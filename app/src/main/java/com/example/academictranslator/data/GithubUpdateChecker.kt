package com.example.academictranslator.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GithubRelease(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
    val notes: String
)

/** 读取项目公开 GitHub Release；不上传用户设备或配置数据。 */
object GithubUpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/serendip215-cell/academic-translator-android/releases/latest"

    fun latestRelease(): GithubRelease? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AcademicTranslator-Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            for (index in 0 until (assets?.length() ?: 0)) {
                val asset = assets?.optJSONObject(index) ?: continue
                val url = asset.optString("browser_download_url")
                if (url.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = url
                    break
                }
            }
            GithubRelease(
                version = json.optString("tag_name").removePrefix("v"),
                releaseUrl = json.optString("html_url"),
                apkUrl = apkUrl,
                notes = json.optString("body").trim()
            )
        } finally {
            connection.disconnect()
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val count = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until count) {
            val r = remoteParts.getOrElse(index) { 0 }
            val l = localParts.getOrElse(index) { 0 }
            if (r != l) return r > l
        }
        return false
    }
}
