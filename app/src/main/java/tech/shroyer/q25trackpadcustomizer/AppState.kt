package tech.shroyer.q25trackpadcustomizer

/**
 * Shared in-memory state for foreground tracking and temporary overrides.
 */
object AppState {

    @Volatile
    var currentForegroundPackage: String? = null

    @Volatile
    var manualOverrideForPackage: String? = null

    @Volatile
    var currentMode: Mode? = null

    @Volatile
    var rootErrorShown: Boolean = false

    // ---- Text-input override ----

    @Volatile
    var textInputOverrideActive: Boolean = false

    @Volatile
    var modeBeforeTextInput: Mode? = null

    // ---- Hold-key override (multi-hold state) ----
    // activeHoldSlot:
    // 0 = none, 1 = Primary Hold, 2 = Secondary Hold
    @Volatile
    var activeHoldSlot: Int = 0

    // Primary Hold state
    @Volatile
    var hold1Active: Boolean = false

    @Volatile
    var hold1KeyCodeDown: Int? = null

    @Volatile
    var modeBeforeHold1: Mode? = null

    // Secondary Hold state
    @Volatile
    var hold2Active: Boolean = false

    @Volatile
    var hold2KeyCodeDown: Int? = null

    @Volatile
    var modeBeforeHold2: Mode? = null

    // ---- Double-press + hold tracking ----
    @Volatile
    var hold1DoublePressWaiting: Boolean = false

    @Volatile
    var hold1FirstTapUpUptime: Long = 0L

    @Volatile
    var hold2DoublePressWaiting: Boolean = false

    @Volatile
    var hold2FirstTapUpUptime: Long = 0L

    // ---- Backwards-compatible aliases ----
    // These map to Primary Hold naming.

    var holdKeyActive: Boolean
        get() = hold1Active
        set(value) { hold1Active = value }

    var holdKeyCodeDown: Int?
        get() = hold1KeyCodeDown
        set(value) { hold1KeyCodeDown = value }

    var modeBeforeHoldKey: Mode?
        get() = modeBeforeHold1
        set(value) { modeBeforeHold1 = value }
}