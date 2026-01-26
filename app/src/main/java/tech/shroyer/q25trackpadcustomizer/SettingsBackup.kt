package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports all app SharedPreferences as a JSON backup.
 */
object SettingsBackup {

    private const val PREFS_NAME = "q25_prefs"
    private const val BACKUP_VERSION = 1

    private const val LEGACY_APP_ID = "com.shroyer.tech.q25trackpadmodetoggler"

    fun exportToJson(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val root = JSONObject()
        root.put("backup_version", BACKUP_VERSION)
        root.put("app_id", context.packageName)

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

        root.put("prefs", prefsArray)
        return root.toString()
    }

    /**
     * Restores settings from JSON.
     * Returns false without modifying prefs if validation or parsing fails.
     */
    fun restoreFromJson(context: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)

            if (!root.has("backup_version") || !root.has("prefs")) return false
            if (root.getInt("backup_version") != BACKUP_VERSION) return false

            val appId = root.optString("app_id", "")
            if (appId.isNotEmpty()) {
                val current = context.packageName
                if (appId != current && appId != LEGACY_APP_ID) return false
            }

            val prefsArray = root.getJSONArray("prefs")

            // Parse first so restore is all-or-nothing.
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

            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

            editor.apply()

            AppState.rootErrorShown = false
            NotificationHelper.clearRootError(context)

            true
        } catch (_: Exception) {
            false
        }
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