/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.kde.kdeconnect_tp.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks this fork's GitHub releases for a newer build, downloads the APK for this device's ABI and hands it to
 * the system installer. Only full (non-prerelease) releases count, i.e. builds of the default branch.
 */
object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/UncleAndy123/kdeconnect-android-dpad/releases/latest"

    data class Update(val version: String, val apkName: String, val apkUrl: String, val size: Long, val notes: String)

    /** The newer release, or null when this build is up to date. Throws on network/parse errors. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val json = JSONObject(httpGet(LATEST_RELEASE_URL))
        // Tags look like v1.37.42-master
        val version = json.getString("tag_name").removePrefix("v").substringBefore('-')
        if (compareVersions(version, BuildConfig.VERSION_NAME.substringBefore('-')) <= 0) return@withContext null
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk") }
        val asset = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            apks.firstOrNull { it.getString("name").endsWith("-$abi.apk") }
        } ?: throw IllegalStateException("No APK for ${Build.SUPPORTED_ABIS.joinToString()}")
        Update(
            version = version,
            apkName = asset.getString("name"),
            apkUrl = asset.getString("browser_download_url"),
            size = asset.optLong("size"),
            notes = json.optString("body"),
        )
    }

    /** Downloads the APK into the cache, reporting progress 0..100. */
    suspend fun download(context: Context, update: Update, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, update.apkName)
        val conn = open(update.apkUrl)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.size
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress(pct) } }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** True when the app may launch the installer; otherwise opens the "install unknown apps" setting. */
    fun ensureInstallAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) return true
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "kdeconnect-android-dpad")
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                throw IllegalStateException("HTTP $code")
            }
        }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
