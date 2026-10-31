package io.github.kaikomziu.hizashi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)
data class HistoryItem(val title: String, val url: String, val time: Long)

// 設定・ブックマーク・履歴・サイトごとの設定を、端末の中（SharedPreferences）に保存する。
// どこにも送信しない。
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hizashi", Context.MODE_PRIVATE)
    private var sites: JSONObject = parseObj(sp.getString("sites", null))

    private fun parseObj(s: String?): JSONObject = try { JSONObject(s ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun parseArr(s: String?): JSONArray = try { JSONArray(s ?: "[]") } catch (e: Exception) { JSONArray() }

    // ---------------------------------------------------------------- 設定
    var engine: String
        get() = sp.getString("engine", "google") ?: "google"
        set(v) { sp.edit().putString("engine", v).apply() }

    var adblock: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) { sp.edit().putBoolean("adblock", v).apply() }

    var httpsUpgrade: Boolean
        get() = sp.getBoolean("httpsUpgrade", true)
        set(v) { sp.edit().putBoolean("httpsUpgrade", v).apply() }

    var stripParams: Boolean
        get() = sp.getBoolean("stripParams", true)
        set(v) { sp.edit().putBoolean("stripParams", v).apply() }

    var forceDarkAll: Boolean
        get() = sp.getBoolean("forceDarkAll", false)
        set(v) { sp.edit().putBoolean("forceDarkAll", v).apply() }

    // 集中モードでブロックするサイト（1行に1つ）
    var focusSites: String
        get() = sp.getString("focusSites", DEFAULT_FOCUS_SITES) ?: DEFAULT_FOCUS_SITES
        set(v) { sp.edit().putString("focusSites", v).apply() }

    // 集中モードの終了時刻（エポックミリ秒。0 = 動いていない）
    var focusUntil: Long
        get() = sp.getLong("focusUntil", 0L)
        set(v) { sp.edit().putLong("focusUntil", v).apply() }

    // サイトに許可した権限などの記憶
    fun flag(key: String): Boolean = sp.getBoolean("flag:$key", false)
    fun setFlag(key: String, v: Boolean) { sp.edit().putBoolean("flag:$key", v).apply() }

    // ---------------------------------------------------------------- サイトごとの設定
    // dark(Boolean) / adOff(Boolean) / zoom(Int, %) / hidden(JSONArray of selector)
    fun site(host: String): JSONObject = sites.optJSONObject(host) ?: JSONObject()

    fun saveSite(host: String, o: JSONObject) {
        if (o.length() == 0) sites.remove(host) else sites.put(host, o)
        sp.edit().putString("sites", sites.toString()).apply()
    }

    fun addHidden(host: String, selector: String) {
        val o = site(host)
        val arr = o.optJSONArray("hidden") ?: JSONArray()
        for (i in 0 until arr.length()) if (arr.optString(i) == selector) return
        arr.put(selector)
        o.put("hidden", arr)
        saveSite(host, o)
    }

    // ---------------------------------------------------------------- ブックマーク
    fun bookmarks(): MutableList<Bookmark> {
        val a = parseArr(sp.getString("bookmarks", null))
        val l = ArrayList<Bookmark>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            l.add(Bookmark(o.optString("t"), o.optString("u")))
        }
        return l
    }

    private fun saveBookmarks(l: List<Bookmark>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        sp.edit().putString("bookmarks", a.toString()).apply()
    }

    fun isBookmarked(url: String): Boolean = bookmarks().any { it.url == url }

    // 追加したら true、解除したら false
    fun toggleBookmark(title: String, url: String): Boolean {
        val l = bookmarks()
        val idx = l.indexOfFirst { it.url == url }
        return if (idx >= 0) {
            l.removeAt(idx); saveBookmarks(l); false
        } else {
            l.add(0, Bookmark(title.ifBlank { url }, url)); saveBookmarks(l); true
        }
    }

    // ---------------------------------------------------------------- 履歴（新しい順・最大2000件）
    fun history(): MutableList<HistoryItem> {
        val a = parseArr(sp.getString("history", null))
        val l = ArrayList<HistoryItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            l.add(HistoryItem(o.optString("t"), o.optString("u"), o.optLong("d")))
        }
        return l
    }

    private fun saveHistory(l: List<HistoryItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        sp.edit().putString("history", a.toString()).apply()
    }

    fun addHistory(title: String, url: String) {
        val l = history()
        if (l.isNotEmpty() && l[0].url == url) l.removeAt(0)
        l.add(0, HistoryItem(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (l.size > 2000) l.removeAt(l.size - 1)
        saveHistory(l)
    }

    fun clearHistory() { sp.edit().remove("history").apply() }

    // ---------------------------------------------------------------- 前回のタブ
    fun saveSession(urls: List<Pair<String, String>>, index: Int) {
        val a = JSONArray()
        urls.forEach { a.put(JSONObject().put("u", it.first).put("n", it.second)) }
        sp.edit().putString("session", JSONObject().put("i", index).put("t", a).toString()).apply()
    }

    fun loadSession(): Pair<List<Pair<String, String>>, Int>? {
        val o = parseObj(sp.getString("session", null))
        val a = o.optJSONArray("t") ?: return null
        val l = ArrayList<Pair<String, String>>()
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            l.add(t.optString("u") to t.optString("n"))
        }
        if (l.isEmpty()) return null
        return l to o.optInt("i", 0).coerceIn(0, l.size - 1)
    }

    companion object {
        const val DEFAULT_FOCUS_SITES =
            "youtube.com\nx.com\ntwitter.com\nreddit.com\ntwitch.tv\nnicovideo.jp\ntiktok.com\ninstagram.com\nfacebook.com"
    }
}
