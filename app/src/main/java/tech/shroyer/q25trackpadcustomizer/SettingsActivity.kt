package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    // Header
    private lateinit var tvSettingsVersion: TextView

    // Toast/Theme UI
    private lateinit var checkToastQuick: CheckBox
    private lateinit var checkToastPerApp: CheckBox
    private lateinit var checkToastDefault: CheckBox
    private lateinit var checkToastHoldKey: CheckBox
    private lateinit var spinnerTheme: Spinner

    // Auto keyboard for text input
    private lateinit var checkAutoKeyboardText: CheckBox

    // Hold-key global settings UI
    private lateinit var spinnerGlobalHoldMode: Spinner
    private lateinit var tvGlobalHoldKeyCode: TextView
    private lateinit var btnSetGlobalHoldKey: Button
    private lateinit var checkHoldAllowedInTextGlobal: CheckBox

    // Global scroll settings UI
    private lateinit var spinnerScrollSensitivity: Spinner
    private lateinit var checkScrollHorizontal: CheckBox
    private lateinit var checkScrollInvertVertical: CheckBox
    private lateinit var checkScrollInvertHorizontal: CheckBox

    // Backup/restore UI
    private lateinit var btnBackupSettings: Button
    private lateinit var btnRestoreSettings: Button

    // Exclusions UI
    private lateinit var btnAddExcludedApps: Button
    private lateinit var recyclerExcludedApps: RecyclerView
    private lateinit var editSearchExcluded: EditText
    private lateinit var btnResetExcluded: ImageButton

    private lateinit var excludedAdapter: ExcludedAppsAdapter
    private var allExcludedItems: List<ExcludedAppItem> = emptyList()

    private val themeOptions = listOf(
        ThemePref.FOLLOW_SYSTEM,
        ThemePref.LIGHT,
        ThemePref.DARK
    )

    private val themeLabels = listOf(
        "Follow system",
        "Light",
        "Dark"
    )

    private val scrollSensOptions = listOf(
        ScrollSensitivity.SLOW,
        ScrollSensitivity.MEDIUM,
        ScrollSensitivity.FAST
    )

    private val scrollSensLabels = listOf(
        "Slow",
        "Medium",
        "Fast"
    )

    private val holdModeOptions = listOf(
        HoldMode.DISABLED,
        HoldMode.MOUSE,
        HoldMode.KEYBOARD,
        HoldMode.SCROLL_WHEEL
    )

    private val holdModeLabels = listOf(
        "Disabled",
        "Mouse",
        "Keyboard",
        "Scroll wheel"
    )

    companion object {
        private const val REQUEST_BACKUP_CREATE = 1001
        private const val REQUEST_RESTORE_OPEN = 1002
    }

    private fun getAppVersionName(): String {
        return try {
            val pm = packageManager
            val pkg = packageName
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            info.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyThemeFromPrefs(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)

        // Header
        tvSettingsVersion = findViewById(R.id.tvSettingsVersion)
        tvSettingsVersion.text = "v${getAppVersionName()}"

        // Toast + theme
        checkToastQuick = findViewById(R.id.checkToastQuick)
        checkToastPerApp = findViewById(R.id.checkToastPerApp)
        checkToastDefault = findViewById(R.id.checkToastDefault)
        checkToastHoldKey = findViewById(R.id.checkToastHoldKey)
        spinnerTheme = findViewById(R.id.spinnerTheme)

        // Auto keyboard for text input
        checkAutoKeyboardText = findViewById(R.id.checkAutoKeyboardText)

        // Hold-key global settings
        spinnerGlobalHoldMode = findViewById(R.id.spinnerGlobalHoldMode)
        tvGlobalHoldKeyCode = findViewById(R.id.tvGlobalHoldKeyCode)
        btnSetGlobalHoldKey = findViewById(R.id.btnSetGlobalHoldKey)
        checkHoldAllowedInTextGlobal = findViewById(R.id.checkHoldAllowedInTextGlobal)

        // Scroll
        spinnerScrollSensitivity = findViewById(R.id.spinnerScrollSensitivity)
        checkScrollHorizontal = findViewById(R.id.checkScrollHorizontal)
        checkScrollInvertVertical = findViewById(R.id.checkScrollInvertVertical)
        checkScrollInvertHorizontal = findViewById(R.id.checkScrollInvertHorizontal)

        // Backup/restore
        btnBackupSettings = findViewById(R.id.btnBackupSettings)
        btnRestoreSettings = findViewById(R.id.btnRestoreSettings)

        // Exclusions
        btnAddExcludedApps = findViewById(R.id.btnAddExcludedApps)
        recyclerExcludedApps = findViewById(R.id.recyclerExcludedApps)
        editSearchExcluded = findViewById(R.id.editSearchExcluded)
        btnResetExcluded = findViewById(R.id.btnResetExcluded)

        setupToasts()
        setupThemeSpinner()
        setupBackupRestore()
        setupAutoKeyboardForText()
        setupHoldKeySettings()
        setupGlobalScrollSettings()
        setupExcludedAppsSection()
    }

    // ---------- Toast & Theme ----------

    private fun setupToasts() {
        checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()
        checkToastPerApp.isChecked = prefs.isToastPerAppEnabled()
        checkToastDefault.isChecked = prefs.isToastDefaultModeEnabled()
        checkToastHoldKey.isChecked = prefs.isToastHoldKeyEnabled()

        checkToastQuick.setOnCheckedChangeListener { _, isChecked ->
            prefs.setToastQuickToggleEnabled(isChecked)
        }
        checkToastPerApp.setOnCheckedChangeListener { _, isChecked ->
            prefs.setToastPerAppEnabled(isChecked)
        }
        checkToastDefault.setOnCheckedChangeListener { _, isChecked ->
            prefs.setToastDefaultModeEnabled(isChecked)
        }
        checkToastHoldKey.setOnCheckedChangeListener { _, isChecked ->
            prefs.setToastHoldKeyEnabled(isChecked)
        }
    }

    private fun setupThemeSpinner() {
        val adapter = ArrayAdapter(
            this,
            AndroidR.layout.simple_spinner_item,
            themeLabels
        )
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = adapter

        val currentTheme = prefs.getThemePref()
        val index = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
        spinnerTheme.setSelection(index, false)

        spinnerTheme.setOnItemSelectedListenerSimple { position ->
            val selectedTheme = themeOptions[position]
            if (selectedTheme != prefs.getThemePref()) {
                prefs.setThemePref(selectedTheme)
                Prefs.applyThemeFromPrefs(this)
                recreate()
            }
        }
    }

    // ---------- Auto keyboard for text input ----------

    private fun setupAutoKeyboardForText() {
        checkAutoKeyboardText.isChecked = prefs.isGlobalAutoKeyboardForTextEnabled()
        checkAutoKeyboardText.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalAutoKeyboardForTextEnabled(isChecked)
        }
    }

    // ---------- Hold-key global settings ----------

    private fun setupHoldKeySettings() {
        val holdModeAdapter = ArrayAdapter(
            this,
            AndroidR.layout.simple_spinner_item,
            holdModeLabels
        )
        holdModeAdapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerGlobalHoldMode.adapter = holdModeAdapter

        val currentMode = prefs.getGlobalHoldMode()
        val modeIndex = holdModeOptions.indexOf(currentMode).coerceAtLeast(0)
        spinnerGlobalHoldMode.setSelection(modeIndex, false)

        spinnerGlobalHoldMode.setOnItemSelectedListenerSimple { position ->
            val selected = holdModeOptions[position]
            if (selected != prefs.getGlobalHoldMode()) {
                prefs.setGlobalHoldMode(selected)
            }
        }

        refreshHoldKeyCodeLabel(prefs.getGlobalHoldKeyCode())

        btnSetGlobalHoldKey.setOnClickListener {
            showKeyCaptureDialog(
                title = "Set hold modifier key",
                initialKeyCode = prefs.getGlobalHoldKeyCode(),
                onKeySelected = { keyCode ->
                    prefs.setGlobalHoldKeyCode(keyCode)
                    refreshHoldKeyCodeLabel(keyCode)
                },
                onDefault = {
                    val def = Prefs.DEFAULT_HOLD_KEYCODE
                    prefs.setGlobalHoldKeyCode(def)
                    refreshHoldKeyCodeLabel(def)
                }
            )
        }

        checkHoldAllowedInTextGlobal.isChecked = prefs.isGlobalHoldAllowedInTextFields()
        checkHoldAllowedInTextGlobal.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalHoldAllowedInTextFields(isChecked)
        }
    }

    private fun refreshHoldKeyCodeLabel(keyCode: Int) {
        tvGlobalHoldKeyCode.text = "${keyCode} (${KeyEvent.keyCodeToString(keyCode)})"
    }

    private fun showKeyCaptureDialog(
        title: String,
        initialKeyCode: Int,
        onKeySelected: (Int) -> Unit,
        onDefault: () -> Unit
    ) {
        val message = TextView(this).apply {
            text = "Press any key to set as the hold modifier.\n\nCurrent: $initialKeyCode (${KeyEvent.keyCodeToString(initialKeyCode)})"
            setPadding(48, 32, 48, 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(message)
            .setPositiveButton("Cancel") { d, _ -> d.dismiss() }
            .setNeutralButton("Default") { d, _ ->
                d.dismiss()
                onDefault()
            }
            .create()

        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                d.dismiss()
                onKeySelected(keyCode)
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    // ---------- Global Scroll Settings ----------

    private fun setupGlobalScrollSettings() {
        val adapter = ArrayAdapter(
            this,
            AndroidR.layout.simple_spinner_item,
            scrollSensLabels
        )
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerScrollSensitivity.adapter = adapter

        val current = prefs.getGlobalScrollSettings()
        val index = scrollSensOptions.indexOf(current.sensitivity).coerceAtLeast(0)
        spinnerScrollSensitivity.setSelection(index, false)

        spinnerScrollSensitivity.setOnItemSelectedListenerSimple { position ->
            val sens = scrollSensOptions[position]
            prefs.setGlobalScrollSensitivity(sens)
        }

        checkScrollHorizontal.isChecked = current.horizontalEnabled
        checkScrollInvertVertical.isChecked = current.invertVertical
        checkScrollInvertHorizontal.isChecked = current.invertHorizontal

        checkScrollHorizontal.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalScrollHorizontalEnabled(isChecked)
        }
        checkScrollInvertVertical.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalScrollInvertVertical(isChecked)
        }
        checkScrollInvertHorizontal.setOnCheckedChangeListener { _, isChecked ->
            prefs.setGlobalScrollInvertHorizontal(isChecked)
        }
    }

    // ---------- Backup & Restore ----------

    private fun setupBackupRestore() {
        btnBackupSettings.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "q25_settings_backup_${System.currentTimeMillis()}.json")
            }
            startActivityForResult(intent, REQUEST_BACKUP_CREATE)
        }

        btnRestoreSettings.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, REQUEST_RESTORE_OPEN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != Activity.RESULT_OK || data?.data == null) return

        val uri = data.data!!

        when (requestCode) {
            REQUEST_BACKUP_CREATE -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        val json = SettingsBackup.exportToJson(this)
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "Settings backup saved.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Failed to save backup.", Toast.LENGTH_LONG).show()
                }
            }

            REQUEST_RESTORE_OPEN -> {
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
                    if (json.isNullOrBlank()) {
                        Toast.makeText(this, "Invalid or empty backup file.", Toast.LENGTH_LONG).show()
                        return
                    }

                    val success = SettingsBackup.restoreFromJson(this, json)
                    if (success) {
                        Toast.makeText(this, "Backup restored. Restarting settings...", Toast.LENGTH_SHORT).show()
                        Prefs.applyThemeFromPrefs(this)
                        recreate()
                    } else {
                        Toast.makeText(this, "Invalid backup file.", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Failed to restore backup.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Excluded Apps Section ----------

    private fun setupExcludedAppsSection() {
        recyclerExcludedApps.layoutManager = LinearLayoutManager(this)
        excludedAdapter = ExcludedAppsAdapter(mutableListOf()) { item ->
            if (Prefs.DEFAULT_EXCLUDED_PACKAGES.contains(item.packageName)) {
                Toast.makeText(
                    this,
                    "Warning: removing some keyboard apps from exclusions may cause frequent mode switching.",
                    Toast.LENGTH_LONG
                ).show()
            }
            prefs.removeExcludedPackage(item.packageName)
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
        }
        recyclerExcludedApps.adapter = excludedAdapter

        btnAddExcludedApps.setOnClickListener { showExcludeAppPickerDialog() }

        btnResetExcluded.setOnClickListener {
            prefs.resetExcludedPackagesToDefault()
            Toast.makeText(this, "Exclusion list reset to defaults.", Toast.LENGTH_SHORT).show()
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
        }

        editSearchExcluded.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshExcludedList(s?.toString().orEmpty())
            }
        })

        refreshExcludedList("")
    }

    private fun refreshExcludedList(query: String) {
        val pkgs = prefs.getExcludedPackages()
        val pm = packageManager

        val items = pkgs.map { pkg -> buildExcludedAppItem(pm, pkg) }
        allExcludedItems = items

        val filtered = if (query.isBlank()) {
            allExcludedItems
        } else {
            val lower = query.lowercase()
            allExcludedItems.filter {
                it.label.lowercase().contains(lower) ||
                        it.packageName.lowercase().contains(lower)
            }
        }

        excludedAdapter.updateItems(filtered)
    }

    private fun buildExcludedAppItem(pm: PackageManager, packageName: String): ExcludedAppItem {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            ExcludedAppItem(packageName, label, icon)
        } catch (_: Exception) {
            ExcludedAppItem(packageName, packageName, null)
        }
    }

    private fun showExcludeAppPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_exclude_app_picker, null)
        val editSearch = dialogView.findViewById<EditText>(R.id.editSearchExcludePicker)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerExcludePickerApps)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select apps to exclude")
            .setView(dialogView)
            .setNegativeButton("Close") { d, _ -> d.dismiss() }
            .create()

        val allCandidates = loadCandidateAppsForExclusion()
        var pickerAdapter: ExclusionPickerAdapter? = null

        pickerAdapter = ExclusionPickerAdapter(
            this,
            allCandidates.toMutableList()
        ) { app ->
            prefs.addExcludedPackage(app.packageName)
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
            pickerAdapter?.removeApp(app.packageName)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = pickerAdapter

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pickerAdapter?.filter(s?.toString().orEmpty())
            }
        })

        dialog.show()
    }

    private fun loadCandidateAppsForExclusion(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val excluded = prefs.getExcludedPackages()
        val myPackage = packageName

        val map = LinkedHashMap<String, AppItem>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (map.containsKey(pkg)) continue
            if (pkg == myPackage) continue
            if (excluded.contains(pkg)) continue

            val label = ri.loadLabel(pm).toString()
            val icon = ri.loadIcon(pm)
            map[pkg] = AppItem(pkg, label, icon)
        }

        return map.values.sortedBy { it.label.lowercase() }
    }
}