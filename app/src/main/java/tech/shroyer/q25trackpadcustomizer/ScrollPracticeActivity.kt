package tech.shroyer.q25trackpadcustomizer

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * Scroll practice: a big board that scrolls both ways, with the scroll method and speed
 * right above it, so you can try each setting with the trackpad and feel the difference.
 * While it's open the trackpad is put in Scroll wheel mode and the global scroll
 * settings are used; the previous mode comes back when you leave.
 */
class ScrollPracticeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var board: PracticeCanvasView
    private lateinit var tvPosition: TextView
    private lateinit var tvStatus: TextView

    private var modeBefore: Mode? = null

    private val methods = listOf(ScrollMethod.CLASSIC, ScrollMethod.TOUCH)
    private val methodLabels = listOf("Classic", "Touch (root)")
    private val speeds = listOf(ScrollSensitivity.SLOW, ScrollSensitivity.MEDIUM, ScrollSensitivity.FAST)
    private val speedLabels = listOf("Slow", "Medium", "Fast")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        // Title row: title, position, reset.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), 0)
        }
        titleRow.addView(TextView(this).apply {
            text = "‹ Scroll practice"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvPosition = TextView(this).apply { textSize = 12f; setPadding(0, 0, dp(8), 0) }
        titleRow.addView(tvPosition)
        titleRow.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
            text = "Reset"
            setOnClickListener { board.reset() }
        })
        root.addView(titleRow)

        // Method + speed.
        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val current = prefs.getGlobalScrollSettings()
        val spMethod = spinner(methodLabels, methods.indexOf(current.method)) { i ->
            prefs.setGlobalScrollMethod(methods[i])
            updateStatus()
        }
        val spSpeed = spinner(speedLabels, speeds.indexOf(current.sensitivity)) { i ->
            prefs.setGlobalScrollSensitivity(speeds[i])
        }
        pickRow.addView(spMethod, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pickRow.addView(spSpeed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(pickRow)

        // Direction options.
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        optRow.addView(check("Sideways", current.horizontalEnabled) { prefs.setGlobalScrollHorizontalEnabled(it) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        optRow.addView(check("Invert ↕", current.invertVertical) { prefs.setGlobalScrollInvertVertical(it) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        optRow.addView(check("Invert ↔", current.invertHorizontal) { prefs.setGlobalScrollInvertHorizontal(it) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(optRow)

        tvStatus = TextView(this).apply {
            textSize = 11f
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        root.addView(tvStatus)

        board = PracticeCanvasView(this)
        board.onPositionChanged = { c, r -> tvPosition.text = "${c + 1}, ${r + 1}" }
        root.addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        tvPosition.text = "1, 1"
    }

    override fun onResume() {
        super.onResume()
        AppState.practiceActive = true
        modeBefore = AppState.currentMode
        if (AppState.currentMode != Mode.SCROLL_WHEEL) setMode(Mode.SCROLL_WHEEL)
        AppSwitchService.instance?.refreshScrollState()
        updateStatus()
        board.requestFocus()
    }

    override fun onPause() {
        AppState.practiceActive = false
        val before = modeBefore
        if (before != null && before != Mode.SCROLL_WHEEL && AppState.currentMode == Mode.SCROLL_WHEEL) {
            setMode(before)
        }
        AppSwitchService.instance?.refreshScrollState()
        super.onPause()
    }

    private fun setMode(mode: Mode) {
        AppState.currentMode = mode
        prefs.setLastKnownMode(mode)
        TrackpadController.requestMode(mode)
    }

    private fun updateStatus() {
        val method = prefs.getGlobalScrollSettings().method
        tvStatus.text = when {
            AppSwitchService.instance == null ->
                "The accessibility service is off, so the trackpad can't scroll here. Turn it on in Settings first."
            method == ScrollMethod.TOUCH ->
                "Slide on the trackpad. Touch moves the board like a finger (needs root). These are your default scroll settings for all apps."
            else ->
                "Slide on the trackpad. Classic sends short swipes. These are your default scroll settings for all apps."
        }
    }

    private fun spinner(labels: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sp.setSelection(selected.coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        return sp
    }

    private fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            textSize = 12f
            isChecked = checked
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
}
