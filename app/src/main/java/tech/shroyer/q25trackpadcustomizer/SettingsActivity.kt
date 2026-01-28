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

    // Primary Hold global
    private lateinit var spinnerGlobalHoldMode: Spinner
    private lateinit var tvGlobalHoldKeyCode: TextView
    private lateinit var btnSetGlobalHoldKey: Button
    private lateinit var checkHoldAllowedInTextGlobal: CheckBox
    private lateinit var checkHoldDoublePressGlobal: CheckBox

    // Secondary Hold global
    private lateinit var spinnerGlobalHold2Mode: Spinner
    private lateinit var checkHold2UseHold1DoubleGlobal: CheckBox
    private lateinit var tvGlobalHold2KeyCode: TextView
    private lateinit var btnSetGlobalHold2Key: Button
    private lateinit var checkHold2AllowedInTextGlobal: CheckBox
    private lateinit var checkHold2DoublePressGlobal: CheckBox

    // Global scroll
    private lateinit var spinnerScrollSensitivity: Spinner
    private lateinit var checkScrollHorizontal: CheckBox
    private lateinit var checkScrollInvertVertical: CheckBox
    private lateinit var checkScrollInvertHorizontal: CheckBox

    // Backup/restore
    private lateinit var btnBackupSettings: Button
    private lateinit var btnRestoreSettings: Button

    // Updates
    private lateinit var btnCheckUpdates: Button

    // Exclusions
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

        // Auto keyboard
        checkAutoKeyboardText = findViewById(R.id.checkAutoKeyboardText)

        // Primary Hold
        spinnerGlobalHoldMode = findViewById(R.id.spinnerGlobalHoldMode)
        tvGlobalHoldKeyCode = findViewById(R.id.tvGlobalHoldKeyCode)
        btnSetGlobalHoldKey = findViewById(R.id.btnSetGlobalHoldKey)
        checkHoldAllowedInTextGlobal = findViewById(R.id.checkHoldAllowedInTextGlobal)
        checkHoldDoublePressGlobal = findViewById(R.id.checkHoldDoublePressGlobal)

        // Secondary Hold
        spinnerGlobalHold2Mode = findViewById(R.id.spinnerGlobalHold2Mode)
        checkHold2UseHold1DoubleGlobal = findViewById(R.id.checkHold2UseHold1DoubleGlobal)
        tvGlobalHold2KeyCode = findViewById(R.id.tvGlobalHold2KeyCode)
        btnSetGlobalHold2Key = findViewById(R.id.btnSetGlobalHold2Key)
        checkHold2AllowedInTextGlobal = findViewById(R.id.checkHold2AllowedInTextGlobal)
        checkHold2DoublePressGlobal = findViewById(R.id.checkHold2DoublePressGlobal)

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
            checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()
            checkToastPerApp.isChecked = prefs.isToastPerAppEnabled()
            checkToastDefault.isChecked = prefs.isToastDefaultModeEnabled()
            checkToastHoldKey.isChecked = prefs.isToastHoldKeyEnabled()

            // Theme
            val currentTheme = prefs.getThemePref()
            val themeIdx = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
            if (spinnerTheme.selectedItemPosition != themeIdx) {
                spinnerTheme.setSelection(themeIdx, false)
            }

            // Auto keyboard
            checkAutoKeyboardText.isChecked = prefs.isGlobalAutoKeyboardForTextEnabled()

            // Primary Hold
            val hold1Mode = prefs.getGlobalHoldMode()
            val hold1Idx = holdModeOptions.indexOf(hold1Mode).coerceAtLeast(0)
            if (spinnerGlobalHoldMode.selectedItemPosition != hold1Idx) {
                spinnerGlobalHoldMode.setSelection(hold1Idx, false)
            }
            refreshKeyLabel(tvGlobalHoldKeyCode, prefs.getGlobalHoldKeyCode())
            checkHoldAllowedInTextGlobal.isChecked = prefs.isGlobalHoldAllowedInTextFields()
            checkHoldDoublePressGlobal.isChecked = prefs.isGlobalHoldDoublePressRequired()

            // Secondary Hold
            val hold2Mode = prefs.getGlobalHold2Mode()
            val hold2Idx = holdModeOptions.indexOf(hold2Mode).coerceAtLeast(0)
            if (spinnerGlobalHold2Mode.selectedItemPosition != hold2Idx) {
                spinnerGlobalHold2Mode.setSelection(hold2Idx, false)
            }

            val tied = prefs.isGlobalHold2UseHold1DoublePressHold()
            checkHold2UseHold1DoubleGlobal.isChecked = tied
            checkHold2AllowedInTextGlobal.isChecked = prefs.isGlobalHold2AllowedInTextFields()
            checkHold2DoublePressGlobal.isChecked = prefs.isGlobalHold2DoublePressRequired()

            updateHold2UiEnabledState()

            // Scroll
            val scroll = prefs.getGlobalScrollSettings()
            val scrollIdx = scrollSensOptions.indexOf(scroll.sensitivity).coerceAtLeast(0)
            if (spinnerScrollSensitivity.selectedItemPosition != scrollIdx) {
                spinnerScrollSensitivity.setSelection(scrollIdx, false)
            }
            checkScrollHorizontal.isChecked = scroll.horizontalEnabled
            checkScrollInvertVertical.isChecked = scroll.invertVertical
            checkScrollInvertHorizontal.isChecked = scroll.invertHorizontal

            // Exclusions
            if (::excludedAdapter.isInitialized) {
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
        val adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, themeLabels)
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

    // ---------- Auto keyboard ----------

    private fun setupAutoKeyboardForText() {
        checkAutoKeyboardText.isChecked = prefs.isGlobalAutoKeyboardForTextEnabled()
        checkAutoKeyboardText.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalAutoKeyboardForTextEnabled(isChecked)
        }
    }

    // ---------- Hold keys ----------

    private fun setupHoldKeySettings() {
        val holdModeAdapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, holdModeLabels)
        holdModeAdapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)

        // Primary Hold mode
        spinnerGlobalHoldMode.adapter = holdModeAdapter
        spinnerGlobalHoldMode.setSelection(holdModeOptions.indexOf(prefs.getGlobalHoldMode()).coerceAtLeast(0), false)
        spinnerGlobalHoldMode.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val selected = holdModeOptions[position]
            if (selected != prefs.getGlobalHoldMode()) {
                prefs.setGlobalHoldMode(selected)
            }
        }

        // Primary Hold key
        refreshKeyLabel(tvGlobalHoldKeyCode, prefs.getGlobalHoldKeyCode())
        btnSetGlobalHoldKey.setOnClickListener {
            showKeyCaptureDialog(
                title = "Set Primary Hold key",
                initialKeyCode = prefs.getGlobalHoldKeyCode(),
                onKeySelected = { keyCode ->
                    prefs.setGlobalHoldKeyCode(keyCode)
                    refreshKeyLabel(tvGlobalHoldKeyCode, keyCode)
                },
                onDefault = {
                    val def = Prefs.DEFAULT_HOLD_KEYCODE
                    prefs.setGlobalHoldKeyCode(def)
                    refreshKeyLabel(tvGlobalHoldKeyCode, def)
                }
            )
        }

        // Primary Hold allow in text
        checkHoldAllowedInTextGlobal.isChecked = prefs.isGlobalHoldAllowedInTextFields()
        checkHoldAllowedInTextGlobal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalHoldAllowedInTextFields(isChecked)
        }

        // Primary Hold double press
        checkHoldDoublePressGlobal.isChecked = prefs.isGlobalHoldDoublePressRequired()
        checkHoldDoublePressGlobal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener

            if (isChecked && prefs.isGlobalHold2UseHold1DoublePressHold()) {
                showConfirmDialog(
                    title = "Conflict",
                    message = "Secondary Hold is currently triggered by Primary Hold double press + hold.\n\n" +
                            "Enabling Primary Hold double-press requirement will disable that Secondary Hold option. Continue?",
                    positive = "Disable Secondary Hold tie",
                    negative = "Cancel",
                    onYes = {
                        prefs.setGlobalHold2UseHold1DoublePressHold(false)
                        prefs.setGlobalHoldDoublePressRequired(true)
                        refreshUiFromPrefs()
                    },
                    onNo = {
                        suppressUiListeners = true
                        try {
                            checkHoldDoublePressGlobal.isChecked = false
                        } finally {
                            suppressUiListeners = false
                        }
                    }
                )
            } else {
                prefs.setGlobalHoldDoublePressRequired(isChecked)
            }
        }

        // Secondary Hold mode
        spinnerGlobalHold2Mode.adapter = holdModeAdapter
        spinnerGlobalHold2Mode.setSelection(holdModeOptions.indexOf(prefs.getGlobalHold2Mode()).coerceAtLeast(0), false)
        spinnerGlobalHold2Mode.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val selected = holdModeOptions[position]
            if (selected != prefs.getGlobalHold2Mode()) {
                prefs.setGlobalHold2Mode(selected)
                updateHold2UiEnabledState()
            }
        }

        // Secondary Hold tied to Primary Hold double gesture
        checkHold2UseHold1DoubleGlobal.isChecked = prefs.isGlobalHold2UseHold1DoublePressHold()
        checkHold2UseHold1DoubleGlobal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener

            if (isChecked) {
                if (prefs.isGlobalHoldDoublePressRequired()) {
                    showConfirmDialog(
                        title = "Conflict",
                        message = "This will disable “Primary Hold: require double press + hold” to avoid conflicts.\n\nContinue?",
                        positive = "Continue",
                        negative = "Cancel",
                        onYes = {
                            prefs.setGlobalHoldDoublePressRequired(false)
                            prefs.setGlobalHold2UseHold1DoublePressHold(true)
                            prefs.setGlobalHold2DoublePressRequired(false)
                            refreshUiFromPrefs()
                        },
                        onNo = {
                            suppressUiListeners = true
                            try {
                                checkHold2UseHold1DoubleGlobal.isChecked = false
                            } finally {
                                suppressUiListeners = false
                            }
                        }
                    )
                } else {
                    prefs.setGlobalHold2UseHold1DoublePressHold(true)
                    prefs.setGlobalHold2DoublePressRequired(false)
                    refreshUiFromPrefs()
                }
            } else {
                prefs.setGlobalHold2UseHold1DoublePressHold(false)
                refreshUiFromPrefs()
            }
        }

        // Secondary Hold key
        btnSetGlobalHold2Key.setOnClickListener {
            if (prefs.isGlobalHold2UseHold1DoublePressHold()) {
                Toast.makeText(this, "Secondary Hold is tied to Primary Hold double press + hold.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showKeyCaptureDialog(
                title = "Set Secondary Hold key",
                initialKeyCode = prefs.getGlobalHold2KeyCode(),
                onKeySelected = { keyCode ->
                    prefs.setGlobalHold2KeyCode(keyCode)
                    refreshKeyLabel(tvGlobalHold2KeyCode, keyCode)
                },
                onDefault = {
                    val def = Prefs.DEFAULT_HOLD2_KEYCODE
                    prefs.setGlobalHold2KeyCode(def)
                    refreshKeyLabel(tvGlobalHold2KeyCode, def)
                }
            )
        }

        // Secondary Hold allow in text
        checkHold2AllowedInTextGlobal.isChecked = prefs.isGlobalHold2AllowedInTextFields()
        checkHold2AllowedInTextGlobal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalHold2AllowedInTextFields(isChecked)
        }

        // Secondary Hold double press
        checkHold2DoublePressGlobal.isChecked = prefs.isGlobalHold2DoublePressRequired()
        checkHold2DoublePressGlobal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            if (prefs.isGlobalHold2UseHold1DoublePressHold()) {
                suppressUiListeners = true
                try {
                    checkHold2DoublePressGlobal.isChecked = false
                } finally {
                    suppressUiListeners = false
                }
                Toast.makeText(this, "Not applicable while Secondary Hold is tied to Primary Hold double press.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.setGlobalHold2DoublePressRequired(isChecked)
        }

        updateHold2UiEnabledState()
    }

    private fun updateHold2UiEnabledState() {
        val hold2Enabled = prefs.getGlobalHold2Mode() != HoldMode.DISABLED
        val tied = prefs.isGlobalHold2UseHold1DoublePressHold()

        btnSetGlobalHold2Key.isEnabled = hold2Enabled && !tied
        checkHold2AllowedInTextGlobal.isEnabled = hold2Enabled

        checkHold2DoublePressGlobal.isEnabled = hold2Enabled && !tied
        if (tied) {
            suppressUiListeners = true
            try {
                checkHold2DoublePressGlobal.isChecked = false
            } finally {
                suppressUiListeners = false
            }
        }

        tvGlobalHold2KeyCode.alpha = if (hold2Enabled) 1.0f else 0.5f
        btnSetGlobalHold2Key.alpha = if (btnSetGlobalHold2Key.isEnabled) 1.0f else 0.5f
        checkHold2AllowedInTextGlobal.alpha = if (hold2Enabled) 1.0f else 0.5f
        checkHold2DoublePressGlobal.alpha = if (checkHold2DoublePressGlobal.isEnabled) 1.0f else 0.5f

        tvGlobalHold2KeyCode.text = when {
            !hold2Enabled -> "Disabled"
            tied -> "Tied to Primary Hold double press + hold"
            else -> formatKeyLabel(prefs.getGlobalHold2KeyCode())
        }
    }

    private fun refreshKeyLabel(target: TextView, keyCode: Int) {
        target.text = formatKeyLabel(keyCode)
    }

    private fun formatKeyLabel(keyCode: Int): String {
        val raw = KeyEvent.keyCodeToString(keyCode)
        val nice = raw.removePrefix("KEYCODE_").replace('_', ' ')
        return "$nice ($keyCode)"
    }

    private fun showKeyCaptureDialog(
        title: String,
        initialKeyCode: Int,
        onKeySelected: (Int) -> Unit,
        onDefault: () -> Unit
    ) {
        val message = TextView(this).apply {
            text = "Press any key to set the modifier.\n\nCurrent: ${formatKeyLabel(initialKeyCode)}"
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

    private fun showConfirmDialog(
        title: String,
        message: String,
        positive: String,
        negative: String,
        onYes: () -> Unit,
        onNo: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .setNegativeButton(negative) { _, _ -> onNo() }
            .setCancelable(false)
            .show()
    }

    // ---------- Global Scroll ----------

    private fun setupGlobalScrollSettings() {
        val adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, scrollSensLabels)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerScrollSensitivity.adapter = adapter

        val current = prefs.getGlobalScrollSettings()
        spinnerScrollSensitivity.setSelection(scrollSensOptions.indexOf(current.sensitivity).coerceAtLeast(0), false)

        spinnerScrollSensitivity.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            prefs.setGlobalScrollSensitivity(scrollSensOptions[position])
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

    // ---------- Updates ----------

    private fun setupUpdateChecker() {
        btnCheckUpdates.setOnClickListener { checkForUpdatesManual() }
    }

    private fun setupViewOnGitHubLink() {
        val parent = btnCheckUpdates.parent as? ViewGroup ?: return
        val existing = parent.findViewWithTag<ViewGroup>(TAG_VIEW_ON_GITHUB_LINK)
        if (existing != null) return

        val link = TextView(this).apply {
            tag = TAG_VIEW_ON_GITHUB_LINK
            text = "View on GitHub"
            textSize = 12f
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(6), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener { openGitHubReleasePage() }
        }

        val idx = parent.indexOfChild(btnCheckUpdates)
        if (idx >= 0 && idx < parent.childCount - 1) {
            parent.addView(link, idx + 1)
        } else {
            parent.addView(link)
        }
    }

    private fun openGitHubReleasePage() {
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
                                        "Installed version is newer than latest version.\n\n" +
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
                                val openLabel = if (result.release.apkUrl != null) "Open APK download" else "Open release page"

                                AlertDialog.Builder(this)
                                    .setTitle("Update available!")
                                    .setMessage(
                                        "An update is available.\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay\n\n" +
                                                "After updating, you will need to re-enable the Accessibility Service!"
                                    )
                                    .setPositiveButton(openLabel) { _, _ -> openInBrowser(openUrl) }
                                    .setNeutralButton("View on GitHub") { _, _ -> openInBrowser(result.release.htmlUrl) }
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

        val bar = ProgressBar(this).apply { isIndeterminate = true }

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

    // ---------- Exclusions ----------

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

        allExcludedItems = pkgs.map { pkg -> buildExcludedAppItem(pm, pkg) }

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
        pickerAdapter = ExclusionPickerAdapter(this, allCandidates.toMutableList()) { app ->
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