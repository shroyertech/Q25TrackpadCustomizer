package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A large board that scrolls both up/down and sideways, used by the scroll practice page.
 * It moves like a normal app's list (drag + fling), so the feel matches real apps.
 */
class PracticeCanvasView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val cell = 120f * density            // one grid square
    private val cols = 24
    private val rows = 60
    private val boardW = cols * cell
    private val boardH = rows * cell

    private var offX = 0f
    private var offY = 0f

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /** Called with the visible position (in grid squares) whenever it changes. */
    var onPositionChanged: ((col: Int, row: Int) -> Unit)? = null

    private val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val bgPaint = Paint().apply { color = if (dark) Color.rgb(24, 24, 28) else Color.rgb(250, 250, 252) }
    private val gridPaint = Paint().apply {
        color = if (dark) Color.rgb(60, 60, 68) else Color.rgb(215, 215, 222)
        strokeWidth = 1f * density
    }
    private val majorPaint = Paint().apply {
        color = if (dark) Color.rgb(110, 110, 125) else Color.rgb(160, 160, 175)
        strokeWidth = 2f * density
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.rgb(225, 225, 235) else Color.rgb(40, 40, 50)
        textSize = 14f * density
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelPaint.color
        textSize = 20f * density
        isFakeBoldText = true
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.argb(150, 255, 255, 255) else Color.argb(120, 0, 0, 0)
    }
    private val rect = RectF()

    // Soft colours for the row bands, so it's easy to see how far you moved.
    private val bandColors = intArrayOf(
        Color.rgb(239, 83, 80), Color.rgb(255, 167, 38), Color.rgb(255, 238, 88),
        Color.rgb(102, 187, 106), Color.rgb(66, 165, 245), Color.rgb(171, 71, 188)
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun reset() {
        scroller.forceFinished(true)
        offX = 0f
        offY = 0f
        changed()
    }

    private fun maxX() = max(0f, boardW - width)
    private fun maxY() = max(0f, boardH - height)

    private fun moveBy(dx: Float, dy: Float) {
        offX = (offX + dx).coerceIn(0f, maxX())
        offY = (offY + dy).coerceIn(0f, maxY())
        changed()
    }

    private fun changed() {
        onPositionChanged?.invoke((offX / cell).toInt(), (offY / cell).toInt())
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        moveBy(0f, 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastX = event.x
                lastY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = lastX - event.x
                val dy = lastY - event.y
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    moveBy(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000, maxFling.toFloat())
                val vx = -(velocity?.xVelocity ?: 0f)
                val vy = -(velocity?.yVelocity ?: 0f)
                if (abs(vx) > minFling || abs(vy) > minFling) {
                    scroller.fling(
                        offX.toInt(), offY.toInt(), vx.toInt(), vy.toInt(),
                        0, maxX().toInt(), 0, maxY().toInt()
                    )
                    postInvalidateOnAnimation()
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    // Mouse wheel / injected scroll events.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val step = 64f * density
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            scroller.forceFinished(true)
            moveBy(h * step, -v * step)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat().coerceIn(0f, maxX())
            offY = scroller.currY.toFloat().coerceIn(0f, maxY())
            changed()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val c0 = (offX / cell).toInt().coerceAtLeast(0)
        val c1 = min(cols - 1, ((offX + width) / cell).toInt())
        val r0 = (offY / cell).toInt().coerceAtLeast(0)
        val r1 = min(rows - 1, ((offY + height) / cell).toInt())

        // Tiles: a coloured band every row, slightly different per column.
        val pad = 10f * density
        for (r in r0..r1) {
            val base = bandColors[(r / 2) % bandColors.size]
            for (c in c0..c1) {
                val left = c * cell - offX
                val top = r * cell - offY
                tilePaint.color = base
                tilePaint.alpha = if ((r + c) % 2 == 0) 70 else 40
                rect.set(left + pad, top + pad, left + cell - pad, top + cell - pad)
                canvas.drawRoundRect(rect, 12f * density, 12f * density, tilePaint)
                canvas.drawText("${c + 1}, ${r + 1}", left + pad * 1.6f, top + pad * 1.6f + labelPaint.textSize, labelPaint)
            }
        }

        // Grid lines; every 5th line is bold.
        for (c in c0..c1 + 1) {
            val x = c * cell - offX
            canvas.drawLine(x, 0f, x, height.toFloat(), if (c % 5 == 0) majorPaint else gridPaint)
        }
        for (r in r0..r1 + 1) {
            val y = r * cell - offY
            canvas.drawLine(0f, y, width.toFloat(), y, if (r % 5 == 0) majorPaint else gridPaint)
        }

        // Row markers down the left side so vertical progress is easy to read.
        for (r in r0..r1) {
            if (r % 5 != 0) continue
            val y = r * cell - offY + cell / 2 + bigPaint.textSize / 2
            canvas.drawText("Row ${r + 1}", 8f * density, y, bigPaint)
        }

        // Scroll bars.
        val t = 4f * density
        if (boardH > height) {
            val len = height * height / boardH
            val top = (height - len) * (offY / maxY().coerceAtLeast(1f))
            rect.set(width - t - 2f * density, top, width - 2f * density, top + len)
            canvas.drawRoundRect(rect, t, t, barPaint)
        }
        if (boardW > width) {
            val len = width * width / boardW
            val left = (width - len) * (offX / maxX().coerceAtLeast(1f))
            rect.set(left, height - t - 2f * density, left + len, height - 2f * density)
            canvas.drawRoundRect(rect, t, t, barPaint)
        }
    }
}
