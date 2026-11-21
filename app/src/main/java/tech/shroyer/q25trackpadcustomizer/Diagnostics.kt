package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Plain-text report of what this phone supports, for bug reports (Settings → Backup,
 * updates & about → Diagnostics). Blocking: call off the main thread.
 */
object Diagnostics {

    private val SWITCH_FILES = listOf("/proc/q20_switch_key_mouse", "/proc/touchpad/touchpad_mode")

    fun collect(context: Context): String {
        val sb = StringBuilder()
        fun line(k: String, v: String) = sb.append(k).append(": ").append(v).append('\n')

        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        line("App", version)
        line("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("Build", Build.DISPLAY ?: "?")
        line("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("Accessibility service", if (AppSwitchService.instance != null) "running" else "NOT running")
        line("Current mode", AppState.currentMode?.name ?: "unknown")

        RootShell.resetBackoff()
        val root = RootShell.run("id -u")
        val hasRoot = root.ok && root.output.trim().endsWith("0")
        line("Root", if (hasRoot) "yes" else "NO (${root.output.trim().take(60)})")

        // Mode switch file
        val found = SWITCH_FILES.filter { path ->
            File(path).exists() || (hasRoot && RootShell.run("[ -e $path ] && echo y").output.contains("y"))
        }
        if (found.isEmpty()) {
            line("Mode switch file", "NOT FOUND (checked ${SWITCH_FILES.joinToString()})")
        } else {
            for (p in found) {
                val v = if (hasRoot) RootShell.run("cat $p").output.trim() else "?"
                line("Mode switch file", "$p = $v")
            }
        }

        // Trackpad input devices (relative X/Y capable)
        val devs = StringBuilder()
        val dir = File("/sys/class/input")
        for (n in (dir.list() ?: emptyArray()).sorted()) {
            if (!n.startsWith("event")) continue
            val rel = try {
                File(dir, "$n/device/capabilities/rel").readText().trim()
            } catch (_: Exception) {
                continue
            }
            val bits = rel.split(' ').lastOrNull()?.toLongOrNull(16) ?: 0L
            if ((bits and 0x3L) != 0x3L) continue
            val name = try {
                File(dir, "$n/device/name").readText().trim()
            } catch (_: Exception) {
                "?"
            }
            val virt = try {
                File(dir, n).canonicalPath.contains("/virtual/")
            } catch (_: Exception) {
                false
            }
            if (devs.isNotEmpty()) devs.append(", ")
            devs.append("$n \"$name\"").append(if (virt) " (virtual)" else "")
        }
        line("Trackpad devices", if (devs.isEmpty()) "none found" else devs.toString())

        // Native touch-scroll helper
        val helper = File(context.applicationInfo.nativeLibraryDir, "libq25touch.so")
        line("Touch scroll helper", if (helper.exists()) (if (helper.canExecute()) "present" else "present, not executable") else "MISSING")
        if (hasRoot) {
            val uinput = RootShell.run("[ -c /dev/uinput ] && echo y").output.contains("y")
            line("/dev/uinput", if (uinput) "yes" else "NO")
        }
        line("Edge scroll", AppState.edgeScrollStatus)

        val prefs = Prefs(context)
        val scroll = prefs.getGlobalScrollSettings()
        line("Scroll method (global)", "${scroll.method.name}, ${scroll.sensitivity.name}")
        return sb.toString().trimEnd()
    }
}
