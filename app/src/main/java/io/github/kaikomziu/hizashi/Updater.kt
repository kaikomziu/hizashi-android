package io.github.kaikomziu.hizashi

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// GitHub のリリースを見て、新しい版があれば APK をダウンロードする（インストールは Android の画面で行う）。
// 通信は「更新を確認」を押したときだけ。自動では何も送らない。
// どの関数も時間がかかるので、UI スレッドでは呼ばないこと。
object Updater {
    const val REPO = "kaikomziu/hizashi-android"
    private const val APK_PREFIX = "https://github.com/$REPO/releases/download/"

    class Release(
        val tag: String,
        val version: List<Int>,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String,
        val apkName: String,
        val sha256: String,
    ) {
        val versionName: String get() = version.joinToString(".")
    }

    fun parseVersion(s: String): List<Int>? {
        val parts = s.trim().trimStart('v', 'V').split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    // a が b より新しいか（桁が足りないところは 0 として比べる）
    fun isNewer(a: List<Int>, b: List<Int>): Boolean {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Hizashi-Android")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    private fun readText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // 最新のリリースを返す。試験版（pre-release）も対象にするため、/releases/latest ではなく一覧を見る
    fun fetchLatest(): Release {
        val arr = JSONArray(readText("https://api.github.com/repos/$REPO/releases?per_page=10"))
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.optString("tag_name")
            val ver = parseVersion(tag) ?: continue
            var apkUrl: String? = null
            var apkName = ""
            var sumsUrl: String? = null
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk")) { apkUrl = url; apkName = name }
                else if (name == "SHA256SUMS.txt") sumsUrl = url
            }
            if (apkUrl == null || !apkUrl.startsWith(APK_PREFIX)) continue

            // SHA-256 は、リリース本文の「SHA-256: `…`」か SHA256SUMS.txt から読む。無ければ更新しない
            val body = r.optString("body")
            var hash = Regex("SHA-256:\\s*`?([0-9a-fA-F]{64})`?").find(body)?.groupValues?.get(1)
            if (hash == null && sumsUrl != null && sumsUrl.startsWith(APK_PREFIX)) {
                hash = Regex("([0-9a-fA-F]{64})\\s+\\*?" + Regex.escape(apkName)).find(readText(sumsUrl))?.groupValues?.get(1)
            }
            if (hash == null) continue

            val rel = Release(tag, ver, body, r.optString("html_url"), apkUrl, apkName, hash.lowercase())
            if (best == null || isNewer(rel.version, best.version)) best = rel
        }
        return best ?: throw IllegalStateException("更新できるリリースが見つかりません")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ダウンロードして SHA-256 を照合する。すでに同じファイルがあれば、そのまま使う。
    // 一致しなければファイルを消して例外にする。
    fun download(rel: Release, dest: File, onProgress: (Int) -> Unit) {
        if (dest.exists() && sha256(dest) == rel.sha256) {
            onProgress(100)
            return
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        try {
            val c = open(rel.apkUrl)
            try {
                if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; onProgress(p) }
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            if (sha256(part) != rel.sha256) throw IllegalStateException("ダウンロードしたファイルの SHA-256 が一致しません（中止しました）")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IllegalStateException("ファイルを保存できません")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    // リリースノートの箇条書きだけを短くまとめる
    fun summarize(notes: String): String {
        val lines = notes.replace("\r", "").lines().map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { "・" + it.substring(2).replace("`", "").replace("**", "") }
            .take(6)
            .map { if (it.length > 60) it.take(60) + "…" else it }
        return if (lines.isEmpty()) "" else "更新の内容:\n" + lines.joinToString("\n")
    }
}
