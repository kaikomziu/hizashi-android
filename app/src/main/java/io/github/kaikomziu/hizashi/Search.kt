package io.github.kaikomziu.hizashi

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

// 検索エンジン・アドレスバーのショートカット・あいまい検索・計算。Windows 版（Util.cs）と同じ仕様。
object Search {
    val engines: LinkedHashMap<String, Pair<String, String>> = linkedMapOf(
        "google" to ("Google" to "https://www.google.com/search?q=%s"),
        "duckduckgo" to ("DuckDuckGo" to "https://duckduckgo.com/?q=%s"),
        "bing" to ("Bing" to "https://www.bing.com/search?q=%s"),
        "yahoo" to ("Yahoo! JAPAN" to "https://search.yahoo.co.jp/search?p=%s"),
        "brave" to ("Brave Search" to "https://search.brave.com/search?q=%s"),
    )

    val bangs: Map<String, String> = linkedMapOf(
        "g" to "https://www.google.com/search?q=%s",
        "ddg" to "https://duckduckgo.com/?q=%s",
        "bing" to "https://www.bing.com/search?q=%s",
        "yt" to "https://www.youtube.com/results?search_query=%s",
        "gh" to "https://github.com/search?q=%s",
        "w" to "https://ja.wikipedia.org/w/index.php?search=%s",
        "wen" to "https://en.wikipedia.org/w/index.php?search=%s",
        "map" to "https://www.google.com/maps/search/%s",
        "img" to "https://www.google.com/search?tbm=isch&q=%s",
        "amz" to "https://www.amazon.co.jp/s?k=%s",
        "nico" to "https://www.nicovideo.jp/search/%s",
        "x" to "https://x.com/search?q=%s",
        "tr" to "https://translate.google.com/?sl=auto&tl=ja&text=%s&op=translate",
        "so" to "https://stackoverflow.com/search?q=%s",
        "mdn" to "https://developer.mozilla.org/ja/search?q=%s",
        "npm" to "https://www.npmjs.com/search?q=%s",
        "r" to "https://www.reddit.com/search/?q=%s",
    )

    private val schemeRe = Regex("^(https?|about):", RegexOption.IGNORE_CASE)
    private val localhostRe = Regex("^localhost(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    private val ipRe = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
    private val domainRe = Regex("^[^\\s./]+\\.[^\\s]{2,}(/.*)?$")
    private val bangRe = Regex("^!?(\\S+)\\s+(.+)$")

    private fun encode(s: String): String = android.net.Uri.encode(s)

    fun engineName(id: String): String = (engines[id] ?: engines.getValue("google")).first

    fun searchUrl(q: String, engineId: String): String =
        (engines[engineId] ?: engines.getValue("google")).second.replace("%s", encode(q))

    fun looksLikeUrl(s: String): Boolean {
        if (schemeRe.containsMatchIn(s)) return true
        if (localhostRe.matches(s)) return true
        if (ipRe.matches(s)) return true
        return domainRe.matches(s) && !s.contains(' ')
    }

    // 入力欄の文字列を、開くURLに変換する（空なら null）
    fun toUrl(input: String, engineId: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (schemeRe.containsMatchIn(s)) return s
        val m = bangRe.find(s)
        if (m != null) {
            val key = m.groupValues[1].lowercase()
            val tmpl = bangs[key]
            if (tmpl != null) return tmpl.replace("%s", encode(m.groupValues[2]))
        }
        if (looksLikeUrl(s)) {
            val local = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})", RegexOption.IGNORE_CASE).containsMatchIn(s)
            return (if (local) "http://" else "https://") + s
        }
        return searchUrl(s, engineId)
    }

    fun isLocalHost(host: String): Boolean =
        !host.contains('.') ||
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) ||
            Regex("\\.(local|localhost|test|internal|lan)$", RegexOption.IGNORE_CASE).containsMatchIn(host)

    // ---------------------------------------------------------------- あいまい検索
    // あいまい一致のスコア（0 = 不一致）
    fun fuzzy(q0: String, text0: String): Double {
        val q = q0.lowercase()
        val text = text0.lowercase()
        if (q.isEmpty()) return 1.0
        val i = text.indexOf(q)
        if (i >= 0) return 1000.0 - i - (text.length - q.length) * 0.1
        var qi = 0
        var last = -2
        var score = 0.0
        var ti = 0
        while (ti < text.length && qi < q.length) {
            if (text[ti] == q[qi]) {
                score += if (ti == last + 1) 5.0 else 1.0
                last = ti
                qi++
            }
            ti++
        }
        return if (qi == q.length) score else 0.0
    }

    // 空白区切りの単語がすべて一致したときだけスコアを返す
    fun fuzzyAll(query: String, text: String): Double {
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return 1.0
        var total = 0.0
        for (w in words) {
            val s = fuzzy(w, text)
            if (s == 0.0) return 0.0
            total += s
        }
        return total
    }

    // ---------------------------------------------------------------- 計算（+ - * / % ^ と括弧）
    fun tryEval(s: String): String? {
        if (!Regex("^[\\d\\s+\\-*/().%^]+$").matches(s)) return null
        if (!Regex("[+\\-*/%^]").containsMatchIn(s) || !Regex("\\d").containsMatchIn(s)) return null
        return try {
            val p = MathParser(s)
            val v = p.parseExpr()
            if (!p.atEnd() || v.isNaN() || v.isInfinite()) return null
            if (v % 1.0 == 0.0 && abs(v) < 1e15) v.toLong().toString()
            else String.format(Locale.ROOT, "%.10f", v).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    private class MathParser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun atEnd(): Boolean { skip(); return i >= s.length }
        fun eat(c: Char): Boolean {
            skip()
            if (i < s.length && s[i] == c) { i++; return true }
            return false
        }

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                if (eat('+')) v += parseTerm()
                else if (eat('-')) v -= parseTerm()
                else return v
            }
        }

        fun parseTerm(): Double {
            var v = parsePow()
            while (true) {
                if (eat('*')) v *= parsePow()
                else if (eat('/')) v /= parsePow()
                else if (eat('%')) v %= parsePow()
                else return v
            }
        }

        fun parsePow(): Double {
            val b = parseUnary()
            return if (eat('^')) b.pow(parsePow()) else b // 右結合
        }

        fun parseUnary(): Double {
            if (eat('-')) return -parseUnary()
            if (eat('+')) return parseUnary()
            return parseAtom()
        }

        fun parseAtom(): Double {
            if (eat('(')) {
                val v = parseExpr()
                if (!eat(')')) throw IllegalArgumentException()
                return v
            }
            skip()
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return s.substring(st, i).toDouble()
        }
    }
}
