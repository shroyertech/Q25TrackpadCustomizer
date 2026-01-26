package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports/imports app SharedPreferences as a JSON backup.
 *
 * v1 backups: root["prefs"] = array of entries for q25_prefs only
 * v2 backups: root["prefs_files"] = [{ name, prefs: [...] }, ...]
 */
object SettingsBackup {

    private const val PREFS_NAME_PRIMARY = "q25_prefs"
    private const val BACKUP_VERSION = 2

    private const val LEGACY_APP_ID = "com.shroyer.tech.q25trackpadmodetoggler"

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("backup_version", BACKUP_VERSION)
        root.put("app_id", context.packageName)

        val prefsFiles = JSONArray()

        // Export ALL shared_prefs/*.xml so globals can’t “hide” in a different file.
        val names = listAllPrefsNames(context)

        for (name in names) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefsFiles.put(
                JSONObject()
                    .put("name", name)
                    .put("prefs", exportPrefsArray(sp))
            )
        }

        root.put("prefs_files", prefsFiles)
        return root.toString()
    }

    /**
     * Restores settings from JSON.
     * Returns false without modifying prefs if validation or parsing fails.
     */
    fun restoreFromJson(context: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)

            if (!root.has("backup_version")) return false
            val version = root.getInt("backup_version")
            if (version != 1 && version != 2) return false

            val appId = root.optString("app_id", "")
            if (appId.isNotEmpty()) {
                val current = context.packageName
                if (appId != current && appId != LEGACY_APP_ID) return false
            }

            // Parse first so restore is all-or-nothing across all prefs files.
            val pendingByFile = LinkedHashMap<String, MutableList<PendingEntry>>()

            if (version == 1) {
                if (!root.has("prefs")) return false
                val prefsArray = root.getJSONArray("prefs")
                val targetName = PREFS_NAME_PRIMARY
                pendingByFile[targetName] = parsePrefsArray(prefsArray)
            } else {
                if (!root.has("prefs_files")) return false
                val files = root.getJSONArray("prefs_files")

                for (i in 0 until files.length()) {
                    val fileObj = files.getJSONObject(i)
                    if (!fileObj.has("prefs")) continue

                    val nameInBackup = fileObj.optString("name", "")
                    val prefsArray = fileObj.getJSONArray("prefs")

                    val targetName = mapPrefsFileName(nameInBackup, context.packageName)
                    pendingByFile[targetName] = parsePrefsArray(prefsArray)
                }

                if (pendingByFile.isEmpty()) return false
            }

            // Apply (clear + restore) each prefs file.
            // Use commit() for deterministic ordering and to avoid timing edge cases.
            for ((prefsName, pending) in pendingByFile) {
                val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val editor = sp.edit()
                editor.clear()

                for (p in pending) {
                    when (p) {
                        is PendingEntry.Bool -> editor.putBoolean(p.key, p.value)
                        is PendingEntry.IntVal -> editor.putInt(p.key, p.value)
                        is PendingEntry.LongVal -> editor.putLong(p.key, p.value)
                        is PendingEntry.FloatVal -> editor.putFloat(p.key, p.value)
                        is PendingEntry.StringVal -> editor.putString(p.key, p.value)
                        is PendingEntry.StringSet -> editor.putStringSet(p.key, p.value)
                    }
                }

                if (!editor.commit()) return false
            }

            AppState.rootErrorShown = false
            NotificationHelper.clearRootError(context)

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun listAllPrefsNames(context: Context): List<String> {
        val names = LinkedHashSet<String>()

        // Always include the known ones.
        names.add(PREFS_NAME_PRIMARY)
        names.add("${context.packageName}_preferences")

        // Include any other prefs files the app has created.
        try {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    val n = f.name
                    if (f.isFile && n.endsWith(".xml")) {
                        names.add(n.removeSuffix(".xml"))
                    }
                }
            }
        } catch (_: Exception) {
            // best-effort
        }

        return names.toList().sorted()
    }

    private fun exportPrefsArray(sp: android.content.SharedPreferences): JSONArray {
        val prefsArray = JSONArray()

        for ((key, value) in sp.all) {
            val entry = JSONObject()
            entry.put("key", key)

            when (value) {
                is Boolean -> {
                    entry.put("type", "bool")
                    entry.put("value", value)
                }
                is Int -> {
                    entry.put("type", "int")
                    entry.put("value", value)
                }
                is Long -> {
                    entry.put("type", "long")
                    entry.put("value", value)
                }
                is Float -> {
                    entry.put("type", "float")
                    entry.put("value", value.toDouble())
                }
                is String -> {
                    entry.put("type", "string")
                    entry.put("value", value)
                }
                is Set<*> -> {
                    // Only supports string sets.
                    if (!value.all { it is String }) continue

                    entry.put("type", "string_set")
                    val arr = JSONArray()
                    value.forEach { v -> arr.put(v as String) }
                    entry.put("value", arr)
                }
                else -> {
                    // Unsupported type
                    continue
                }
            }

            prefsArray.put(entry)
        }

        return prefsArray
    }

    private fun parsePrefsArray(prefsArray: JSONArray): MutableList<PendingEntry> {
        val pending = ArrayList<PendingEntry>(prefsArray.length())

        for (i in 0 until prefsArray.length()) {
            val entry = prefsArray.getJSONObject(i)

            val key = entry.getString("key")
            val type = entry.getString("type")

            when (type) {
                "bool" -> pending.add(PendingEntry.Bool(key, entry.getBoolean("value")))
                "int" -> pending.add(PendingEntry.IntVal(key, entry.getInt("value")))
                "long" -> pending.add(PendingEntry.LongVal(key, entry.getLong("value")))
                "float" -> pending.add(PendingEntry.FloatVal(key, entry.getDouble("value").toFloat()))
                "string" -> pending.add(PendingEntry.StringVal(key, entry.getString("value")))
                "string_set" -> {
                    val arr = entry.getJSONArray("value")
                    val set = mutableSetOf<String>()
                    for (j in 0 until arr.length()) {
                        set.add(arr.getString(j))
                    }
                    pending.add(PendingEntry.StringSet(key, set))
                }
                else -> {
                    // Unknown types are ignored for forward compatibility.
                }
            }
        }

        return pending
    }

    /**
     * Map a prefs file name from a backup (possibly from an older package) into the current app.
     */
    private fun mapPrefsFileName(nameInBackup: String, currentPackageName: String): String {
        if (nameInBackup.isBlank()) return PREFS_NAME_PRIMARY
        if (nameInBackup == PREFS_NAME_PRIMARY) return PREFS_NAME_PRIMARY

        // Default prefs from any package end in "_preferences" - map to current package's default prefs.
        if (nameInBackup.endsWith("_preferences")) {
            return "${currentPackageName}_preferences"
        }

        // If a prefs name somehow includes the legacy package, remap it.
        if (nameInBackup.startsWith(LEGACY_APP_ID)) {
            return nameInBackup.replaceFirst(LEGACY_APP_ID, currentPackageName)
        }

        // Unknown/other: restore into the same-named file (best-effort).
        return nameInBackup
    }

    private sealed class PendingEntry(open val key: String) {
        data class Bool(override val key: String, val value: Boolean) : PendingEntry(key)
        data class IntVal(override val key: String, val value: Int) : PendingEntry(key)
        data class LongVal(override val key: String, val value: Long) : PendingEntry(key)
        data class FloatVal(override val key: String, val value: Float) : PendingEntry(key)
        data class StringVal(override val key: String, val value: String) : PendingEntry(key)
        data class StringSet(override val key: String, val value: Set<String>) : PendingEntry(key)
    }
}