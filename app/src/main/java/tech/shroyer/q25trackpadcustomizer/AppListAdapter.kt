package tech.shroyer.q25trackpadcustomizer

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

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

    // null = follow global (no override stored)
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
        holder.spinnerAppMode.setSelection(index, false)

        holder.btnScrollSettings.visibility = View.VISIBLE
        holder.btnScrollSettings.setOnClickListener { showAppSettingsDialog(app) }

        holder.spinnerAppMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.setAppMode(app.packageName, modeOptions[pos])
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

        // Auto keyboard
        val checkAutoKeyboard = dialogView.findViewById<CheckBox>(R.id.checkAppAutoKeyboard)

        // Primary Hold
        val spinnerHold1Mode = dialogView.findViewById<Spinner>(R.id.spinnerAppHoldMode)
        val tvHold1KeyCode = dialogView.findViewById<TextView>(R.id.tvAppHoldKeyCode)
        val btnSetHold1Key = dialogView.findViewById<Button>(R.id.btnSetAppHoldKey)
        val checkHold1AllowInText = dialogView.findViewById<CheckBox>(R.id.checkHoldAllowedInTextApp)
        val checkHold1DoubleRequired = dialogView.findViewById<CheckBox>(R.id.checkAppHoldDoublePressRequired)

        // Secondary Hold
        val checkHold2UseHold1Double = dialogView.findViewById<CheckBox>(R.id.checkAppHold2UseHold1DoublePressHold)
        val spinnerHold2Mode = dialogView.findViewById<Spinner>(R.id.spinnerAppHold2Mode)
        val tvHold2KeyCode = dialogView.findViewById<TextView>(R.id.tvAppHold2KeyCode)
        val btnSetHold2Key = dialogView.findViewById<Button>(R.id.btnSetAppHold2Key)
        val checkHold2AllowInText = dialogView.findViewById<CheckBox>(R.id.checkHold2AllowedInTextApp)
        val checkHold2DoubleRequired = dialogView.findViewById<CheckBox>(R.id.checkAppHold2DoublePressRequired)

        tvTitle.text = "Settings for ${app.label}"

        val savedMode = prefs.getAppMode(app.packageName) ?: Mode.FOLLOW_SYSTEM
        val effectiveMode = when (savedMode) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> savedMode
            Mode.FOLLOW_SYSTEM -> prefs.getSystemDefaultMode()
        }
        val isScrollEffective = (effectiveMode == Mode.SCROLL_WHEEL)

        // ----- Scroll wheel -----
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
        spinnerSens.setSelection(sensOptions.indexOf(effectiveScroll.sensitivity).coerceAtLeast(0), false)
        checkHorizontal.isChecked = effectiveScroll.horizontalEnabled
        checkInvertV.isChecked = effectiveScroll.invertVertical
        checkInvertH.isChecked = effectiveScroll.invertHorizontal

        val scrollVis = if (isScrollEffective) View.VISIBLE else View.GONE
        tvScrollSensitivityLabel.visibility = scrollVis
        spinnerSens.visibility = scrollVis
        checkHorizontal.visibility = scrollVis
        checkInvertV.visibility = scrollVis
        checkInvertH.visibility = scrollVis

        // ----- Auto keyboard -----
        checkAutoKeyboard.isChecked = prefs.isEffectiveAutoKeyboardForTextEnabled(app.packageName)

        // ----- Hold mode adapters -----
        val holdModeAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            holdModeLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerHold1Mode.adapter = holdModeAdapter
        spinnerHold2Mode.adapter = holdModeAdapter

        // ----- Pending values -----
        val hold1ModeOverride = prefs.getAppHoldModeOverride(app.packageName)
        var pendingHold1ModeOverride: HoldMode? = hold1ModeOverride

        val hold1KeyOverride = prefs.getAppHoldKeyCodeOverride(app.packageName)
        var pendingHold1KeyOverride: Int? = hold1KeyOverride

        var pendingHold1AllowInText = prefs.isEffectiveHoldAllowedInTextFields(app.packageName)
        var pendingHold1DoubleRequired = prefs.isEffectiveHoldDoublePressRequired(app.packageName)

        var pendingHold2ModeOverride: HoldMode? = prefs.getAppHold2ModeOverride(app.packageName)
        var pendingHold2KeyOverride: Int? = prefs.getAppHold2KeyCodeOverride(app.packageName)
        var pendingHold2AllowInText = prefs.isEffectiveHold2AllowedInTextFields(app.packageName)
        var pendingHold2DoubleRequired = prefs.isEffectiveHold2DoublePressRequired(app.packageName)
        var pendingHold2UseHold1Double = prefs.isEffectiveHold2UseHold1DoublePressHold(app.packageName)

        fun effectiveHold2Mode(): HoldMode = pendingHold2ModeOverride ?: prefs.getGlobalHold2Mode()

        fun updateHold1KeyLabel() {
            val effectiveKey = prefs.getEffectiveHoldKeyCode(app.packageName)
            val displayKey = pendingHold1KeyOverride ?: effectiveKey
            val suffix = if (pendingHold1KeyOverride == null) " (global)" else " (override)"
            tvHold1KeyCode.text = "${formatKeyLabel(displayKey)}$suffix"
        }

        fun updateHold2KeyLabel() {
            val enabled = effectiveHold2Mode() != HoldMode.DISABLED
            val tied = pendingHold2UseHold1Double

            tvHold2KeyCode.alpha = if (enabled) 1f else 0.5f
            tvHold2KeyCode.text = when {
                !enabled -> "Disabled"
                tied -> "Tied to Primary Hold double-press + hold"
                else -> {
                    val effectiveKey = prefs.getEffectiveHold2KeyCode(app.packageName)
                    val displayKey = pendingHold2KeyOverride ?: effectiveKey
                    val suffix = if (pendingHold2KeyOverride == null) " (global)" else " (override)"
                    "${formatKeyLabel(displayKey)}$suffix"
                }
            }
        }

        fun updateHold2Ui() {
            val enabled = effectiveHold2Mode() != HoldMode.DISABLED
            if (!enabled && pendingHold2UseHold1Double) {
                pendingHold2UseHold1Double = false
                checkHold2UseHold1Double.isChecked = false
            }

            checkHold2UseHold1Double.isEnabled = enabled
            checkHold2UseHold1Double.alpha = if (enabled) 1f else 0.5f

            val tied = pendingHold2UseHold1Double

            btnSetHold2Key.isEnabled = enabled && !tied
            btnSetHold2Key.alpha = if (btnSetHold2Key.isEnabled) 1f else 0.5f

            val dblEnabled = enabled && !tied
            checkHold2DoubleRequired.isEnabled = dblEnabled
            checkHold2DoubleRequired.alpha = if (dblEnabled) 1f else 0.5f

            if (tied) {
                pendingHold2DoubleRequired = false
                checkHold2DoubleRequired.isChecked = false
            }

            checkHold2AllowInText.isEnabled = enabled
            checkHold2AllowInText.alpha = if (enabled) 1f else 0.5f

            updateHold2KeyLabel()
        }

        // ----- Init UI -----
        spinnerHold1Mode.setSelection(holdModeOptions.indexOf(hold1ModeOverride).let { if (it >= 0) it else 0 }, false)
        spinnerHold2Mode.setSelection(holdModeOptions.indexOf(pendingHold2ModeOverride).let { if (it >= 0) it else 0 }, false)

        checkHold1AllowInText.isChecked = pendingHold1AllowInText
        checkHold1DoubleRequired.isChecked = pendingHold1DoubleRequired

        checkHold2AllowInText.isChecked = pendingHold2AllowInText
        checkHold2DoubleRequired.isChecked = pendingHold2DoubleRequired
        checkHold2UseHold1Double.isChecked = pendingHold2UseHold1Double

        updateHold1KeyLabel()
        updateHold2Ui()

        // ----- Listeners -----
        spinnerHold1Mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pendingHold1ModeOverride = holdModeOptions[position]
            }
        }

        btnSetHold1Key.setOnClickListener {
            val effectiveKey = prefs.getEffectiveHoldKeyCode(app.packageName)
            showKeyCaptureDialog(
                title = "Set Primary Hold key",
                currentEffectiveKeyCode = effectiveKey,
                onKeyCaptured = { keyCodeOrNull ->
                    pendingHold1KeyOverride = keyCodeOrNull
                    updateHold1KeyLabel()
                }
            )
        }

        checkHold1AllowInText.setOnCheckedChangeListener { _, isChecked ->
            pendingHold1AllowInText = isChecked
        }

        checkHold1DoubleRequired.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && pendingHold2UseHold1Double) {
                AlertDialog.Builder(context)
                    .setTitle("Conflict")
                    .setMessage(
                        "Secondary Hold is currently set to trigger from Primary Hold double-press + hold.\n\n" +
                                "Enabling Primary Hold double-press requirement would disable that Secondary Hold option for this app. Continue?"
                    )
                    .setPositiveButton("Disable Secondary Hold tie") { d, _ ->
                        d.dismiss()
                        pendingHold2UseHold1Double = false
                        checkHold2UseHold1Double.isChecked = false
                        pendingHold1DoubleRequired = true
                        updateHold2Ui()
                    }
                    .setNegativeButton("Cancel") { d, _ ->
                        d.dismiss()
                        buttonView.isChecked = false
                    }
                    .show()
                return@setOnCheckedChangeListener
            }
            pendingHold1DoubleRequired = isChecked
        }

        checkHold2UseHold1Double.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                if (pendingHold1DoubleRequired) {
                    AlertDialog.Builder(context)
                        .setTitle("Conflict")
                        .setMessage(
                            "Enabling this will disable “Primary Hold: require double press + hold” for this app to avoid conflicts.\n\nContinue?"
                        )
                        .setPositiveButton("Continue") { d, _ ->
                            d.dismiss()
                            pendingHold1DoubleRequired = false
                            checkHold1DoubleRequired.isChecked = false
                            pendingHold2UseHold1Double = true
                            pendingHold2DoubleRequired = false
                            checkHold2DoubleRequired.isChecked = false
                            updateHold2Ui()
                        }
                        .setNegativeButton("Cancel") { d, _ ->
                            d.dismiss()
                            buttonView.isChecked = false
                        }
                        .show()
                    return@setOnCheckedChangeListener
                }

                pendingHold2UseHold1Double = true
                pendingHold2DoubleRequired = false
                checkHold2DoubleRequired.isChecked = false
                updateHold2Ui()
            } else {
                pendingHold2UseHold1Double = false
                updateHold2Ui()
            }
        }

        spinnerHold2Mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pendingHold2ModeOverride = holdModeOptions[position]
                updateHold2Ui()
            }
        }

        btnSetHold2Key.setOnClickListener {
            if (pendingHold2UseHold1Double) {
                Toast.makeText(context, "Secondary Hold key is tied to Primary Hold double-press + hold.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val effectiveKey = prefs.getEffectiveHold2KeyCode(app.packageName)
            showKeyCaptureDialog(
                title = "Set Secondary Hold key",
                currentEffectiveKeyCode = effectiveKey,
                onKeyCaptured = { keyCodeOrNull ->
                    pendingHold2KeyOverride = keyCodeOrNull
                    updateHold2Ui()
                }
            )
        }

        checkHold2AllowInText.setOnCheckedChangeListener { _, isChecked ->
            pendingHold2AllowInText = isChecked
        }

        checkHold2DoubleRequired.setOnCheckedChangeListener { buttonView, isChecked ->
            if (pendingHold2UseHold1Double) {
                buttonView.isChecked = false
                Toast.makeText(context, "Not applicable while Secondary Hold is tied to Primary Hold double-press.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            pendingHold2DoubleRequired = isChecked
        }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("Save") { d, _ ->
                if (isScrollEffective) {
                    val selSens = sensOptions[spinnerSens.selectedItemPosition]
                    prefs.setAppScrollSensitivity(app.packageName, selSens)
                    prefs.setAppScrollHorizontalEnabled(app.packageName, checkHorizontal.isChecked)
                    prefs.setAppScrollInvertVertical(app.packageName, checkInvertV.isChecked)
                    prefs.setAppScrollInvertHorizontal(app.packageName, checkInvertH.isChecked)
                }

                val desiredAuto = checkAutoKeyboard.isChecked
                val globalAuto = prefs.isGlobalAutoKeyboardForTextEnabled()
                prefs.setAppAutoKeyboardOverride(app.packageName, if (desiredAuto == globalAuto) null else desiredAuto)

                prefs.setAppHoldModeOverride(app.packageName, pendingHold1ModeOverride)
                prefs.setAppHoldKeyCodeOverride(app.packageName, pendingHold1KeyOverride)

                val globalAllow1 = prefs.isGlobalHoldAllowedInTextFields()
                prefs.setAppHoldAllowedInTextFieldsOverride(app.packageName, if (pendingHold1AllowInText == globalAllow1) null else pendingHold1AllowInText)

                val globalHold1Double = prefs.isGlobalHoldDoublePressRequired()
                prefs.setAppHoldDoublePressRequiredOverride(
                    app.packageName,
                    if (pendingHold1DoubleRequired == globalHold1Double) null else pendingHold1DoubleRequired
                )

                prefs.setAppHold2ModeOverride(app.packageName, pendingHold2ModeOverride)
                prefs.setAppHold2KeyCodeOverride(app.packageName, pendingHold2KeyOverride)

                val globalAllow2 = prefs.isGlobalHold2AllowedInTextFields()
                prefs.setAppHold2AllowedInTextFieldsOverride(app.packageName, if (pendingHold2AllowInText == globalAllow2) null else pendingHold2AllowInText)

                val globalHold2Double = prefs.isGlobalHold2DoublePressRequired()
                val finalHold2Double = if (pendingHold2UseHold1Double) false else pendingHold2DoubleRequired
                prefs.setAppHold2DoublePressRequiredOverride(
                    app.packageName,
                    if (finalHold2Double == globalHold2Double) null else finalHold2Double
                )

                val globalTie = prefs.isGlobalHold2UseHold1DoublePressHold()
                prefs.setAppHold2UseHold1DoublePressHoldOverride(
                    app.packageName,
                    if (pendingHold2UseHold1Double == globalTie) null else pendingHold2UseHold1Double
                )

                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()
    }

    private fun showKeyCaptureDialog(
        title: String,
        currentEffectiveKeyCode: Int,
        onKeyCaptured: (Int?) -> Unit
    ) {
        val message = "Press the key you want to use.\n\n" +
                "Current effective: ${formatKeyLabel(currentEffectiveKeyCode)}"

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use global") { d, _ ->
                d.dismiss()
                onKeyCaptured(null)
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

    private fun formatKeyLabel(keyCode: Int): String {
        val raw = KeyEvent.keyCodeToString(keyCode)
        val nice = raw.removePrefix("KEYCODE_").replace('_', ' ')
        return "$nice ($keyCode)"
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