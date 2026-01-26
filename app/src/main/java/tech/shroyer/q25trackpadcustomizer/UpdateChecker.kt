package tech.shroyer.q25trackpadcustomizer

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Simple GitHub Releases "latest" checker (manual use)
 * Uses GitHub API: /releases/latest URL builder
 */
object UpdateChecker {

    private const val OWNER = "shroyertech"
    private const val REPO = "Q25TrackpadCustomizer"

    // Stable web URLs (no API call needed)
    const val REPO_WEB_URL = "https://github.com/$OWNER/$REPO"
    const val LATEST_RELEASE_WEB_URL = "$REPO_WEB_URL/releases/latest"

    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        val apkUrl: String?
    )

    sealed class Result {
        data class Success(val release: ReleaseInfo) : Result()
        data class Error(val message: String) : Result()
    }

    fun fetchLatestRelease(): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Q25TrackpadCustomizer")
            }

            val code = conn.responseCode

            val stream = try {
                if (code in 200..299) conn.inputStream else conn.errorStream
            } catch (_: Exception) {
                null
            }

            val body = stream?.let { s ->
                BufferedReader(InputStreamReader(s)).use { it.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                val msg = buildString {
                    append("GitHub API error ($code)")
                    val detail = extractMessageFromGithubError(body)
                    if (!detail.isNullOrBlank()) append(": ").append(detail)
                }
                return Result.Error(msg)
            }

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").ifBlank { json.optString("name", "") }
            val htmlUrl = json.optString("html_url", "")
            val assets = json.optJSONArray("assets") ?: JSONArray()

            val apkUrl = findApkAssetUrl(assets)

            if (tag.isBlank() || htmlUrl.isBlank()) {
                return Result.Error("Latest release data was missing tag_name or html_url.")
            }

            Result.Success(
                ReleaseInfo(
                    tagName = tag,
                    htmlUrl = htmlUrl,
                    apkUrl = apkUrl
                )
            )
        } catch (e: Exception) {
            Result.Error("Failed to check updates: ${e.message ?: "unknown error"}")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Compare installed vs latest tag/version.
     * Returns:
     *  -1 if installed < latest
     *   0 if equal
     *   1 if installed > latest (for time travellers)
     */
    fun compareVersions(installed: String, latestTag: String): Int {
        val a = parseVersionParts(installed)
        val b = parseVersionParts(latestTag)

        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = if (i < a.size) a[i] else 0
            val bi = if (i < b.size) b[i] else 0
            if (ai < bi) return -1
            if (ai > bi) return 1
        }
        return 0
    }

    fun normalizeForDisplay(versionOrTag: String): String {
        val raw = versionOrTag.trim()
        return if (raw.lowercase(Locale.US).startsWith("v")) raw else "v$raw"
    }

    private fun parseVersionParts(input: String): List<Int> {
        // Normalize release versions in case future me is inconsistent
        val s0 = input.trim()
            .removePrefix("v")
            .removePrefix("V")

        // Strip suffixes
        val s1 = s0.split('-', '+', ' ').firstOrNull().orEmpty()

        // Keep digits and dots only (defensive)
        val cleaned = s1.filter { it.isDigit() || it == '.' }
        if (cleaned.isBlank()) return listOf(0)

        return cleaned
            .split('.')
            .filter { it.isNotBlank() }
            .mapNotNull { part -> part.toIntOrNull() }
            .ifEmpty { listOf(0) }
    }

    private fun findApkAssetUrl(assets: JSONArray): String? {
        // Prefer an .apk asset first always
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                return url
            }
        }
        // Fallback: any asset with a download URL
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url", "")
            if (url.isNotBlank()) return url
        }
        return null
    }

    private fun extractMessageFromGithubError(body: String): String? {
        return try {
            val j = JSONObject(body)
            j.optString("message", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}