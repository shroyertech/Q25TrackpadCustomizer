package tech.shroyer.q25trackpadcustomizer

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * App list with per-app mode selection and a settings dialog.
 * The settings dialog includes:
 * - Scroll-wheel overrides (shown when effective mode is Scroll Wheel)
 * - Auto-keyboard override
 * - Hold-key overrides (mode, keycode, allow-in-text)
 */
class AppListAdapter(
    private val context: Context,
    items: List<AppItem>,
    private val prefs: Prefs
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val allItems: MutableList<AppItem> = items.toMutableList()
    private val filteredItems: MutableList<AppItem> = items.toMutableList()

    private val modeOptions = listOf(
        Mode.FOLLOW_SYSTEM,
        Mode.MOUSE,
        Mode.KEYBOARD,
        Mode.SCROLL_WHEEL
    )

    private val modeLabels = listOf(
        "Follow system",
        "Mouse mode",
        "Keyboard mode",
        "Scroll wheel mode"
    )

    // Hold mode spinner uses null to represent "Follow global" (no override stored).
    private val holdModeOptions: List<HoldMode?> = listOf(
        null,
        HoldMode.DISABLED,
        HoldMode.MOUSE,
        HoldMode.KEYBOARD,
        HoldMode.SCROLL_WHEEL
    )

    private val holdModeLabels = listOf(
        "Follow global",
        "Disabled",
        "Mouse",
        "Keyboard",
        "Scroll wheel"
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_mode, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = filteredItems.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredItems[position]

        holder.tvAppName.text = app.label
        holder.tvPackageName.text = app.packageName
        holder.imgAppIcon.setImageDrawable(app.icon)

        val spinnerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            modeLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        holder.spinnerAppMode.adapter = spinnerAdapter

        val savedMode = prefs.getAppMode(app.packageName) ?: Mode.FOLLOW_SYSTEM
        val index = modeOptions.indexOf(savedMode).coerceAtLeast(0)

        // Avoid triggering onItemSelected during binding
        holder.spinnerAppMode.setSelection(index, false)

        // Gear is always visible (hold settings apply to all modes)
        holder.btnScrollSettings.visibility = View.VISIBLE

        holder.btnScrollSettings.setOnClickListener {
            showAppSettingsDialog(app)
        }

        holder.spinnerAppMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                pos: Int,
                id: Long
            ) {
                val mode = modeOptions[pos]
                prefs.setAppMode(app.packageName, mode)
            }
        }
    }

    private fun showAppSettingsDialog(app: AppItem) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_app_scroll_settings, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvScrollDialogTitle)

        // Scroll controls
        val tvScrollSensitivityLabel = dialogView.findViewById<TextView>(R.id.tvScrollSensitivityLabel)
        val spinnerSens = dialogView.findViewById<Spinner>(R.id.spinnerAppScrollSensitivity)
        val checkHorizontal = dialogView.findViewById<CheckBox>(R.id.checkAppScrollHorizontal)
        val checkInvertV = dialogView.findViewById<CheckBox>(R.id.checkAppScrollInvertVertical)
        val checkInvertH = dialogView.findViewById<CheckBox>(R.id.checkAppScrollInvertHorizontal)

        // Auto keyboard control
        val checkAutoKeyboard = dialogView.findViewById<CheckBox>(R.id.checkAppAutoKeyboard)

        // Hold-key controls (must exist in layout you’re using)
        val spinnerHoldMode = dialogView.findViewById<Spinner>(R.id.spinnerAppHoldMode)
        val tvHoldKeyCode = dialogView.findViewById<TextView>(R.id.tvAppHoldKeyCode)
        val btnSetHoldKey = dialogView.findViewById<View>(R.id.btnSetAppHoldKey)
        val checkHoldAllowedInTextApp = dialogView.findViewById<CheckBox>(R.id.checkHoldAllowedInTextApp)

        tvTitle.text = "Settings for ${app.label}"

        // Determine whether scroll controls should be shown based on EFFECTIVE mode
        val savedMode = prefs.getAppMode(app.packageName) ?: Mode.FOLLOW_SYSTEM
        val effectiveMode = when (savedMode) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> savedMode
            Mode.FOLLOW_SYSTEM -> prefs.getSystemDefaultMode()
        }
        val isScrollEffective = (effectiveMode == Mode.SCROLL_WHEEL)

        // ---- Scroll wheel controls ----
        val sensLabels = listOf("Slow", "Medium", "Fast")
        val sensOptions = listOf(
            ScrollSensitivity.SLOW,
            ScrollSensitivity.MEDIUM,
            ScrollSensitivity.FAST
        )

        val sensAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            sensLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSens.adapter = sensAdapter

        val effectiveScroll = prefs.getEffectiveScrollSettings(app.packageName)
        val currentSensIndex = sensOptions.indexOf(effectiveScroll.sensitivity).coerceAtLeast(0)
        spinnerSens.setSelection(currentSensIndex, false)
        checkHorizontal.isChecked = effectiveScroll.horizontalEnabled
        checkInvertV.isChecked = effectiveScroll.invertVertical
        checkInvertH.isChecked = effectiveScroll.invertHorizontal

        if (!isScrollEffective) {
            tvScrollSensitivityLabel.visibility = View.GONE
            spinnerSens.visibility = View.GONE
            checkHorizontal.visibility = View.GONE
            checkInvertV.visibility = View.GONE
            checkInvertH.visibility = View.GONE
        } else {
            tvScrollSensitivityLabel.visibility = View.VISIBLE
            spinnerSens.visibility = View.VISIBLE
            checkHorizontal.visibility = View.VISIBLE
            checkInvertV.visibility = View.VISIBLE
            checkInvertH.visibility = View.VISIBLE
        }

        // ---- Auto keyboard (effective shown; override saved on Save) ----
        checkAutoKeyboard.isChecked = prefs.isEffectiveAutoKeyboardForTextEnabled(app.packageName)

        // ---- Hold mode spinner (null = follow global) ----
        val holdModeAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            holdModeLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerHoldMode.adapter = holdModeAdapter

        val holdModeOverride = prefs.getAppHoldModeOverride(app.packageName) // HoldMode? (null = follow)
        var pendingHoldModeOverride: HoldMode? = holdModeOverride
        val holdModeIndex = holdModeOptions.indexOf(holdModeOverride).let { if (it >= 0) it else 0 }
        spinnerHoldMode.setSelection(holdModeIndex, false)

        spinnerHoldMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pendingHoldModeOverride = holdModeOptions[position]
            }
        }

        // ---- Hold keycode override (null = follow global) ----
        val holdKeyOverride = prefs.getAppHoldKeyCodeOverride(app.packageName) // Int? (null = follow)
        var pendingHoldKeyOverride: Int? = holdKeyOverride

        fun updateHoldKeyLabel() {
            val effectiveKey = prefs.getEffectiveHoldKeyCode(app.packageName)
            val displayKey = pendingHoldKeyOverride ?: effectiveKey
            val suffix = if (pendingHoldKeyOverride == null) " (global)" else " (override)"
            tvHoldKeyCode.text = "${keyCodeLabel(displayKey)}$suffix"
        }

        updateHoldKeyLabel()

        btnSetHoldKey.setOnClickListener {
            val effectiveKey = prefs.getEffectiveHoldKeyCode(app.packageName)
            showKeyCaptureDialog(
                currentEffectiveKeyCode = effectiveKey,
                onKeyCaptured = { keyCodeOrNull ->
                    pendingHoldKeyOverride = keyCodeOrNull
                    updateHoldKeyLabel()
                }
            )
        }

        // ---- Hold allowed in text fields (store override only if differs from global) ----
        checkHoldAllowedInTextApp.isChecked = prefs.isEffectiveHoldAllowedInTextFields(app.packageName)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("Save") { d, _ ->
                // Save scroll settings only when effective mode is Scroll Wheel.
                if (isScrollEffective) {
                    val selSens = sensOptions[spinnerSens.selectedItemPosition]
                    prefs.setAppScrollSensitivity(app.packageName, selSens)
                    prefs.setAppScrollHorizontalEnabled(app.packageName, checkHorizontal.isChecked)
                    prefs.setAppScrollInvertVertical(app.packageName, checkInvertV.isChecked)
                    prefs.setAppScrollInvertHorizontal(app.packageName, checkInvertH.isChecked)
                }

                // Save per-app auto keyboard override (null = follow global)
                val desiredAuto = checkAutoKeyboard.isChecked
                val globalAuto = prefs.isGlobalAutoKeyboardForTextEnabled()
                if (desiredAuto == globalAuto) {
                    prefs.setAppAutoKeyboardOverride(app.packageName, null)
                } else {
                    prefs.setAppAutoKeyboardOverride(app.packageName, desiredAuto)
                }

                // Save hold mode override (null = follow global)
                prefs.setAppHoldModeOverride(app.packageName, pendingHoldModeOverride)

                // Save hold key override (null = follow global)
                prefs.setAppHoldKeyCodeOverride(app.packageName, pendingHoldKeyOverride)

                // Save allow-in-text override (null = follow global)
                val desiredAllow = checkHoldAllowedInTextApp.isChecked
                val globalAllow = prefs.isGlobalHoldAllowedInTextFields()
                if (desiredAllow == globalAllow) {
                    prefs.setAppHoldAllowedInTextFieldsOverride(app.packageName, null)
                } else {
                    prefs.setAppHoldAllowedInTextFieldsOverride(app.packageName, desiredAllow)
                }

                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()
    }

    private fun showKeyCaptureDialog(
        currentEffectiveKeyCode: Int,
        onKeyCaptured: (Int?) -> Unit
    ) {
        val message = "Press the key you want to use as the hold modifier.\n\n" +
                "Current effective: $currentEffectiveKeyCode (${KeyEvent.keyCodeToString(currentEffectiveKeyCode)})"

        val dialog = AlertDialog.Builder(context)
            .setTitle("Set hold key")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use global") { d, _ ->
                d.dismiss()
                onKeyCaptured(null) // clear override
            }
            .create()

        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false

            onKeyCaptured(keyCode)
            d.dismiss()
            true
        }

        dialog.show()
    }

    private fun keyCodeLabel(keyCode: Int): String {
        val raw = KeyEvent.keyCodeToString(keyCode)
        return raw.removePrefix("KEYCODE_").replace('_', ' ')
    }

    fun filter(query: String) {
        val lower = query.lowercase()
        filteredItems.clear()
        if (lower.isEmpty()) {
            filteredItems.addAll(allItems)
        } else {
            filteredItems.addAll(
                allItems.filter {
                    it.label.lowercase().contains(lower) ||
                            it.packageName.lowercase().contains(lower)
                }
            )
        }
        notifyDataSetChanged()
    }

    fun updateApps(newItems: List<AppItem>) {
        allItems.clear()
        allItems.addAll(newItems)
        filteredItems.clear()
        filteredItems.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgAppIcon: ImageView = view.findViewById(R.id.imgAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
        val spinnerAppMode: Spinner = view.findViewById(R.id.spinnerAppMode)
        val btnScrollSettings: ImageButton = view.findViewById(R.id.btnScrollSettings)
    }
}