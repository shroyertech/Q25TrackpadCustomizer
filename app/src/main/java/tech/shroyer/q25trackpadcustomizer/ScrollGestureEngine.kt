package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * Smooth scroll-wheel emulation using one continuous "finger" per scroll burst (API 26+).
 *
 * The old approach dispatched a separate swipe for every key press. Each new
 * dispatchGesture() cancels the swipe still in progress, so a stream of trackpad key
 * events produced a series of cut-off swipes and lift-off flings: stuttery scrolling.
 *
 * Here the finger stays down while keys keep arriving: each key adds distance to a
 * queue, and the next segment is dispatched as a continuation of the same stroke
 * (StrokeDescription.continueStroke) when the previous one finishes, so movement is
 * one smooth drag. Segment length follows the rate keys arrive at, so the finger is still
 * moving when the next key lands instead of stopping and starting (which reads as jank,
 * and also makes the app's velocity tracker see a stopped finger).
 *
 * When the keys stop: with [inertia] > 0 the finger is released while still moving at its
 * current speed, so the app performs its own native fling (the glide the original
 * one-swipe-per-key version had). With inertia off, the finger is lifted with almost no
 * velocity after a short idle, so scrolling stops where the user stopped. Each drag starts
 * at the screen centre and stays in the middle band; when it runs out of room it is
 * lifted and a new one starts from the centre.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ScrollGestureEngine(private val service: AccessibilityService) {

    private enum class Axis { VERTICAL, HORIZONTAL }

    private val handler = Handler(Looper.getMainLooper())

    private var stroke: GestureDescription.StrokeDescription? = null
    private var axis: Axis? = null
    private var direction = 0          // +1 / -1 finger direction along the axis
    private var fingerX = 0f
    private var fingerY = 0f
    private var segmentInFlight = false
    private var pendingDistance = 0f
    private var generation = 0

    // Release-with-fling in progress: new input waits for it, then starts a new finger.
    private var flingLiftInFlight = false
    private var lastSegmentMove = 0f
    private var lastSegmentMs = 0L
    private var lastEnqueueUptime = 0L
    private var keyIntervalMs = 0f

    /** 0 = stop dead when input stops; 1..3 = release moving so the app flings (longer = faster release). */
    var inertia: Int = 0

    private val liftRunnable = Runnable { lift() }

    /**
     * Queue a scroll. [fingerForward] = true moves the finger toward smaller coordinates
     * (up/left), matching the old gesture direction for the same key.
     */
    fun scrollVertical(fingerForward: Boolean, steps: Int) =
        enqueue(Axis.VERTICAL, if (fingerForward) -1 else 1, steps)

    fun scrollHorizontal(fingerForward: Boolean, steps: Int) =
        enqueue(Axis.HORIZONTAL, if (fingerForward) -1 else 1, steps)

    /** Lift any finger immediately (e.g. when leaving scroll mode). */
    fun cancel() {
        handler.removeCallbacks(liftRunnable)
        if (stroke != null) lift() else reset()
    }

    private fun enqueue(newAxis: Axis, newDirection: Int, steps: Int) {
        handler.removeCallbacks(liftRunnable)

        val now = android.os.SystemClock.uptimeMillis()
        val gap = now - lastEnqueueUptime
        lastEnqueueUptime = now
        keyIntervalMs = when {
            gap > BURST_GAP_MS -> 0f
            keyIntervalMs == 0f -> gap.toFloat()
            else -> keyIntervalMs * 0.6f + gap * 0.4f
        }

        val directionChanged = newAxis != axis || newDirection != direction
        if (directionChanged && flingLiftInFlight) {
            // The release is already on its way; drop queued travel in the old direction.
            pendingDistance = 0f
        }
        if (stroke != null && directionChanged) {
            // Direction change: lift the current finger and start fresh.
            lift()
        }

        axis = newAxis
        direction = newDirection
        pendingDistance += stepDistance(newAxis) * steps.coerceAtLeast(1)

        if (!segmentInFlight && !flingLiftInFlight) dispatchNextSegment()
    }

    private fun stepDistance(a: Axis): Float {
        val dm = service.resources.displayMetrics
        val size = if (a == Axis.VERTICAL) dm.heightPixels else dm.widthPixels
        return size * STEP_FRACTION
    }

    private fun dispatchNextSegment() {
        val a = axis ?: return
        if (pendingDistance <= 0f) {
            if (inertia > 0 && stroke != null) flingLift() else scheduleLift()
            return
        }

        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val min = if (a == Axis.VERTICAL) h * EDGE_MARGIN else w * EDGE_MARGIN
        val max = if (a == Axis.VERTICAL) h * (1 - EDGE_MARGIN) else w * (1 - EDGE_MARGIN)

        var current = stroke
        if (current == null) {
            // New finger always goes down at the screen centre. Starting near an edge can
            // trigger edge gestures instead of scrolling (e.g. a downward drag from the top
            // closes the app drawer or pulls the notification shade).
            fingerX = w / 2f
            fingerY = h / 2f
        }

        val pos = if (a == Axis.VERTICAL) fingerY else fingerX
        val room = if (direction < 0) pos - min else max - pos
        if (room < 1f) {
            // Out of room: lift (keeps momentum like a real swipe) and continue with a new finger.
            liftThenContinue()
            return
        }

        val move = minOf(pendingDistance, room, MAX_SEGMENT_FRACTION * (max - min))
        pendingDistance -= move

        val endX = if (a == Axis.HORIZONTAL) fingerX + direction * move else fingerX
        val endY = if (a == Axis.VERTICAL) fingerY + direction * move else fingerY
        val path = Path().apply {
            moveTo(fingerX, fingerY)
            lineTo(endX, endY)
        }

        var duration = (move / (max - min) * FULL_TRAVEL_MS).toLong().coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        // Keep the finger moving until about when the next key is due.
        if (keyIntervalMs > 0f) {
            val cadence = (keyIntervalMs * 1.15f).toLong().coerceAtMost(MAX_CADENCE_SEGMENT_MS)
            if (cadence > duration) duration = cadence
        }
        val next = try {
            if (current == null) {
                GestureDescription.StrokeDescription(path, 0, duration, true)
            } else {
                current.continueStroke(path, 0, duration, true)
            }
        } catch (_: Exception) {
            reset()
            return
        }

        stroke = next
        fingerX = endX
        fingerY = endY
        lastSegmentMove = move
        lastSegmentMs = duration
        segmentInFlight = true
        val gen = generation

        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    segmentInFlight = false
                    dispatchNextSegment()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    reset()
                }
            },
            handler
        )
        if (!ok) reset()
    }

    private fun scheduleLift() {
        handler.removeCallbacks(liftRunnable)
        handler.postDelayed(liftRunnable, IDLE_LIFT_MS)
    }

    /** End the stroke with a tiny, slow move so the lift doesn't fling. */
    private fun lift() {
        val current = stroke
        generation++
        stroke = null
        segmentInFlight = false
        flingLiftInFlight = false
        pendingDistance = 0f
        if (current == null) return

        val path = Path().apply {
            moveTo(fingerX, fingerY)
            lineTo(fingerX, fingerY + 0.5f)
        }
        try {
            val end = current.continueStroke(path, 0, LIFT_MS, false)
            service.dispatchGesture(GestureDescription.Builder().addStroke(end).build(), null, handler)
        } catch (_: Exception) {
        }
    }

    /**
     * Release the finger while it is still moving at the current speed, so the app's own
     * velocity tracker sees a flick and flings. Input that arrives meanwhile is queued and
     * continues with a new finger once the release lands (like catching a fling).
     */
    private fun flingLift() {
        val current = stroke ?: return
        val a = axis ?: return
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val speed = if (lastSegmentMs > 0) lastSegmentMove / lastSegmentMs else 0f // px per ms
        val gain = FLING_RELEASE_GAIN[inertia.coerceIn(0, FLING_RELEASE_GAIN.size - 1)]
        val pos = if (a == Axis.VERTICAL) fingerY else fingerX
        val limit = if (a == Axis.VERTICAL) h else w
        // Stay on screen: at most up to 2% from the edge.
        val room = if (direction < 0) pos - limit * 0.02f else limit * 0.98f - pos
        val dist = (speed * gain * FLING_TAIL_MS).coerceIn(0.5f, room.coerceAtLeast(0.5f))

        val endX = if (a == Axis.HORIZONTAL) fingerX + direction * dist else fingerX
        val endY = if (a == Axis.VERTICAL) fingerY + direction * dist else fingerY
        val path = Path().apply {
            moveTo(fingerX, fingerY)
            lineTo(endX, endY)
        }

        generation++
        val gen = generation
        stroke = null
        segmentInFlight = false
        flingLiftInFlight = true

        val end = try {
            current.continueStroke(path, 0, FLING_TAIL_MS, false)
        } catch (_: Exception) {
            flingLiftInFlight = false
            reset()
            return
        }

        val after = Runnable {
            if (gen != generation) return@Runnable
            flingLiftInFlight = false
            if (pendingDistance > 0f && !segmentInFlight) dispatchNextSegment()
        }
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(end).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = after.run()
                override fun onCancelled(gestureDescription: GestureDescription?) = after.run()
            },
            handler
        )
        if (!ok) after.run()
    }

    private fun liftThenContinue() {
        val remaining = pendingDistance
        val a = axis
        val d = direction
        lift()
        axis = a
        direction = d
        pendingDistance = remaining
        // Give the lift a moment to land before the new finger goes down.
        segmentInFlight = true
        val gen = generation
        handler.postDelayed({
            if (gen != generation) return@postDelayed
            segmentInFlight = false
            dispatchNextSegment()
        }, LIFT_MS + 10)
    }

    private fun reset() {
        flingLiftInFlight = false
        generation++
        stroke = null
        segmentInFlight = false
        pendingDistance = 0f
        handler.removeCallbacks(liftRunnable)
    }

    companion object {
        // Distance per scroll "step" as a fraction of the screen along the axis.
        // (The old per-press swipe was 15% but lost much of it to cancellation.)
        private const val STEP_FRACTION = 0.10f
        // The finger stays within the middle band of the screen (25%..75%); when it runs out
        // of room it lifts without a fling and a new drag starts from the centre.
        private const val EDGE_MARGIN = 0.25f
        private const val MAX_SEGMENT_FRACTION = 0.5f
        // Time for the finger to cross the whole usable area; sets drag speed.
        private const val FULL_TRAVEL_MS = 250f
        private const val MIN_SEGMENT_MS = 16L
        private const val MAX_SEGMENT_MS = 250L
        private const val IDLE_LIFT_MS = 140L
        private const val LIFT_MS = 40L
        // Keys further apart than this start a new burst (cadence tracking restarts).
        private const val BURST_GAP_MS = 180L
        private const val MAX_CADENCE_SEGMENT_MS = 110L
        // Release segment that carries the finger's speed into the app's fling.
        private const val FLING_TAIL_MS = 24L
        private val FLING_RELEASE_GAIN = floatArrayOf(0f, 0.6f, 0.85f, 1.1f)
    }
}
