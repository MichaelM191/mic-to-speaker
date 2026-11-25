package com.micspeaker.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer build, downloads it, and opens the installer. */
object Updater {
    data class Info(val versionName: String, val versionCode: Int, val apkUrl: String)

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) return@runCatching null
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name").removePrefix("v")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val assets = json.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url != null && code > BuildConfig.VERSION_CODE) Info(tag, code, url) else null
        }.getOrNull()
    }

    suspend fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "MicToSpeaker-${info.versionName}.apk")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    output.write(buf, 0, r)
                    done += r
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        file
    }

    /** Returns false if the user first has to allow installs from this app. */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
