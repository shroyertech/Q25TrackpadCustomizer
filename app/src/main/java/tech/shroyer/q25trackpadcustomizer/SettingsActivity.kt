package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
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

    // Updates UI
    private lateinit var btnCheckUpdates: Button

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

    // Prevent UI refresh from writing prefs.
    private var suppressUiListeners = false

    private val backupCreateLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) handleBackupCreate(uri)
        }

    private val restoreOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleRestoreOpen(uri)
        }

    companion object {
        private const val TAG_VIEW_ON_GITHUB_LINK = "view_on_github_link"
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

        // Updates
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        setupUpdateChecker()
        setupViewOnGitHubLink()

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

    override fun onResume() {
        super.onResume()
        refreshUiFromPrefs()
    }

    private fun refreshUiFromPrefs() {
        if (!::prefs.isInitialized) return

        suppressUiListeners = true
        try {
            // Toasts
            if (::checkToastQuick.isInitialized) checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()
            if (::checkToastPerApp.isInitialized) checkToastPerApp.isChecked = prefs.isToastPerAppEnabled()
            if (::checkToastDefault.isInitialized) checkToastDefault.isChecked = prefs.isToastDefaultModeEnabled()
            if (::checkToastHoldKey.isInitialized) checkToastHoldKey.isChecked = prefs.isToastHoldKeyEnabled()

            // Theme spinner
            if (::spinnerTheme.isInitialized) {
                val currentTheme = prefs.getThemePref()
                val idx = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
                if (spinnerTheme.selectedItemPosition != idx) {
                    spinnerTheme.setSelection(idx, false)
                }
            }

            // Auto keyboard
            if (::checkAutoKeyboardText.isInitialized) {
                checkAutoKeyboardText.isChecked = prefs.isGlobalAutoKeyboardForTextEnabled()
            }

            // Hold-key globals
            if (::spinnerGlobalHoldMode.isInitialized) {
                val currentMode = prefs.getGlobalHoldMode()
                val idx = holdModeOptions.indexOf(currentMode).coerceAtLeast(0)
                if (spinnerGlobalHoldMode.selectedItemPosition != idx) {
                    spinnerGlobalHoldMode.setSelection(idx, false)
                }
            }
            if (::tvGlobalHoldKeyCode.isInitialized) {
                refreshHoldKeyCodeLabel(prefs.getGlobalHoldKeyCode())
            }
            if (::checkHoldAllowedInTextGlobal.isInitialized) {
                checkHoldAllowedInTextGlobal.isChecked = prefs.isGlobalHoldAllowedInTextFields()
            }

            // Scroll globals
            if (::spinnerScrollSensitivity.isInitialized) {
                val current = prefs.getGlobalScrollSettings()
                val idx = scrollSensOptions.indexOf(current.sensitivity).coerceAtLeast(0)
                if (spinnerScrollSensitivity.selectedItemPosition != idx) {
                    spinnerScrollSensitivity.setSelection(idx, false)
                }
                if (::checkScrollHorizontal.isInitialized) checkScrollHorizontal.isChecked = current.horizontalEnabled
                if (::checkScrollInvertVertical.isInitialized) checkScrollInvertVertical.isChecked = current.invertVertical
                if (::checkScrollInvertHorizontal.isInitialized) checkScrollInvertHorizontal.isChecked = current.invertHorizontal
            }

            // Exclusions list
            if (::editSearchExcluded.isInitialized && ::excludedAdapter.isInitialized) {
                refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
            }
        } finally {
            suppressUiListeners = false
        }
    }

    // ---------- Toast & Theme ----------

    private fun setupToasts() {
        checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()
        checkToastPerApp.isChecked = prefs.isToastPerAppEnabled()
        checkToastDefault.isChecked = prefs.isToastDefaultModeEnabled()
        checkToastHoldKey.isChecked = prefs.isToastHoldKeyEnabled()

        checkToastQuick.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastQuickToggleEnabled(isChecked)
        }
        checkToastPerApp.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastPerAppEnabled(isChecked)
        }
        checkToastDefault.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastDefaultModeEnabled(isChecked)
        }
        checkToastHoldKey.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
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
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
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
            if (suppressUiListeners) return@setOnCheckedChangeListener
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
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val selected = holdModeOptions[position]
            if (selected != prefs.getGlobalHoldMode()) {
                prefs.setGlobalHoldMode(selected)
            }
        }

        refreshHoldKeyCodeLabel(prefs.getGlobalHoldKeyCode())

        btnSetGlobalHoldKey.setOnClickListener {
            showKeyCaptureDialog(
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
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalHoldAllowedInTextFields(isChecked)
        }
    }

    private fun refreshHoldKeyCodeLabel(keyCode: Int) {
        tvGlobalHoldKeyCode.text = "$keyCode (${KeyEvent.keyCodeToString(keyCode)})"
    }

    private fun showKeyCaptureDialog(
        initialKeyCode: Int,
        onKeySelected: (Int) -> Unit,
        onDefault: () -> Unit
    ) {
        val message = TextView(this).apply {
            text = "Press any key to set as the hold modifier.\n\nCurrent: $initialKeyCode (${KeyEvent.keyCodeToString(initialKeyCode)})"
            setPadding(48, 32, 48, 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set hold modifier key")
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
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val sens = scrollSensOptions[position]
            prefs.setGlobalScrollSensitivity(sens)
        }

        checkScrollHorizontal.isChecked = current.horizontalEnabled
        checkScrollInvertVertical.isChecked = current.invertVertical
        checkScrollInvertHorizontal.isChecked = current.invertHorizontal

        checkScrollHorizontal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalScrollHorizontalEnabled(isChecked)
        }
        checkScrollInvertVertical.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalScrollInvertVertical(isChecked)
        }
        checkScrollInvertHorizontal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalScrollInvertHorizontal(isChecked)
        }
    }

    // ---------- Backup & Restore ----------

    private fun setupBackupRestore() {
        btnBackupSettings.setOnClickListener {
            val name = "q25_settings_backup_${System.currentTimeMillis()}.json"
            backupCreateLauncher.launch(name)
        }

        btnRestoreSettings.setOnClickListener {
            restoreOpenLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun handleBackupCreate(uri: Uri) {
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

    private fun handleRestoreOpen(uri: Uri) {
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

    // ---------- Updates (manual GitHub release check) ----------

    private fun setupUpdateChecker() {
        btnCheckUpdates.setOnClickListener {
            checkForUpdatesManual()
        }
    }

    private fun setupViewOnGitHubLink() {
        val parent = btnCheckUpdates.parent as? ViewGroup ?: return
        val existing = parent.findViewWithTag<View>(TAG_VIEW_ON_GITHUB_LINK)
        if (existing != null) return

        val link = TextView(this).apply {
            tag = TAG_VIEW_ON_GITHUB_LINK
            text = "View on GitHub"
            textSize = 12f
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(6), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openGitHubReleasePage()
            }
        }

        // Try to insert immediately after the "Check for updates" button.
        val idx = parent.indexOfChild(btnCheckUpdates)
        if (idx >= 0 && idx < parent.childCount - 1) {
            parent.addView(link, idx + 1)
        } else {
            parent.addView(link)
        }
    }

    private fun openGitHubReleasePage() {
        // Open the stable web URL
        openInBrowser(UpdateChecker.LATEST_RELEASE_WEB_URL)
    }

    private fun checkForUpdatesManual() {
        val installedRaw = getAppVersionName().ifBlank { "0.0.0" }
        val installedDisplay = UpdateChecker.normalizeForDisplay(installedRaw)

        val progress = showUpdateCheckProgressDialog()

        Thread {
            val result = UpdateChecker.fetchLatestRelease()

            Handler(Looper.getMainLooper()).post {
                try {
                    progress.dismiss()
                } catch (_: Exception) {
                }

                when (result) {
                    is UpdateChecker.Result.Error -> {
                        AlertDialog.Builder(this)
                            .setTitle("Update check failed")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    is UpdateChecker.Result.Success -> {
                        val latestTag = result.release.tagName
                        val latestDisplay = UpdateChecker.normalizeForDisplay(latestTag)
                        val cmp = UpdateChecker.compareVersions(installedRaw, latestTag)

                        when {
                            cmp > 0 -> {
                                AlertDialog.Builder(this)
                                    .setTitle("You’re ahead of GitHub!")
                                    .setMessage(
                                        "Installed version is newer than latest version. What's it like in the future?\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay"
                                    )
                                    .setPositiveButton("OK", null)
                                    .setNeutralButton("View on GitHub") { _, _ ->
                                        openInBrowser(result.release.htmlUrl)
                                    }
                                    .show()
                            }

                            cmp == 0 -> {
                                AlertDialog.Builder(this)
                                    .setTitle("No update available")
                                    .setMessage(
                                        "Already on the latest update.\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay"
                                    )
                                    .setPositiveButton("OK", null)
                                    .setNeutralButton("View on GitHub") { _, _ ->
                                        openInBrowser(result.release.htmlUrl)
                                    }
                                    .show()
                            }

                            else -> {
                                val openUrl = result.release.apkUrl ?: result.release.htmlUrl
                                val openLabel = if (result.release.apkUrl != null) {
                                    "Open APK download"
                                } else {
                                    "Open release page"
                                }

                                AlertDialog.Builder(this)
                                    .setTitle("Update available!")
                                    .setMessage(
                                        "An update is available! How exciting.\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay\n\n" +
                                                "After updating, you will need to re-enable the Accessibility Service!"
                                    )
                                    .setPositiveButton(openLabel) { _, _ ->
                                        openInBrowser(openUrl)
                                    }
                                    .setNeutralButton("View on GitHub") { _, _ ->
                                        openInBrowser(result.release.htmlUrl)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open the link.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to open link.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdateCheckProgressDialog(): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = ProgressBar(this).apply {
            isIndeterminate = true
        }

        val tv = TextView(this).apply {
            text = "Comparing installed vs latest release on GitHub."
            setPadding(32, 0, 0, 0)
        }

        container.addView(bar)
        container.addView(tv)

        return AlertDialog.Builder(this)
            .setTitle("Checking for updates…")
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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

        lateinit var pickerAdapter: ExclusionPickerAdapter
        pickerAdapter = ExclusionPickerAdapter(
            this,
            allCandidates.toMutableList()
        ) { app ->
            prefs.addExcludedPackage(app.packageName)
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
            pickerAdapter.removeApp(app.packageName)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = pickerAdapter

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pickerAdapter.filter(s?.toString().orEmpty())
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