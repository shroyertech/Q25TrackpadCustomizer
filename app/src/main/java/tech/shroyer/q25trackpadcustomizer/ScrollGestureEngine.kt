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
 * one smooth drag. After a short idle period the finger is lifted with almost no
 * velocity, so scrolling stops where the user stopped (no extra fling). If the finger
 * would leave the usable screen area, the stroke is lifted and a new one starts.
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

        if (stroke != null && (newAxis != axis || newDirection != direction)) {
            // Direction change: lift the current finger and start fresh.
            lift()
        }

        axis = newAxis
        direction = newDirection
        pendingDistance += stepDistance(newAxis) * steps.coerceAtLeast(1)

        if (!segmentInFlight) dispatchNextSegment()
    }

    private fun stepDistance(a: Axis): Float {
        val dm = service.resources.displayMetrics
        val size = if (a == Axis.VERTICAL) dm.heightPixels else dm.widthPixels
        return size * STEP_FRACTION
    }

    private fun dispatchNextSegment() {
        val a = axis ?: return
        if (pendingDistance <= 0f) {
            scheduleLift()
            return
        }

        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val min = if (a == Axis.VERTICAL) h * EDGE_MARGIN else w * EDGE_MARGIN
        val max = if (a == Axis.VERTICAL) h * (1 - EDGE_MARGIN) else w * (1 - EDGE_MARGIN)

        var current = stroke
        if (current == null) {
            // New finger: start on the side opposite to travel, to leave room.
            fingerX = w / 2f
            fingerY = h / 2f
            val start = if (direction < 0) max else min
            if (a == Axis.VERTICAL) fingerY = start else fingerX = start
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

        val duration = (move / (max - min) * FULL_TRAVEL_MS).toLong().coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
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
        private const val EDGE_MARGIN = 0.15f
        private const val MAX_SEGMENT_FRACTION = 0.5f
        // Time for the finger to cross the whole usable area; sets drag speed.
        private const val FULL_TRAVEL_MS = 450f
        private const val MIN_SEGMENT_MS = 16L
        private const val MAX_SEGMENT_MS = 250L
        private const val IDLE_LIFT_MS = 140L
        private const val LIFT_MS = 40L
    }
}
