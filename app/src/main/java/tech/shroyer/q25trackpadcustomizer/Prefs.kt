package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * SharedPreferences wrapper for app settings.
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- System default mode ----

    fun getSystemDefaultMode(): Mode {
        val value = prefs.getInt(KEY_SYSTEM_DEFAULT_MODE, Mode.MOUSE.prefValue)
        return Mode.fromPrefValue(value)
    }

    fun setSystemDefaultMode(mode: Mode) {
        prefs.edit().putInt(KEY_SYSTEM_DEFAULT_MODE, mode.prefValue).apply()
    }

    // ---- Last-known logical mode ----
    // Avoids ambiguous /proc reads and improves restore reliability.

    fun getLastKnownMode(): Mode? {
        if (!prefs.contains(KEY_LAST_KNOWN_MODE)) return null
        val value = prefs.getInt(KEY_LAST_KNOWN_MODE, Mode.MOUSE.prefValue)
        return Mode.fromPrefValue(value)
    }

    fun setLastKnownMode(mode: Mode?) {
        prefs.edit().apply {
            if (mode == null) remove(KEY_LAST_KNOWN_MODE)
            else putInt(KEY_LAST_KNOWN_MODE, mode.prefValue)
        }.apply()
    }

    // ---- Per-app modes ----

    fun getAppMode(packageName: String): Mode? {
        val key = keyAppMode(packageName)
        if (!prefs.contains(key)) return null
        val value = prefs.getInt(key, Mode.FOLLOW_SYSTEM.prefValue)
        return Mode.fromPrefValue(value)
    }

    fun setAppMode(packageName: String, mode: Mode) {
        prefs.edit().putInt(keyAppMode(packageName), mode.prefValue).apply()
    }

    // ---- Toast settings ----

    fun isToastQuickToggleEnabled(): Boolean = prefs.getBoolean(KEY_TOAST_QUICK_TOGGLE, true)

    fun setToastQuickToggleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_QUICK_TOGGLE, enabled).apply()
    }

    fun isToastPerAppEnabled(): Boolean = prefs.getBoolean(KEY_TOAST_PER_APP, true)

    fun setToastPerAppEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_PER_APP, enabled).apply()
    }

    fun isToastDefaultModeEnabled(): Boolean = prefs.getBoolean(KEY_TOAST_DEFAULT_MODE, true)

    fun setToastDefaultModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_DEFAULT_MODE, enabled).apply()
    }

    fun isToastHoldKeyEnabled(): Boolean = prefs.getBoolean(KEY_TOAST_HOLD_KEY, true)

    fun setToastHoldKeyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_HOLD_KEY, enabled).apply()
    }

    // ---- Quick Toggle mode selection (global + per-app overrides) ----
    // Stored as a bitmask: MOUSE=1, KEYBOARD=2, SCROLL_WHEEL=4.

    fun getGlobalQuickToggleModes(): Set<Mode> {
        val mask = prefs.getInt(KEY_QUICK_TOGGLE_MODES_GLOBAL, QUICK_TOGGLE_DEFAULT_MASK)
        val set = maskToQuickToggleModes(mask)
        return if (set.isEmpty()) maskToQuickToggleModes(QUICK_TOGGLE_DEFAULT_MASK) else set
    }

    fun setGlobalQuickToggleModes(modes: Set<Mode>) {
        val sanitized = sanitizeQuickToggleModes(modes)
        val final = if (sanitized.isEmpty()) maskToQuickToggleModes(QUICK_TOGGLE_DEFAULT_MASK) else sanitized
        prefs.edit().putInt(KEY_QUICK_TOGGLE_MODES_GLOBAL, quickToggleModesToMask(final)).apply()
    }

    /**
     * Per-app override set for quick toggle modes, or null if not set.
     * Empty/invalid masks are treated as "not set".
     */
    fun getAppQuickToggleModesOverride(packageName: String?): Set<Mode>? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyQuickToggleModesPkg(packageName)
        if (!prefs.contains(key)) return null
        val mask = prefs.getInt(key, 0)
        val set = maskToQuickToggleModes(mask)
        return if (set.isEmpty()) null else set
    }

    fun setAppQuickToggleModesOverride(packageName: String, overrideModes: Set<Mode>?) {
        val key = keyQuickToggleModesPkg(packageName)
        prefs.edit().apply {
            if (overrideModes == null) {
                remove(key)
            } else {
                val sanitized = sanitizeQuickToggleModes(overrideModes)
                if (sanitized.isEmpty()) remove(key)
                else putInt(key, quickToggleModesToMask(sanitized))
            }
        }.apply()
    }

    /**
     * Effective quick toggle mode set (app override if present, else global),
     * clamped to at least one option.
     */
    fun getEffectiveQuickToggleModes(packageName: String?): Set<Mode> {
        val raw = getAppQuickToggleModesOverride(packageName) ?: getGlobalQuickToggleModes()
        val sanitized = sanitizeQuickToggleModes(raw)
        return if (sanitized.isNotEmpty()) sanitized else getGlobalQuickToggleModes()
    }

    /**
     * Global fallback used only when:
     * - user selected exactly 1 quick toggle mode
     * - and that selection equals the app's base mode
     */
    fun getGlobalQuickToggleSingleMatchFallbackMode(): Mode {
        val value = prefs.getInt(KEY_QUICK_TOGGLE_SINGLE_MATCH_FALLBACK, Mode.MOUSE.prefValue)
        val m = Mode.fromPrefValue(value)
        return when (m) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> m
            else -> Mode.MOUSE
        }
    }

    fun setGlobalQuickToggleSingleMatchFallbackMode(mode: Mode) {
        val safe = when (mode) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> mode
            else -> Mode.MOUSE
        }
        prefs.edit().putInt(KEY_QUICK_TOGGLE_SINGLE_MATCH_FALLBACK, safe.prefValue).apply()
    }

    // ---- Auto keyboard on text input (global + per-app) ----

    fun isGlobalAutoKeyboardForTextEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_KEYBOARD_FOR_TEXT_GLOBAL, true)
    }

    fun setGlobalAutoKeyboardForTextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_KEYBOARD_FOR_TEXT_GLOBAL, enabled).apply()
    }

    /**
     * Per-app override for auto keyboard, or null if not set.
     * - true  => forced ON
     * - false => forced OFF
     * - null  => follow global
     */
    fun getAppAutoKeyboardOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyAutoKeyboardForTextPkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else null
    }

    fun setAppAutoKeyboardOverride(packageName: String, override: Boolean?) {
        val key = keyAutoKeyboardForTextPkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveAutoKeyboardForTextEnabled(packageName: String?): Boolean {
        return getAppAutoKeyboardOverride(packageName) ?: isGlobalAutoKeyboardForTextEnabled()
    }

    // ---- Primary hold mode switching (global + per-app overrides) ----

    fun getGlobalHoldMode(): HoldMode {
        val value = prefs.getInt(KEY_HOLD_MODE_GLOBAL, HoldMode.DISABLED.prefValue)
        return HoldMode.fromPrefValue(value)
    }

    fun setGlobalHoldMode(mode: HoldMode) {
        prefs.edit().putInt(KEY_HOLD_MODE_GLOBAL, mode.prefValue).apply()
    }

    fun getGlobalHoldKeyCode(): Int {
        return prefs.getInt(KEY_HOLD_KEYCODE_GLOBAL, DEFAULT_HOLD_KEYCODE)
    }

    fun setGlobalHoldKeyCode(keyCode: Int) {
        prefs.edit().putInt(KEY_HOLD_KEYCODE_GLOBAL, keyCode).apply()
    }

    fun isGlobalHoldAllowedInTextFields(): Boolean {
        return prefs.getBoolean(KEY_HOLD_ALLOW_IN_TEXT_GLOBAL, false)
    }

    fun setGlobalHoldAllowedInTextFields(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_HOLD_ALLOW_IN_TEXT_GLOBAL, allowed).apply()
    }

    fun getAppHoldModeOverride(packageName: String?): HoldMode? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHoldModePkg(packageName)
        if (!prefs.contains(key)) return null
        return HoldMode.fromPrefValue(prefs.getInt(key, HoldMode.DISABLED.prefValue))
    }

    fun setAppHoldModeOverride(packageName: String, override: HoldMode?) {
        val key = keyHoldModePkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putInt(key, override.prefValue)
        }.apply()
    }

    fun getEffectiveHoldMode(packageName: String?): HoldMode {
        return getAppHoldModeOverride(packageName) ?: getGlobalHoldMode()
    }

    fun getAppHoldKeyCodeOverride(packageName: String?): Int? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHoldKeyCodePkg(packageName)
        return if (prefs.contains(key)) prefs.getInt(key, DEFAULT_HOLD_KEYCODE) else null
    }

    fun setAppHoldKeyCodeOverride(packageName: String, overrideKeyCode: Int?) {
        val key = keyHoldKeyCodePkg(packageName)
        prefs.edit().apply {
            if (overrideKeyCode == null) remove(key)
            else putInt(key, overrideKeyCode)
        }.apply()
    }

    fun getEffectiveHoldKeyCode(packageName: String?): Int {
        return getAppHoldKeyCodeOverride(packageName) ?: getGlobalHoldKeyCode()
    }

    /**
     * Per-app override for allowing hold switching inside text fields.
     * - true  => allow
     * - false => disallow
     * - null  => follow global
     */
    fun getAppHoldAllowedInTextFieldsOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHoldAllowInTextPkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setAppHoldAllowedInTextFieldsOverride(packageName: String, override: Boolean?) {
        val key = keyHoldAllowInTextPkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveHoldAllowedInTextFields(packageName: String?): Boolean {
        return getAppHoldAllowedInTextFieldsOverride(packageName) ?: isGlobalHoldAllowedInTextFields()
    }

    // ---- Primary hold: require double-press + hold (global + per-app) ----

    fun isGlobalHoldDoublePressRequired(): Boolean {
        return prefs.getBoolean(KEY_HOLD_DOUBLE_PRESS_GLOBAL, false)
    }

    fun setGlobalHoldDoublePressRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_HOLD_DOUBLE_PRESS_GLOBAL, required).apply()
    }

    /**
     * Per-app override for Primary Hold double-press requirement.
     * - true  => require double-press + hold
     * - false => single press + hold
     * - null  => follow global
     */
    fun getAppHoldDoublePressRequiredOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHoldDoublePressPkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setAppHoldDoublePressRequiredOverride(packageName: String, override: Boolean?) {
        val key = keyHoldDoublePressPkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveHoldDoublePressRequired(packageName: String?): Boolean {
        return getAppHoldDoublePressRequiredOverride(packageName) ?: isGlobalHoldDoublePressRequired()
    }

    // ---- Secondary hold mode switching (global + per-app overrides) ----

    fun getGlobalHold2Mode(): HoldMode {
        val value = prefs.getInt(KEY_HOLD2_MODE_GLOBAL, HoldMode.DISABLED.prefValue)
        return HoldMode.fromPrefValue(value)
    }

    fun setGlobalHold2Mode(mode: HoldMode) {
        prefs.edit().putInt(KEY_HOLD2_MODE_GLOBAL, mode.prefValue).apply()
    }

    fun getGlobalHold2KeyCode(): Int {
        return prefs.getInt(KEY_HOLD2_KEYCODE_GLOBAL, DEFAULT_HOLD2_KEYCODE)
    }

    fun setGlobalHold2KeyCode(keyCode: Int) {
        prefs.edit().putInt(KEY_HOLD2_KEYCODE_GLOBAL, keyCode).apply()
    }

    fun isGlobalHold2AllowedInTextFields(): Boolean {
        return prefs.getBoolean(KEY_HOLD2_ALLOW_IN_TEXT_GLOBAL, false)
    }

    fun setGlobalHold2AllowedInTextFields(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_HOLD2_ALLOW_IN_TEXT_GLOBAL, allowed).apply()
    }

    fun getAppHold2ModeOverride(packageName: String?): HoldMode? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHold2ModePkg(packageName)
        if (!prefs.contains(key)) return null
        return HoldMode.fromPrefValue(prefs.getInt(key, HoldMode.DISABLED.prefValue))
    }

    fun setAppHold2ModeOverride(packageName: String, override: HoldMode?) {
        val key = keyHold2ModePkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putInt(key, override.prefValue)
        }.apply()
    }

    fun getEffectiveHold2Mode(packageName: String?): HoldMode {
        return getAppHold2ModeOverride(packageName) ?: getGlobalHold2Mode()
    }

    fun getAppHold2KeyCodeOverride(packageName: String?): Int? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHold2KeyCodePkg(packageName)
        return if (prefs.contains(key)) prefs.getInt(key, DEFAULT_HOLD2_KEYCODE) else null
    }

    fun setAppHold2KeyCodeOverride(packageName: String, overrideKeyCode: Int?) {
        val key = keyHold2KeyCodePkg(packageName)
        prefs.edit().apply {
            if (overrideKeyCode == null) remove(key)
            else putInt(key, overrideKeyCode)
        }.apply()
    }

    fun getEffectiveHold2KeyCode(packageName: String?): Int {
        return getAppHold2KeyCodeOverride(packageName) ?: getGlobalHold2KeyCode()
    }

    /**
     * Per-app override for allowing Secondary Hold switching inside text fields.
     * - true  => allow
     * - false => disallow
     * - null  => follow global
     */
    fun getAppHold2AllowedInTextFieldsOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHold2AllowInTextPkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setAppHold2AllowedInTextFieldsOverride(packageName: String, override: Boolean?) {
        val key = keyHold2AllowInTextPkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveHold2AllowedInTextFields(packageName: String?): Boolean {
        return getAppHold2AllowedInTextFieldsOverride(packageName) ?: isGlobalHold2AllowedInTextFields()
    }

    // ---- Secondary hold: require double-press + hold (global + per-app) ----
    // Only meaningful when Secondary Hold is using its own keycode (not tied to Primary Hold's double-press gesture).

    fun isGlobalHold2DoublePressRequired(): Boolean {
        return prefs.getBoolean(KEY_HOLD2_DOUBLE_PRESS_GLOBAL, false)
    }

    fun setGlobalHold2DoublePressRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_HOLD2_DOUBLE_PRESS_GLOBAL, required).apply()
    }

    /**
     * Per-app override for Secondary Hold double-press requirement.
     * - true  => require double-press + hold
     * - false => single press + hold
     * - null  => follow global
     */
    fun getAppHold2DoublePressRequiredOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHold2DoublePressPkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setAppHold2DoublePressRequiredOverride(packageName: String, override: Boolean?) {
        val key = keyHold2DoublePressPkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveHold2DoublePressRequired(packageName: String?): Boolean {
        return getAppHold2DoublePressRequiredOverride(packageName) ?: isGlobalHold2DoublePressRequired()
    }

    // ---- Secondary hold: "Use Primary Hold double-press + hold" trigger (global + per-app) ----

    fun isGlobalHold2UseHold1DoublePressHold(): Boolean {
        return prefs.getBoolean(KEY_HOLD2_USE_HOLD1_DOUBLE_GLOBAL, false)
    }

    fun setGlobalHold2UseHold1DoublePressHold(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOLD2_USE_HOLD1_DOUBLE_GLOBAL, enabled).apply()
    }

    /**
     * Per-app override for Secondary Hold using Primary Hold's double-press+hold gesture.
     * - true  => Secondary Hold is triggered by Primary Hold double-press+hold
     * - false => Secondary Hold uses its own keycode
     * - null  => follow global
     */
    fun getAppHold2UseHold1DoublePressHoldOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyHold2UseHold1DoublePkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setAppHold2UseHold1DoublePressHoldOverride(packageName: String, override: Boolean?) {
        val key = keyHold2UseHold1DoublePkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveHold2UseHold1DoublePressHold(packageName: String?): Boolean {
        return getAppHold2UseHold1DoublePressHoldOverride(packageName) ?: isGlobalHold2UseHold1DoublePressHold()
    }

    // ---- Theme ----

    fun getThemePref(): ThemePref {
        val value = prefs.getInt(KEY_THEME_PREF, ThemePref.FOLLOW_SYSTEM.prefValue)
        return ThemePref.fromPrefValue(value)
    }

    fun setThemePref(themePref: ThemePref) {
        prefs.edit().putInt(KEY_THEME_PREF, themePref.prefValue).apply()
    }

    // ---- Excluded packages ----

    fun getExcludedPackages(): Set<String> {
        val stored = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null)
        return if (stored != null) {
            HashSet(stored)
        } else {
            val defaults = DEFAULT_EXCLUDED_PACKAGES
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, HashSet(defaults)).apply()
            defaults
        }
    }

    fun addExcludedPackage(packageName: String) {
        val set = HashSet(getExcludedPackages())
        if (set.add(packageName)) {
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, set).apply()
        }
    }

    fun removeExcludedPackage(packageName: String) {
        val set = HashSet(getExcludedPackages())
        if (set.remove(packageName)) {
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, set).apply()
        }
    }

    fun resetExcludedPackagesToDefault() {
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, HashSet(DEFAULT_EXCLUDED_PACKAGES)).apply()
    }

    // ---- Global scroll wheel settings ----

    fun getGlobalScrollSettings(): ScrollSettings {
        val sensValue = prefs.getInt(KEY_SCROLL_GLOBAL_SENS, ScrollSensitivity.MEDIUM.prefValue)
        val sens = ScrollSensitivity.fromPrefValue(sensValue)
        val horizontal = prefs.getBoolean(KEY_SCROLL_GLOBAL_HORIZONTAL, false)
        val invertV = prefs.getBoolean(KEY_SCROLL_GLOBAL_INV_V, false)
        val invertH = prefs.getBoolean(KEY_SCROLL_GLOBAL_INV_H, false)
        return ScrollSettings(
            sensitivity = sens,
            horizontalEnabled = horizontal,
            invertVertical = invertV,
            invertHorizontal = invertH
        )
    }

    fun setGlobalScrollSensitivity(sensitivity: ScrollSensitivity) {
        prefs.edit().putInt(KEY_SCROLL_GLOBAL_SENS, sensitivity.prefValue).apply()
    }

    fun setGlobalScrollHorizontalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCROLL_GLOBAL_HORIZONTAL, enabled).apply()
    }

    fun setGlobalScrollInvertVertical(invert: Boolean) {
        prefs.edit().putBoolean(KEY_SCROLL_GLOBAL_INV_V, invert).apply()
    }

    fun setGlobalScrollInvertHorizontal(invert: Boolean) {
        prefs.edit().putBoolean(KEY_SCROLL_GLOBAL_INV_H, invert).apply()
    }

    // ---- Per-app scroll settings ----

    private fun hasAnyAppScrollSettings(packageName: String): Boolean {
        return prefs.all.keys.any { key ->
            key.startsWith(PREFIX_SCROLL_APP) && key.endsWith("_$packageName")
        }
    }

    fun getAppScrollSettings(packageName: String): ScrollSettings? {
        if (!hasAnyAppScrollSettings(packageName)) return null

        val global = getGlobalScrollSettings()

        val sensKey = keyScrollAppSens(packageName)
        val horizKey = keyScrollAppHorizontal(packageName)
        val invVKey = keyScrollAppInvV(packageName)
        val invHKey = keyScrollAppInvH(packageName)

        val sens = if (prefs.contains(sensKey)) {
            ScrollSensitivity.fromPrefValue(prefs.getInt(sensKey, global.sensitivity.prefValue))
        } else global.sensitivity

        val horizontal = if (prefs.contains(horizKey)) {
            prefs.getBoolean(horizKey, global.horizontalEnabled)
        } else global.horizontalEnabled

        val invertV = if (prefs.contains(invVKey)) {
            prefs.getBoolean(invVKey, global.invertVertical)
        } else global.invertVertical

        val invertH = if (prefs.contains(invHKey)) {
            prefs.getBoolean(invHKey, global.invertHorizontal)
        } else global.invertHorizontal

        return ScrollSettings(
            sensitivity = sens,
            horizontalEnabled = horizontal,
            invertVertical = invertV,
            invertHorizontal = invertH
        )
    }

    fun getEffectiveScrollSettings(packageName: String?): ScrollSettings {
        val global = getGlobalScrollSettings()
        if (packageName.isNullOrEmpty()) return global
        return getAppScrollSettings(packageName) ?: global
    }

    fun setAppScrollSensitivity(packageName: String, sensitivity: ScrollSensitivity) {
        prefs.edit().putInt(keyScrollAppSens(packageName), sensitivity.prefValue).apply()
    }

    fun setAppScrollHorizontalEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyScrollAppHorizontal(packageName), enabled).apply()
    }

    fun setAppScrollInvertVertical(packageName: String, invert: Boolean) {
        prefs.edit().putBoolean(keyScrollAppInvV(packageName), invert).apply()
    }

    fun setAppScrollInvertHorizontal(packageName: String, invert: Boolean) {
        prefs.edit().putBoolean(keyScrollAppInvH(packageName), invert).apply()
    }

    // ---- Internal helpers ----

    private fun sanitizeQuickToggleModes(modes: Set<Mode>): Set<Mode> {
        val out = LinkedHashSet<Mode>()
        for (m in modes) {
            when (m) {
                Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> out.add(m)
                else -> Unit
            }
        }
        return out
    }

    private fun quickToggleModesToMask(modes: Set<Mode>): Int {
        var mask = 0
        for (m in modes) {
            mask = mask or when (m) {
                Mode.MOUSE -> QUICK_TOGGLE_MASK_MOUSE
                Mode.KEYBOARD -> QUICK_TOGGLE_MASK_KEYBOARD
                Mode.SCROLL_WHEEL -> QUICK_TOGGLE_MASK_SCROLL
                else -> 0
            }
        }
        return mask
    }

    private fun maskToQuickToggleModes(mask: Int): Set<Mode> {
        val out = LinkedHashSet<Mode>()
        if ((mask and QUICK_TOGGLE_MASK_MOUSE) != 0) out.add(Mode.MOUSE)
        if ((mask and QUICK_TOGGLE_MASK_KEYBOARD) != 0) out.add(Mode.KEYBOARD)
        if ((mask and QUICK_TOGGLE_MASK_SCROLL) != 0) out.add(Mode.SCROLL_WHEEL)
        return out
    }

    companion object {
        private const val PREFS_NAME = "q25_prefs"

        private const val KEY_SYSTEM_DEFAULT_MODE = "system_default_mode"
        private const val KEY_LAST_KNOWN_MODE = "last_known_mode"

        private const val PREFIX_APP_MODE = "app_mode_"
        private fun keyAppMode(pkg: String) = "$PREFIX_APP_MODE$pkg"

        private const val KEY_TOAST_QUICK_TOGGLE = "toast_quick_toggle"
        private const val KEY_TOAST_PER_APP = "toast_per_app"
        private const val KEY_TOAST_DEFAULT_MODE = "toast_default_mode"
        private const val KEY_TOAST_HOLD_KEY = "toast_hold_key"

        // Quick Toggle selection
        private const val KEY_QUICK_TOGGLE_MODES_GLOBAL = "quick_toggle_modes_global"
        private const val KEY_QUICK_TOGGLE_SINGLE_MATCH_FALLBACK = "quick_toggle_single_match_fallback"
        private fun keyQuickToggleModesPkg(pkg: String) = "quick_toggle_modes_pkg_$pkg"

        private const val QUICK_TOGGLE_MASK_MOUSE = 1
        private const val QUICK_TOGGLE_MASK_KEYBOARD = 2
        private const val QUICK_TOGGLE_MASK_SCROLL = 4
        private const val QUICK_TOGGLE_DEFAULT_MASK =
            QUICK_TOGGLE_MASK_MOUSE or QUICK_TOGGLE_MASK_KEYBOARD or QUICK_TOGGLE_MASK_SCROLL

        private const val KEY_AUTO_KEYBOARD_FOR_TEXT_GLOBAL = "auto_keyboard_for_text"
        private fun keyAutoKeyboardForTextPkg(pkg: String) = "auto_keyboard_for_text_pkg_$pkg"

        // Primary Hold
        private const val KEY_HOLD_MODE_GLOBAL = "hold_mode_global"
        private const val KEY_HOLD_KEYCODE_GLOBAL = "hold_keycode_global"
        private const val KEY_HOLD_ALLOW_IN_TEXT_GLOBAL = "hold_allow_in_text_global"

        private fun keyHoldModePkg(pkg: String) = "hold_mode_pkg_$pkg"
        private fun keyHoldKeyCodePkg(pkg: String) = "hold_keycode_pkg_$pkg"
        private fun keyHoldAllowInTextPkg(pkg: String) = "hold_allow_in_text_pkg_$pkg"

        // Primary Hold double-press requirement
        private const val KEY_HOLD_DOUBLE_PRESS_GLOBAL = "hold_double_press_global"
        private fun keyHoldDoublePressPkg(pkg: String) = "hold_double_press_pkg_$pkg"

        // Secondary Hold
        private const val KEY_HOLD2_MODE_GLOBAL = "hold2_mode_global"
        private const val KEY_HOLD2_KEYCODE_GLOBAL = "hold2_keycode_global"
        private const val KEY_HOLD2_ALLOW_IN_TEXT_GLOBAL = "hold2_allow_in_text_global"

        private fun keyHold2ModePkg(pkg: String) = "hold2_mode_pkg_$pkg"
        private fun keyHold2KeyCodePkg(pkg: String) = "hold2_keycode_pkg_$pkg"
        private fun keyHold2AllowInTextPkg(pkg: String) = "hold2_allow_in_text_pkg_$pkg"

        // Secondary Hold double-press requirement
        private const val KEY_HOLD2_DOUBLE_PRESS_GLOBAL = "hold2_double_press_global"
        private fun keyHold2DoublePressPkg(pkg: String) = "hold2_double_press_pkg_$pkg"

        // Secondary Hold uses Primary Hold double-press+hold gesture
        private const val KEY_HOLD2_USE_HOLD1_DOUBLE_GLOBAL = "hold2_use_hold1_double_global"
        private fun keyHold2UseHold1DoublePkg(pkg: String) = "hold2_use_hold1_double_pkg_$pkg"

        private const val KEY_THEME_PREF = "theme_pref"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

        private const val KEY_SCROLL_GLOBAL_SENS = "scroll_global_sens"
        private const val KEY_SCROLL_GLOBAL_HORIZONTAL = "scroll_global_horizontal"
        private const val KEY_SCROLL_GLOBAL_INV_V = "scroll_global_inv_v"
        private const val KEY_SCROLL_GLOBAL_INV_H = "scroll_global_inv_h"

        private const val PREFIX_SCROLL_APP = "scroll_app_"
        private fun keyScrollAppSens(pkg: String) = "scroll_app_sens_$pkg"
        private fun keyScrollAppHorizontal(pkg: String) = "scroll_app_horizontal_$pkg"
        private fun keyScrollAppInvV(pkg: String) = "scroll_app_inv_v_$pkg"
        private fun keyScrollAppInvH(pkg: String) = "scroll_app_inv_h_$pkg"

        const val DEFAULT_HOLD_KEYCODE = 59 // KEYCODE_SHIFT_LEFT
        const val DEFAULT_HOLD2_KEYCODE = 60 // KEYCODE_SHIFT_RIGHT

        val DEFAULT_EXCLUDED_PACKAGES: Set<String> = setOf(
            // Q25 / custom keyboards
            "it.srik.TypeQ25",
            "com.duc1607.q25keyboard",

            // Popular keyboards
            "com.blackberry.keyboard",
            "com.google.android.inputmethod.latin", // Gboard
            "com.touchtype.swiftkey",            // SwiftKey
            "it.srik.TypeQ25",                  // TypeQ25
            "com.duc1607.q25keyboard"           // Dave's Q25 Keyboard
        )

        fun applyThemeFromPrefs(context: Context) {
            val prefs = Prefs(context.applicationContext)
            val themePref = prefs.getThemePref()
            val mode = when (themePref) {
                ThemePref.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemePref.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemePref.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}