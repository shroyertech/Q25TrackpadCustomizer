package tech.shroyer.q25trackpadcustomizer

/**
 * Experimental edge auto-scroll: in Mouse or Keyboard mode, reaching the edge of the
 * screen/page scrolls a bit further in that direction.
 */
data class EdgeScrollSettings(
    val enabled: Boolean,
    val up: Boolean,
    val down: Boolean,
    val left: Boolean,
    val right: Boolean,
    val inMouseMode: Boolean,
    val inKeyboardMode: Boolean,
    /** Explicit speed, or null to match the app's scroll wheel sensitivity. */
    val speedOverride: ScrollSensitivity?
) {
    fun allows(dx: Int, dy: Int): Boolean = when {
        dy < 0 -> up
        dy > 0 -> down
        dx < 0 -> left
        dx > 0 -> right
        else -> false
    }

    val anyDirection: Boolean get() = up || down || left || right
}
