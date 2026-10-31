package io.github.kaikomziu.hizashi

import android.net.Uri

// 広告・トラッカーのドメイン一覧（サフィックス一致で判定）と、URLの追跡パラメータ除去。
// Windows 版（Blocklist.cs）と同じ内容。
object Blocklist {
    val adHosts: Set<String> = setOf(
        // Google 系の広告・計測
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "google-analytics.com", "adservice.google.com", "pagead2.googlesyndication.com", "2mdn.net",
        "admob.com", "adsense.com",
        // 大手アドネットワーク
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "adsrvr.org", "advertising.com", "moatads.com",
        "amazon-adsystem.com", "teads.tv", "smartadserver.com", "casalemedia.com", "bidswitch.net",
        "contextweb.com", "lijit.com", "sharethrough.com", "33across.com", "mathtag.com",
        "adform.net", "adroll.com", "yieldmo.com", "indexww.com", "media.net", "revcontent.com",
        "mgid.com", "zemanta.com", "adcolony.com", "applovin.com", "inmobi.com", "smaato.net",
        "tapad.com", "serving-sys.com", "adition.com", "adtechus.com", "yahoo-ads.com",
        "ads.yahoo.com", "gemini.yahoo.com",
        // 計測・トラッキング
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "hotjar.com", "hotjar.io",
        "mixpanel.com", "segment.io", "segment.com", "bluekai.com", "krxd.net", "demdex.net",
        "everesttech.net", "omtrdc.net", "chartbeat.com", "chartbeat.net", "fullstory.com",
        "mouseflow.com", "crazyegg.com", "luckyorange.com", "clarity.ms", "branch.io",
        "adjust.com", "appsflyer.com", "kochava.com", "singular.net", "bat.bing.com",
        "ads.linkedin.com", "analytics.tiktok.com", "ads-twitter.com", "analytics.twitter.com",
        "px.ads.linkedin.com",
        // 日本の広告ネットワーク
        "i-mobile.co.jp", "microad.jp", "microad.net", "adingo.jp", "ad-stir.com", "impact-ad.jp",
        "nend.net", "fout.jp", "fluct.jp", "logly.co.jp", "popin.cc", "adlantis.jp", "ad-m.asia",
        "adnetwork.jp", "adk2.com", "adtdp.com", "danet.jp", "socdm.com", "yads.c.yimg.jp",
        "ads.yimg.jp", "tg.socdm.com", "gssprt.jp", "dable.io", "yjtag.yahoo.co.jp", "shinobi.jp",
        // その他
        "ads.pubmatic.com", "adsafeprotected.com", "iasds01.com", "doubleverify.com", "zedo.com",
        "exoclick.com", "popads.net", "propellerads.com", "popcash.net", "juicyads.com",
        "trafficjunky.net", "adsterra.com", "clickadu.com",
    )

    // ホスト名が広告ドメイン（またはそのサブドメイン）か
    fun isAdHost(host: String?): Boolean {
        var h = (host ?: "").lowercase()
        while (h.isNotEmpty()) {
            if (h in adHosts) return true
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        return false
    }

    private val trackingParams: Set<String> = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "utm_name", "utm_brand", "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "yclid",
        "mc_cid", "mc_eid", "igshid", "_hsenc", "_hsmi", "ref_src", "ref_url", "twclid",
        "ttclid", "wbraid", "gbraid", "vero_id", "mkt_tok", "oly_enc_id", "oly_anon_id",
        "__s", "rb_clickid", "s_cid", "trk", "trkcampaign",
    )

    // 追跡パラメータを取り除いたURLを返す（変化がなければ元の文字列をそのまま返す）。
    // 残すパラメータは元のエンコードのまま保つ。
    fun cleanTrackingUrl(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val hash = url.indexOf('#')
        val frag = if (hash >= 0) url.substring(hash) else ""
        val noFrag = if (hash >= 0) url.substring(0, hash) else url
        val q = noFrag.indexOf('?')
        if (q < 0 || q == noFrag.length - 1) return url
        val base = noFrag.substring(0, q)
        val parts = noFrag.substring(q + 1).split('&').filter { it.isNotEmpty() }
        val keep = parts.filter { p ->
            val name = try {
                Uri.decode(p.substringBefore('=')).lowercase()
            } catch (e: Exception) {
                p.substringBefore('=').lowercase()
            }
            name !in trackingParams
        }
        if (keep.size == parts.size) return url
        return base + (if (keep.isNotEmpty()) "?" + keep.joinToString("&") else "") + frag
    }
}
