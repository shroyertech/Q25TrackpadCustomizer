package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
    }

    override fun onResume() {
        super.onResume()
        // Re-sync global UI from prefs
        refreshSystemDefaultSpinnerFromPrefs()
        refreshAppList(editSearch.text?.toString().orEmpty())
    }

    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupSystemDefaultSpinner() {
        val adapter = ArrayAdapter<String>(
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
        val adapter = ArrayAdapter<String>(
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
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appListAdapter.filter(s?.toString() ?: "")
            }
        })
    }

    /**
     * and filter out excluded apps and myself.
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

    /**
     */
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
}