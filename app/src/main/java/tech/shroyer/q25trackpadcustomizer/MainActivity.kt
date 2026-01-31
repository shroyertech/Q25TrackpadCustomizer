package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var spinnerSystemDefault: Spinner
    private lateinit var spinnerSetAllMode: Spinner
    private lateinit var btnSetAll: Button
    private lateinit var recyclerApps: RecyclerView
    private lateinit var editSearch: EditText
    private lateinit var btnSettings: ImageButton

    private lateinit var appListAdapter: AppListAdapter
    private var allApps: List<AppItem> = emptyList()

    private val systemDefaultOptions = listOf(
        Mode.MOUSE,
        Mode.KEYBOARD,
        Mode.SCROLL_WHEEL
    )

    private val systemDefaultLabels = listOf(
        "Mouse mode",
        "Keyboard mode",
        "Scroll wheel mode"
    )

    private val setAllOptions = listOf(
        Mode.FOLLOW_SYSTEM,
        Mode.MOUSE,
        Mode.KEYBOARD,
        Mode.SCROLL_WHEEL
    )

    private val setAllLabels = listOf(
        "Follow system",
        "Mouse mode",
        "Keyboard mode",
        "Scroll wheel mode"
    )

    // Prevents spinner refresh from writing prefs
    private var suppressSystemDefaultListener = false

    // ---- Setup / prompts ----

    private var firstLaunchFlowPending = false
    private var hasShownFirstLaunchWizardThisActivity = false
    private var hasShownMissingPromptThisActivity = false
    private var rootCheckInFlight = false

    // Prevent wizard dialogs from stacking
    private var wizardDialogShowing = false

    private enum class RootManager { MAGISK, KERNELSU, OTHER }

    private data class RootAccessResult(
        val granted: Boolean,
        val message: String,
        val manager: RootManager
    )

    companion object {
        private const val SHARED_PREFS_NAME = "q25_prefs" // same name Prefs uses
        private const val KEY_SETUP_WIZARD_SHOWN = "setup_wizard_shown_v1"

        // Shared between MainActivity + SettingsActivity
        private const val KEY_A11Y_WIZARD_STEP = "a11y_wizard_step_v1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyThemeFromPrefs(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        spinnerSystemDefault = findViewById(R.id.spinnerSystemDefault)
        spinnerSetAllMode = findViewById(R.id.spinnerSetAllMode)
        btnSetAll = findViewById(R.id.btnSetAll)
        recyclerApps = findViewById(R.id.recyclerApps)
        editSearch = findViewById(R.id.editSearch)
        btnSettings = findViewById(R.id.btnSettings)

        setupSystemDefaultSpinner()
        setupSetAllSpinner()
        setupAppList()
        setupSearch()
        setupSettingsButton()

        // First launch flow flag
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        firstLaunchFlowPending = !sp.getBoolean(KEY_SETUP_WIZARD_SHOWN, false)
        if (firstLaunchFlowPending) {
            sp.edit().putBoolean(KEY_SETUP_WIZARD_SHOWN, true).apply()
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-sync global UI from prefs
        refreshSystemDefaultSpinnerFromPrefs()
        refreshAppList(editSearch.text?.toString().orEmpty())

        // If the user is mid-accessibility wizard, continue it and skip other prompts.
        if (maybeContinueAccessibilityWizard()) {
            firstLaunchFlowPending = false
            return
        }

        // Requirements check (root + accessibility)
        runRequirementsCheckAndMaybePrompt(isFirstLaunch = firstLaunchFlowPending)
        firstLaunchFlowPending = false
    }

    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupSystemDefaultSpinner() {
        val adapter = ArrayAdapter(
            this,
            AndroidR.layout.simple_spinner_item,
            systemDefaultLabels
        )
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerSystemDefault.adapter = adapter

        val current = prefs.getSystemDefaultMode()
        val index = systemDefaultOptions.indexOf(current).coerceAtLeast(0)

        suppressSystemDefaultListener = true
        spinnerSystemDefault.setSelection(index, false)
        suppressSystemDefaultListener = false

        spinnerSystemDefault.setOnItemSelectedListenerSimple { position ->
            if (suppressSystemDefaultListener) return@setOnItemSelectedListenerSimple
            val mode = systemDefaultOptions[position]
            prefs.setSystemDefaultMode(mode)
        }
    }

    private fun refreshSystemDefaultSpinnerFromPrefs() {
        if (!::spinnerSystemDefault.isInitialized) return
        val current = prefs.getSystemDefaultMode()
        val desiredIndex = systemDefaultOptions.indexOf(current).coerceAtLeast(0)

        if (spinnerSystemDefault.selectedItemPosition != desiredIndex) {
            suppressSystemDefaultListener = true
            spinnerSystemDefault.setSelection(desiredIndex, false)
            suppressSystemDefaultListener = false
        }
    }

    private fun setupSetAllSpinner() {
        val adapter = ArrayAdapter(
            this,
            AndroidR.layout.simple_spinner_item,
            setAllLabels
        )
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerSetAllMode.adapter = adapter

        spinnerSetAllMode.setSelection(0, false) // default to Follow system

        btnSetAll.setOnClickListener {
            val mode = setAllOptions[spinnerSetAllMode.selectedItemPosition]
            setAllAppsToMode(mode)
        }
    }

    private fun setupAppList() {
        recyclerApps.layoutManager = LinearLayoutManager(this)
        allApps = loadLaunchableApps()
        appListAdapter = AppListAdapter(this, allApps, prefs)
        recyclerApps.adapter = appListAdapter
    }

    private fun refreshAppList(currentQuery: String) {
        allApps = loadLaunchableApps()
        appListAdapter.updateApps(allApps)
        appListAdapter.filter(currentQuery)
    }

    private fun setupSearch() {
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appListAdapter.filter(s?.toString() ?: "")
            }
        })
    }

    /**
     * Load launchable apps and filter out excluded apps and myself.
     */
    private fun loadLaunchableApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

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

    private fun setAllAppsToMode(mode: Mode) {
        val apps = loadLaunchableApps()
        apps.forEach { app ->
            prefs.setAppMode(app.packageName, mode)
        }
        Toast.makeText(
            this,
            "Set ${apps.size} apps to ${modeLabel(mode)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun modeLabel(mode: Mode): String {
        return when (mode) {
            Mode.MOUSE -> "Mouse mode"
            Mode.KEYBOARD -> "Keyboard mode"
            Mode.SCROLL_WHEEL -> "Scroll wheel mode"
            Mode.FOLLOW_SYSTEM -> "Follow system"
        }
    }

    // -------------------------------
    // Accessibility step-by-step wizard
    // -------------------------------

    private fun getA11yWizardStep(): Int {
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        return sp.getInt(KEY_A11Y_WIZARD_STEP, 0)
    }

    private fun setA11yWizardStep(step: Int) {
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        sp.edit().putInt(KEY_A11Y_WIZARD_STEP, step).apply()
    }

    private fun cancelAccessibilityWizard() {
        setA11yWizardStep(0)
    }

    private fun beginAccessibilityWizard() {
        setA11yWizardStep(1)
        showAccessibilityWizardStep1()
    }

    private fun maybeContinueAccessibilityWizard(): Boolean {
        if (wizardDialogShowing) return true

        val step = getA11yWizardStep()
        if (step == 0) return false

        if (isAccessibilityEnabled()) {
            cancelAccessibilityWizard()
            return false
        }

        when (step) {
            1 -> showAccessibilityWizardStep1()
            2 -> showAccessibilityWizardStep2()
            3 -> showAccessibilityWizardStep3()
            4 -> showAccessibilityWizardStillNotEnabled()
            else -> cancelAccessibilityWizard()
        }
        return true
    }

    private fun showAccessibilityWizardStep1() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 1 of 3\n\n" +
                    "• Open Accessibility settings\n" +
                    "• Tap “Q25 Trackpad Customizer” (will be grayed out)\n" +
                    "• You’ll see a “Restricted setting” message\n" +
                    "• Come back here (just hit Back)\n\n" +
                    "Already saw the restricted message? You can jump to App info."

        showScrollableDialog(
            title = "Enable Accessibility",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(2)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppInfoSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStep2() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 2 of 3\n\n" +
                    "Now allow restricted settings for this app:\n" +
                    "• In App info, tap the ⋮ menu (top-right)\n" +
                    "• Tap “Allow restricted settings”\n" +
                    "• Enter your PIN/passcode, then return here (Press Back button)\n\n" +
                    "Don’t see the ⋮ menu? You probably missed step 1 - go back and tap the app in Accessibility first."

        showScrollableDialog(
            title = "Allow restricted settings",
            message = msg,
            positiveText = "Open App Info",
            onPositive = {
                setA11yWizardStep(3)
                openAppInfoSettings()
            },
            neutralText = "Back to Accessibility",
            onNeutral = {
                setA11yWizardStep(2)
                openAccessibilitySettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStep3() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 3 of 3\n\n" +
                    "Go back to Accessibility and enable the service:\n" +
                    "Settings > Accessibility > Q25 Trackpad Customizer > Enable\n\n" +
                    "Still grayed out? Go back to App info and double-check “Allow restricted settings” is enabled."

        showScrollableDialog(
            title = "Enable the service",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(4)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppInfoSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStillNotEnabled() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "It looks like the Accessibility service is still OFF.\n\n" +
                    "Go back and make sure “Q25 Trackpad Customizer” is enabled in Accessibility.\n\n" +
                    "If it’s still grayed out, re-check App info > ⋮ > Allow restricted settings."

        showScrollableDialog(
            title = "Almost there",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(4)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppInfoSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showScrollableDialog(
        title: String,
        message: String,
        positiveText: String,
        onPositive: (() -> Unit)?,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        negativeText: String = "Close",
        onNegative: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(6))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tv = TextView(this).apply {
            text = message
            textSize = 14f
        }

        container.addView(tv)
        scroll.addView(container)

        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(positiveText) { d, _ ->
                d.dismiss()
                onPositive?.invoke()
            }
            .setNegativeButton(negativeText) { d, _ ->
                d.dismiss()
                onNegative?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }

        if (!neutralText.isNullOrBlank()) {
            b.setNeutralButton(neutralText) { d, _ ->
                d.dismiss()
                onNeutral?.invoke()
            }
        }

        b.show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // -------------------------------
    // Setup checks + prompts
    // -------------------------------

    private fun runRequirementsCheckAndMaybePrompt(isFirstLaunch: Boolean) {
        if (rootCheckInFlight) return

        rootCheckInFlight = true

        Thread {
            val rootResult = testRootAccessDetailed()
            runOnUiThread {
                rootCheckInFlight = false

                val accessibilityEnabledNow = isAccessibilityEnabled()
                val missingRoot = !rootResult.granted
                val missingA11y = !accessibilityEnabledNow

                // If the user is mid-wizard, don’t stack prompts.
                if (getA11yWizardStep() != 0) return@runOnUiThread

                if (isFirstLaunch && !hasShownFirstLaunchWizardThisActivity && (missingRoot || missingA11y)) {
                    hasShownFirstLaunchWizardThisActivity = true

                    // Common case: root is OK, only a11y is missing -> start the guided wizard immediately.
                    if (!missingRoot) {
                        beginAccessibilityWizard()
                        return@runOnUiThread
                    }

                    showFirstLaunchSetupDialog(rootResult, accessibilityEnabledNow)
                    return@runOnUiThread
                }

                if (!hasShownMissingPromptThisActivity && (missingRoot || missingA11y)) {
                    hasShownMissingPromptThisActivity = true
                    showMissingRequirementsPrompt(missingRoot, missingA11y)
                }
            }
        }.start()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
        } catch (_: Exception) {
            false
        }
        if (!enabled) return false

        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // Prefer exact service component match, but allow package-based fallback too.
        val component = ComponentName(this, AppSwitchService::class.java).flattenToString()
        return raw.split(':').any { entry ->
            entry.equals(component, ignoreCase = true) ||
                    entry.startsWith("$packageName/") ||
                    entry.contains(packageName)
        }
    }

    private fun detectRootManager(): RootManager {

        fun hasPkg(pkg: String): Boolean {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun hasAppLabel(labels: List<String>): Boolean {
            val pm = packageManager
            val installedApps = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }

            return installedApps.any { app ->
                val label = app.loadLabel(pm).toString().lowercase()
                labels.any { label.contains(it) }
            }
        }

        // ---- KernelSU ----

        val kernelSuPackages = listOf(
            "me.weishu.kernelsu",
            "vaurmet.nfdvv.ztmdui"
        )

        if (kernelSuPackages.any { hasPkg(it) }) {
            return RootManager.KERNELSU
        }

        val kernelSuLabels = listOf(
            "kernelsu",
            "kernelsu next"
        )

        if (hasAppLabel(kernelSuLabels)) {
            return RootManager.KERNELSU
        }

        // ---- Magisk ----

        val magiskPackages = listOf(
            "com.topjohnwu.magisk"
        )

        if (magiskPackages.any { hasPkg(it) }) {
            return RootManager.MAGISK
        }

        val magiskLabels = listOf(
            "magisk"
        )

        if (hasAppLabel(magiskLabels)) {
            return RootManager.MAGISK
        }

        return RootManager.OTHER
    }

    private fun testRootAccessDetailed(): RootAccessResult {
        val manager = detectRootManager()

        // This will trigger Magisk/KSU prompt when needed.
        val cmd = arrayOf("su", "-c", "id")

        return try {
            val pb = ProcessBuilder(*cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()

            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exit = proc.waitFor()

            val isRoot = (exit == 0) && output.contains("uid=0")
            when {
                isRoot -> RootAccessResult(true, "Root access granted.", manager)
                exit == 0 -> RootAccessResult(false, "su ran, but didn’t return uid=0.", manager)
                else -> RootAccessResult(false, "Root access denied or unavailable.", manager)
            }
        } catch (_: Exception) {
            RootAccessResult(false, "su not available or failed to run.", manager)
        }
    }

    private fun showFirstLaunchSetupDialog(root: RootAccessResult, accessibilityEnabled: Boolean) {
        val rootLine = if (root.granted) {
            "✅ Root access: Granted"
        } else {
            "❌ Root access: Not granted yet"
        }

        val a11yLine = if (accessibilityEnabled) {
            "✅ Accessibility: Enabled"
        } else {
            "❌ Accessibility: Not enabled yet"
        }

        val rootHelp = when (root.manager) {
            RootManager.MAGISK ->
                "If a Magisk prompt pops up, tap Allow. If you previously denied it, open Magisk > Superuser and enable this app."
            RootManager.KERNELSU ->
                "Open KernelSU > Superuser/App list and allow this app."
            RootManager.OTHER ->
                "If you use a different root manager, like KernelSU, look for a Superuser/Root access list and allow this app."
        }

        val a11yHelpShort =
            "Accessibility setup uses a guided 3-step wizard:\n" +
                    "Accessibility (tap app) → App info (allow restricted) → Accessibility (enable)."

        val msg =
            "Finish setup so the app can switch modes.\n\n" +
                    "$rootLine\n" +
                    "$a11yLine\n\n" +
                    "Root:\n$rootHelp\n\n" +
                    "Accessibility:\n$a11yHelpShort"

        val positiveLabel = if (accessibilityEnabled) "OK" else "Start Accessibility Setup"

        showScrollableDialog(
            title = "Finish setup",
            message = msg,
            positiveText = positiveLabel,
            onPositive = {
                if (!accessibilityEnabled) beginAccessibilityWizard()
            },
            neutralText = "Open App Settings",
            onNeutral = {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            negativeText = "Not now",
            onNegative = null
        )
    }

    private fun showMissingRequirementsPrompt(missingRoot: Boolean, missingA11y: Boolean) {
        val parts = mutableListOf<String>()
        if (missingRoot) parts.add("Root access")
        if (missingA11y) parts.add("Accessibility service")

        val msg =
            "Setup needed: ${parts.joinToString(" and ")}.\n\n" +
                    "Use the guided setup to enable Accessibility, and use Settings to test/grant Root."

        val positiveLabel = if (missingA11y) "Start Accessibility Setup" else "Open App Settings"

        showScrollableDialog(
            title = "Setup needed",
            message = msg,
            positiveText = positiveLabel,
            onPositive = {
                if (missingA11y) {
                    beginAccessibilityWizard()
                } else {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            },
            neutralText = if (missingA11y && missingRoot) "Open App Settings" else null,
            onNeutral = if (missingA11y && missingRoot) {
                { startActivity(Intent(this, SettingsActivity::class.java)) }
            } else null,
            negativeText = "Not now",
            onNegative = null
        )
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Couldn’t open Accessibility settings.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to open Accessibility settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppInfoSettings() {
        try {
            val uri = Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Couldn’t open App info.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to open App info.", Toast.LENGTH_LONG).show()
        }
    }
}