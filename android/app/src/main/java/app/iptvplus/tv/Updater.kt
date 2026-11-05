package app.iptvplus.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Holt die neueste Fire-TV-Version aus den GitHub-Releases und installiert sie */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/corecx12/iptv-plus-ios/releases?per_page=30"

    data class Release(val number: Int, val name: String, val url: String, val size: Long)

    fun currentVersion(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    fun versionName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "IPTVPlus-FireTV")
        c.connectTimeout = 15000; c.readTimeout = 30000
        if (c.responseCode != 200) throw ApiException("Update-Server antwortet mit HTTP ${c.responseCode}")
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val tag = r.s("tag_name")
            if (!tag.startsWith("firetv-") || r.optBoolean("draft")) continue
            val n = tag.removePrefix("firetv-").toIntOrNull() ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.s("name").endsWith(".apk") && (best == null || n > best.number)) {
                    best = Release(n, r.s("name"), a.s("browser_download_url"), a.optLong("size"))
                }
            }
        }
        best
    }

    suspend fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // ältere Fire-TV-Systeme (vor Android 7) brauchen eine für den Installer lesbare Ablage
        val base = if (Build.VERSION.SDK_INT >= 24) ctx.cacheDir else (ctx.getExternalFilesDir(null) ?: ctx.cacheDir)
        val dir = File(base, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // nur eigene alte Update-Dateien
        val out = File(dir, "IPTVPlus-FireTV-${r.number}.apk")
        var url = r.url
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "IPTVPlus-FireTV")
            c.connectTimeout = 15000; c.readTimeout = 60000
            val code = c.responseCode
            if (code in 300..399 && hops < 5) { url = c.getHeaderField("Location"); c.disconnect(); hops++; continue }
            if (code != 200) throw ApiException("Download fehlgeschlagen (HTTP $code)")
            break
        }
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
        c.inputStream.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress(pct) } }
                }
            }
        }
        out
    }

    /** true = Installation gestartet; false = erst Erlaubnis "Apps unbekannter Herkunft" nötig (Einstellungen geöffnet) */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk) else Uri.fromFile(apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        return true
    }
}
