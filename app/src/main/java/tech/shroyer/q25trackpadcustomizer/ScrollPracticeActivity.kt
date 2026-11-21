package tech.shroyer.q25trackpadcustomizer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Scroll practice: a big board that scrolls both ways, with the scroll method, speed and
 * inertia right above it, so you can try each setting with the trackpad and feel the
 * difference. While it's open the trackpad is put in Scroll wheel mode and the global
 * scroll settings are used; the previous mode comes back when you leave. Everything the
 * trackpad and the board do is logged, and "Share log" sends a report.
 */
class ScrollPracticeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var board: PracticeCanvasView
    private lateinit var tvPosition: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spInertia: Spinner

    private var modeBefore: Mode? = null

    private val methods = listOf(ScrollMethod.CLASSIC, ScrollMethod.TOUCH)
    private val methodLabels = listOf("Classic", "Touch")
    private val speeds = listOf(ScrollSensitivity.SLOW, ScrollSensitivity.MEDIUM, ScrollSensitivity.FAST)
    private val speedLabels = listOf("Slow", "Medium", "Fast")
    private val inertias = listOf(ScrollInertia.OFF, ScrollInertia.LOW, ScrollInertia.MEDIUM, ScrollInertia.HIGH)
    private val inertiaLabels = listOf("Glide: off", "Glide: low", "Glide: normal", "Glide: high")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        // Title row: title, position, share, reset.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(4), 0)
        }
        titleRow.addView(TextView(this).apply {
            text = "‹ Scroll practice"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvPosition = TextView(this).apply { textSize = 11f; setPadding(0, 0, dp(4), 0) }
        titleRow.addView(tvPosition)
        titleRow.addView(textButton("Share log") { shareLog() })
        titleRow.addView(textButton("Reset") {
            board.reset()
            PracticeLog.clear()
            logSettings("reset")
        })
        root.addView(titleRow)

        // Method, speed, inertia.
        val current = prefs.getGlobalScrollSettings()
        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val spMethod = spinner(methodLabels, methods.indexOf(current.method)) { i ->
            if (prefs.getGlobalScrollSettings().method != methods[i]) {
                prefs.setGlobalScrollMethod(methods[i])
                logSettings("method")
            }
            updateStatus()
        }
        val spSpeed = spinner(speedLabels, speeds.indexOf(current.sensitivity)) { i ->
            if (prefs.getGlobalScrollSettings().sensitivity != speeds[i]) {
                prefs.setGlobalScrollSensitivity(speeds[i])
                logSettings("speed")
            }
        }
        spInertia = spinner(inertiaLabels, inertias.indexOf(prefs.getClassicInertia())) { i ->
            if (prefs.getClassicInertia() != inertias[i]) {
                prefs.setClassicInertia(inertias[i])
                logSettings("inertia")
            }
        }
        pickRow.addView(spMethod, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        pickRow.addView(spSpeed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        pickRow.addView(spInertia, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        root.addView(pickRow)

        // Direction options.
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        optRow.addView(check("Sideways", current.horizontalEnabled) {
            prefs.setGlobalScrollHorizontalEnabled(it); logSettings("sideways")
        }, lp())
        optRow.addView(check("Invert ↕", current.invertVertical) {
            prefs.setGlobalScrollInvertVertical(it); logSettings("invert v")
        }, lp())
        optRow.addView(check("Invert ↔", current.invertHorizontal) {
            prefs.setGlobalScrollInvertHorizontal(it); logSettings("invert h")
        }, lp())
        root.addView(optRow)

        tvStatus = TextView(this).apply {
            textSize = 10.5f
            setPadding(dp(10), 0, dp(10), dp(3))
        }
        root.addView(tvStatus)

        board = PracticeCanvasView(this)
        board.onPositionChanged = { c, r -> tvPosition.text = "${c + 1}, ${r + 1}" }
        board.log = { PracticeLog.add(it) }
        root.addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        tvPosition.text = "1, 1"
        PracticeLog.clear()
    }

    override fun onResume() {
        super.onResume()
        AppState.practiceActive = true
        modeBefore = AppState.currentMode
        if (AppState.currentMode != Mode.SCROLL_WHEEL) setMode(Mode.SCROLL_WHEEL)
        AppSwitchService.instance?.refreshScrollState()
        logSettings("open")
        updateStatus()
        board.requestFocus()
    }

    override fun onPause() {
        PracticeLog.add("closed")
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

    private fun settingsLine(): String {
        val s = prefs.getGlobalScrollSettings()
        return "method=${s.method} speed=${s.sensitivity} glide=${prefs.getClassicInertia()} " +
                "sideways=${s.horizontalEnabled} invV=${s.invertVertical} invH=${s.invertHorizontal}"
    }

    private fun logSettings(why: String) {
        PracticeLog.add("settings ($why) ${settingsLine()} mode=${AppState.currentMode}")
    }

    private fun updateStatus() {
        val method = prefs.getGlobalScrollSettings().method
        spInertia.visibility = if (method == ScrollMethod.CLASSIC) View.VISIBLE else View.INVISIBLE
        tvStatus.text = when {
            AppSwitchService.instance == null ->
                "The accessibility service is off, so the trackpad can't scroll here."
            method == ScrollMethod.TOUCH ->
                "Touch moves the board like a finger (needs root). These are your default settings for all apps."
            else ->
                "Classic sends short swipes. These are your default settings for all apps."
        }
    }

    private fun shareLog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val report = buildString {
            append("Q25 Trackpad Customizer scroll practice log\n")
            append("App $version, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Settings: ${settingsLine()}\n")
            append("Mode: ${AppState.currentMode}, service ${if (AppSwitchService.instance != null) "on" else "off"}\n")
            append("Lines: time ms since start, then the event.\n")
            append("  r dx dy = trackpad motion, g down/up/turn/hold/regrip = virtual finger,\n")
            append("  key = Classic scroll key, swipe = Classic swipe, board = what the board did\n\n")
            append(PracticeLog.dump())
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Scroll practice log")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try {
            startActivity(Intent.createChooser(send, "Share log"))
        } catch (_: Exception) {
        }
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            val tv = TypedValue()
            if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) setTextColor(tv.data)
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }

    private fun spinner(labels: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
        val sp = Spinner(this)
        sp.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as? TextView)?.apply { textSize = 13f; maxLines = 1 }
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
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
