package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Works out which root solution is installed, so setup/help text can point the user
 * to the right app.
 *
 * Order of evidence:
 *  1. `su -v`: Magisk, KernelSU and APatch identify themselves in the version string.
 *     This also finds a Magisk app that was hidden/renamed to a random package.
 *  2. Known manager package names (official builds plus common forks).
 *  3. App labels, as a last resort.
 *
 * Blocking (runs a process and queries PackageManager): call off the main thread.
 */
object RootManagerDetector {

    enum class Type(val displayName: String) {
        MAGISK("Magisk"),
        KERNELSU("KernelSU"),
        APATCH("APatch"),
        UNKNOWN("Unknown")
    }

    private val MAGISK_PACKAGES = listOf(
        "com.topjohnwu.magisk",      // Magisk
        "io.github.vvb2060.magisk",  // Magisk Alpha
        "io.github.huskydg.magisk",  // Kitsune / Magisk Delta
    )

    private val KERNELSU_PACKAGES = listOf(
        "me.weishu.kernelsu",        // KernelSU
        "com.rifsxd.ksunext",        // KernelSU Next
        "com.sukisu.ultra",          // SukiSU Ultra
        "vaurmet.nfdvv.ztmdui",
    )

    private val APATCH_PACKAGES = listOf(
        "me.bmax.apatch",            // APatch
    )

    private const val SU_VERSION_TIMEOUT_MS = 2_000L

    fun detect(context: Context): Type {
        fromSuVersion()?.let { return it }

        val pm = context.packageManager
        if (KERNELSU_PACKAGES.any { isInstalled(pm, it) }) return Type.KERNELSU
        if (APATCH_PACKAGES.any { isInstalled(pm, it) }) return Type.APATCH
        if (MAGISK_PACKAGES.any { isInstalled(pm, it) }) return Type.MAGISK

        return fromAppLabels(pm) ?: Type.UNKNOWN
    }

    /**
     * Magisk prints e.g. "27.0:MAGISK:R" for `su -v` without asking for a grant.
     * KernelSU/APatch only expose `su` to apps that were already granted.
     */
    private fun fromSuVersion(): Type? {
        val out = runWithTimeout(arrayOf("su", "-v"), SU_VERSION_TIMEOUT_MS) ?: return null
        val lower = out.lowercase()
        return when {
            "magisk" in lower -> Type.MAGISK
            "kernelsu" in lower || "ksu" in lower -> Type.KERNELSU
            "apatch" in lower -> Type.APATCH
            else -> null
        }
    }

    private fun fromAppLabels(pm: PackageManager): Type? {
        return try {
            val apps = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            var found: Type? = null
            for (app in apps) {
                val label = app.loadLabel(pm).toString().lowercase()
                found = when {
                    "kernelsu" in label -> Type.KERNELSU
                    "apatch" in label -> Type.APATCH
                    "magisk" in label -> Type.MAGISK
                    else -> null
                }
                if (found != null) break
            }
            found
        } catch (_: Exception) {
            null
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun runWithTimeout(cmd: Array<String>, timeoutMs: Long): String? {
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            try { p.outputStream.close() } catch (_: Exception) {}

            var output: String? = null
            val reader = Thread {
                output = try {
                    p.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    null
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMs)
            if (reader.isAlive) {
                p.destroy()
                return null
            }
            output
        } catch (_: Exception) {
            null
        }
    }
}
