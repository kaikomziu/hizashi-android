package io.github.kaikomziu.hizashi

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File

private const val INTERNAL_HOST = "hizashi.invalid"
private const val NEWTAB_URL = "https://hizashi.invalid/newtab.html"
private const val FOCUS_URL = "https://hizashi.invalid/focus.html"
private const val REPO_URL = "https://github.com/kaikomziu/hizashi-android"
private const val SITE_URL = "https://kaikomziu.github.io/hizashi/"

private val BG = Color.parseColor("#14211A")
private val BAR = Color.parseColor("#1B2B22")
private val FIELD = Color.parseColor("#2A4132")
private val TEXT = Color.parseColor("#E8F0EA")
private val SUB = Color.parseColor("#8FA597")
private val ACCENT = Color.parseColor("#9CD68A")

private class Tab {
    var url: String = ""
    var title: String = ""
    var web: WebView? = null

    @Volatile var host: String = ""
    @Volatile var adCount: Int = 0
    @Volatile var adOff: Boolean = false
    var upgradedFrom: String? = null
    var desktop: Boolean = false
    var progress: Int = 100
}

private class PItem(val title: String, val sub: String, val score: Double, val run: () -> Unit)
private class Cmd(val title: String, val hint: String, val run: () -> Unit)

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private val closedUrls = ArrayList<String>()
    private val httpOnlyHosts = HashSet<String>()
    private var lastFocusBlocked = ""
    private var pickerGen = 0
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var content: FrameLayout
    private lateinit var address: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var shieldBtn: TextView
    private lateinit var reloadBtn: TextView
    private lateinit var tabsBtn: TextView
    private lateinit var focusChip: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permCallback: ((Map<String, Boolean>) -> Unit)? = null
    private var mobileUa = ""
    private var desktopUa = ""

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { m ->
        permCallback?.invoke(m)
        permCallback = null
    }

    // アプリ内のページ（新しいタブ・集中モードの画面）を https://hizashi.invalid/ で配信する
    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(INTERNAL_HOST)
            .addPathHandler("/", WebViewAssetLoader.PathHandler { path -> internalPage(path) })
            .build()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateFocusChip()
            ui.postDelayed(this, 1000)
        }
    }

    private val commands: List<Cmd> by lazy { buildCommands() }

    // ================================================================ 起動
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        File(cacheDir, "updates").deleteRecursively() // 前回の更新用ファイルを片付ける

        val base = WebSettings.getDefaultUserAgent(this)
        // 「; wv」と「Version/4.0」を外して、Chrome の名乗りに揃える
        mobileUa = base.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        val ver = Regex("Chrome/([\\d.]+)").find(base)?.groupValues?.get(1) ?: "120.0.0.0"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"

        setContentView(buildUi())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        val session = store.loadSession()
        if (session != null) {
            session.first.forEach { (u, n) ->
                val t = Tab()
                t.url = u
                t.title = n
                t.host = Uri.parse(u).host ?: ""
                tabs.add(t)
            }
            show(tabs[session.second])
        } else {
            newTab()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i == null) return false
        when (i.action) {
            Intent.ACTION_VIEW -> {
                val d = i.data ?: return false
                newTab(d.toString())
                return true
            }
            Intent.ACTION_SEND -> {
                val s = i.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                newTab(Search.toUrl(s.trim(), store.engine))
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.web?.onResume() }
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        saveSession()
        CookieManager.getInstance().flush()
        tabs.forEach { it.web?.onPause() }
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        tabs.forEach { it.web?.destroy() }
        super.onDestroy()
    }

    private fun saveSession() {
        if (tabs.isEmpty()) return
        val l = tabs.map { t ->
            val u = if (t.url.isBlank() || t.url == FOCUS_URL) NEWTAB_URL else t.url
            u to t.title
        }
        store.saveSession(l, tabs.indexOf(cur).coerceAtLeast(0))
    }

    // ================================================================ 画面
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun selectable(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setBackgroundResource(selectable())
            setOnClickListener { onClick() }
        }

    @SuppressLint("SetTextI18n")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // ---- 上のバー（アドレス・再読み込み・シールド）
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        address = EditText(this).apply {
            hint = "検索 または URL を入力"
            setHintTextColor(SUB)
            setTextColor(TEXT)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(FIELD, 20)
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigate(text.toString())
                    clearFocus()
                    hideKeyboard()
                    cur?.web?.requestFocus()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, focused ->
                if (focused) post { selectAll() }
            }
        }
        reloadBtn = barButton("⟳") { reloadOrStop() }
        shieldBtn = barButton("🛡0") { showSiteMenu() }.apply { textSize = 14f }
        top.addView(address, LinearLayout.LayoutParams(0, dp(40), 1f))
        top.addView(reloadBtn, LinearLayout.LayoutParams(dp(44), dp(40)))
        top.addView(shieldBtn, LinearLayout.LayoutParams(dp(54), dp(40)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        // ---- ページ内検索バー
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        findInput = EditText(this).apply {
            hint = "ページ内を検索"
            setHintTextColor(SUB)
            setTextColor(TEXT)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(FIELD, 16)
            setPadding(dp(12), 0, dp(12), 0)
            doAfterTextChanged {
                val q = it?.toString() ?: ""
                if (q.isEmpty()) {
                    cur?.web?.clearMatches()
                    findCount.text = ""
                } else {
                    cur?.web?.findAllAsync(q)
                }
            }
            setOnEditorActionListener { _, _, _ ->
                cur?.web?.findNext(true)
                true
            }
        }
        findCount = TextView(this).apply {
            setTextColor(SUB)
            textSize = 13f
            setPadding(dp(8), 0, dp(4), 0)
        }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, dp(36), 1f))
        findBar.addView(findCount)
        findBar.addView(barButton("▲") { cur?.web?.findNext(false) }, LinearLayout.LayoutParams(dp(40), dp(36)))
        findBar.addView(barButton("▼") { cur?.web?.findNext(true) }, LinearLayout.LayoutParams(dp(40), dp(36)))
        findBar.addView(barButton("✕") { hideFind() }, LinearLayout.LayoutParams(dp(40), dp(36)))
        root.addView(findBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 集中モードの残り時間
        focusChip = TextView(this).apply {
            setTextColor(BG)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundColor(ACCENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("集中モードを終了しますか？")
                    .setPositiveButton("終了する") { _, _ -> stopFocus() }
                    .setNegativeButton("続ける", null)
                    .show()
            }
        }
        root.addView(focusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- ページの表示領域
        content = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 下のバー（戻る・進む・パレット・タブ・メニュー）
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
        }
        tabsBtn = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), TEXT)
                cornerRadius = dp(6).toFloat()
            }
        }
        val tabsCell = FrameLayout(this).apply {
            setBackgroundResource(selectable())
            setOnClickListener { showTabs() }
            addView(tabsBtn, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        val cells = listOf(
            barButton("◀") { goBack() },
            barButton("▶") { goForward() },
            barButton("🔍") { showPalette() },
            tabsCell,
            barButton("⋮") { showMenu() },
        )
        cells.forEach { bottom.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun displayUrl(u: String): String =
        if (u.isBlank() || Uri.parse(u).host == INTERNAL_HOST) "" else u

    private fun updateUi() {
        val t = cur ?: return
        if (!address.hasFocus()) address.setText(displayUrl(t.url))
        tabsBtn.text = tabs.size.toString()
        updateShield()
        updateProgress()
    }

    @SuppressLint("SetTextI18n")
    private fun updateShield() {
        val t = cur ?: return
        shieldBtn.text = "🛡${t.adCount}"
        shieldBtn.alpha = if (t.adOff || !store.adblock) 0.45f else 1f
    }

    private fun updateProgress() {
        val t = cur ?: return
        val loading = t.progress in 0..99
        progressBar.progress = t.progress
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        reloadBtn.text = if (loading) "✕" else "⟳"
    }

    // ================================================================ タブ
    private fun ensureWeb(t: Tab): WebView = t.web ?: createWeb(t, false)

    private fun newTab(url: String? = null, activate: Boolean = true): Tab {
        val t = Tab()
        tabs.add(t)
        if (activate) {
            createWeb(t, false)
            show(t)
        }
        loadUrl(t, url ?: NEWTAB_URL)
        return t
    }

    private fun show(t: Tab) {
        if (findBar.visibility == View.VISIBLE) hideFind()
        cur = t
        val w = t.web ?: createWeb(t, true)
        (w.parent as? ViewGroup)?.removeView(w)
        content.removeAllViews()
        content.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.requestFocus()
        address.clearFocus()
        updateUi()
    }

    private fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        if (t.url.startsWith("http") && Uri.parse(t.url).host != INTERNAL_HOST) {
            closedUrls.add(t.url)
            if (closedUrls.size > 25) closedUrls.removeAt(0)
        }
        tabs.removeAt(idx)
        val w = t.web
        t.web = null
        if (w != null) {
            (w.parent as? ViewGroup)?.removeView(w)
            w.stopLoading()
            // 自分のコールバックの中で破棄しないように、あとで破棄する
            ui.post { w.destroy() }
        }
        if (tabs.isEmpty()) {
            cur = null
            newTab()
            return
        }
        if (cur === t) show(tabs[(idx - 1).coerceAtLeast(0)]) else updateUi()
    }

    private fun sleepTab(t: Tab) {
        if (t === cur) return
        val w = t.web ?: return
        t.web = null
        (w.parent as? ViewGroup)?.removeView(w)
        w.stopLoading()
        ui.post { w.destroy() }
    }

    private fun reopenClosed() {
        if (closedUrls.isEmpty()) {
            toast("閉じたタブはありません")
            return
        }
        newTab(closedUrls.removeAt(closedUrls.size - 1))
    }

    // ================================================================ ナビゲーション
    private fun navigate(input: String, tab: Tab? = cur) {
        val t = tab ?: return
        loadUrl(t, Search.toUrl(input, store.engine) ?: NEWTAB_URL)
    }

    // 追跡パラメータの除去と HTTPS への自動アップグレード
    private fun rewrite(t: Tab, url0: String): String {
        t.upgradedFrom = null
        var url = url0
        if (store.stripParams) url = Blocklist.cleanTrackingUrl(url)
        val host = Uri.parse(url).host ?: ""
        if (store.httpsUpgrade && url.startsWith("http://") && !Search.isLocalHost(host) && host !in httpOnlyHosts) {
            t.upgradedFrom = url
            url = "https://" + url.substring(7)
        }
        return url
    }

    private fun loadUrl(t: Tab, url0: String) {
        var url = url0
        val uri = Uri.parse(url)
        val host = uri.host ?: ""
        if ((uri.scheme == "http" || uri.scheme == "https") && host != INTERNAL_HOST) {
            if (focusBlocked(host)) {
                showFocusBlock(t, url)
                return
            }
            url = rewrite(t, url)
        }
        t.url = url
        t.host = Uri.parse(url).host ?: ""
        ensureWeb(t).loadUrl(url)
        if (t === cur) updateUi()
    }

    private fun goBack() {
        cur?.web?.let { if (it.canGoBack()) it.goBack() }
    }

    private fun goForward() {
        cur?.web?.let { if (it.canGoForward()) it.goForward() }
    }

    private fun reloadOrStop() {
        val t = cur ?: return
        val w = t.web ?: return
        if (t.progress in 0..99) w.stopLoading() else w.reload()
    }

    private fun onBack() {
        if (customView != null) {
            exitFull()
            return
        }
        if (findBar.visibility == View.VISIBLE) {
            hideFind()
            return
        }
        val t = cur
        val w = t?.web
        if (w != null && w.canGoBack()) {
            w.goBack()
            return
        }
        if (t != null && tabs.size > 1) {
            closeTab(t)
            return
        }
        finish()
    }

    private fun openExternalScheme(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
            toast("このリンクを開けるアプリがありません")
        }
    }

    // ================================================================ WebView
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWeb(t: Tab, autoload: Boolean): WebView {
        val w = WebView(this)
        t.web = w
        w.setBackgroundColor(BG)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = if (t.desktop) desktopUa else mobileUa
        s.textZoom = store.site(t.host).optInt("zoom", 100)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = makeClient(t)
        w.webChromeClient = makeChrome(t)
        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        w.setFindListener { active, total, _ ->
            findCount.text = if (total == 0) "0/0" else "${active + 1}/$total"
        }
        w.setOnLongClickListener { onLongPress(w) }
        if (autoload) loadUrl(t, if (t.url.isBlank()) NEWTAB_URL else t.url)
        return w
    }

    private fun makeClient(t: Tab): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return false
            if (!req.isForMainFrame) return false
            val uri = req.url
            val scheme = uri.scheme ?: return false
            if (scheme != "http" && scheme != "https") {
                if (scheme == "about") return false
                openExternalScheme(uri.toString())
                return true
            }
            val host = uri.host ?: ""
            if (host == INTERNAL_HOST) return handleInternalLink(uri, t)
            if (req.method != "GET") return false
            val url = uri.toString()
            if (focusBlocked(host)) {
                showFocusBlock(t, url)
                return true
            }
            val u = rewrite(t, url)
            if (u != url) {
                t.url = u
                view?.loadUrl(u)
                return true
            }
            return false
        }

        // 広告・トラッカーのドメインへの通信を止める（バックグラウンドのスレッドで呼ばれる）
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val uri = req.url
            if (uri.host == INTERNAL_HOST) return assetLoader.shouldInterceptRequest(uri)
            if (!req.isForMainFrame && store.adblock && !t.adOff && Blocklist.isAdHost(uri.host)) {
                t.adCount = t.adCount + 1
                ui.post { if (cur === t) updateShield() }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val u = url ?: return
            t.url = u
            t.host = Uri.parse(u).host ?: ""
            t.adCount = 0
            val site = store.site(t.host)
            t.adOff = site.optBoolean("adOff")
            t.progress = 0
            view?.settings?.textZoom = site.optInt("zoom", 100)
            if (t === cur) updateUi()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            applySite(t)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val u = url ?: return
            t.progress = 100
            t.upgradedFrom = null
            t.title = view?.title ?: ""
            applySite(t)
            if (u.startsWith("http") && t.host.isNotEmpty() && t.host != INTERNAL_HOST) store.addHistory(t.title, u)
            if (t === cur) updateUi()
        }

        // HTTPS に上げたページが開けなかったら、http に戻して開き直す
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val req = request ?: return
            if (req.isForMainFrame) fallbackToHttp(t, view, req.url.host)
        }

        // 証明書エラーは、常に中止する（「このまま進む」は用意しない）
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            fallbackToHttp(t, view, Uri.parse(error?.url ?: "").host)
        }
    }

    private fun fallbackToHttp(t: Tab, view: WebView?, failedHost: String?) {
        val from = t.upgradedFrom ?: return
        val host = Uri.parse(from).host ?: return
        if (host != failedHost) return
        t.upgradedFrom = null
        httpOnlyHosts.add(host)
        view?.loadUrl(from)
    }

    private fun makeChrome(t: Tab): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            t.progress = newProgress
            if (t === cur) updateProgress()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            t.title = title ?: ""
        }

        // window.open など。ユーザーの操作によるものだけ、新しいタブで開く（ポップアップ対策）
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val msg = resultMsg ?: return false
            if (!isUserGesture) return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            val nt = Tab()
            tabs.add(nt)
            val w = createWeb(nt, false)
            transport.webView = w
            msg.sendToTarget()
            show(nt)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            val target = tabs.firstOrNull { it.web === window } ?: return
            closeTab(target)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view != null && callback != null) enterFull(view, callback)
        }

        override fun onHideCustomView() {
            exitFull()
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            val cb = filePathCallback ?: return false
            val params = fileChooserParams ?: return false
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            return try {
                fileLauncher.launch(params.createIntent())
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            val r = request ?: return
            runOnUiThread { handleWebPermission(r) }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            val o = origin ?: return
            val cb = callback ?: return
            handleGeolocation(o, cb)
        }
    }

    // ---- アプリ内のページ（新しいタブ・集中モード）へのリンク操作 ----
    private fun handleInternalLink(uri: Uri, t: Tab): Boolean {
        // アプリ内のページから出た操作だけを受け付ける（ほかのサイトからは動かさない）
        val fromInternal = t.host == INTERNAL_HOST
        when (uri.path) {
            "/go" -> {
                if (fromInternal) navigate(uri.getQueryParameter("q") ?: "", t)
            }
            "/focus-start" -> {
                if (fromInternal) startFocus((uri.getQueryParameter("m") ?: "25").toIntOrNull() ?: 25)
            }
            "/focus-stop" -> {
                if (fromInternal) {
                    stopFocus()
                    val u = lastFocusBlocked
                    lastFocusBlocked = ""
                    loadUrl(t, if (u.isBlank()) NEWTAB_URL else u)
                }
            }
            else -> return false
        }
        return true
    }

    private fun internalPage(path: String): WebResourceResponse? {
        val html = when (path) {
            "newtab.html" -> newtabHtml()
            "focus.html" -> focusHtml()
            else -> return null
        }
        val res = WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        res.responseHeaders = mapOf("Cache-Control" to "no-store")
        return res
    }

    private fun esc(s: String): String = TextUtils.htmlEncode(s)

    private fun newtabHtml(): String {
        val tiles = store.bookmarks().take(12).joinToString("") { b ->
            val h = (Uri.parse(b.url).host ?: "").removePrefix("www.")
            val ch = (h.firstOrNull() ?: '★').uppercaseChar().toString()
            "<a class=\"tile\" href=\"${esc(b.url)}\"><span class=\"ic\">${esc(ch)}</span><span>${esc(b.title.take(14))}</span></a>"
        }
        val tilesBlock = if (tiles.isEmpty()) "<p class=\"sec\">ブックマークすると、ここに並びます</p>" else "<div class=\"tiles\">$tiles</div>"
        val focusNote = if (focusActive()) "<p class=\"sec\">🎯 集中モード中</p>" else ""
        return """<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>新しいタブ</title>
<style>
:root{color-scheme:dark}
body{margin:0;min-height:100vh;background:linear-gradient(160deg,#14211a,#1d3526);color:#e8f0ea;font-family:system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:56px 20px 32px;box-sizing:border-box}
.clock{font-size:54px;font-weight:700;text-align:center}
.hello{text-align:center;color:#9cd68a;margin:4px 0 28px}
form{display:flex;background:#243a2d;border-radius:14px;padding:4px}
input{flex:1;min-width:0;background:none;border:0;outline:0;color:inherit;font-size:16px;padding:12px 14px}
button{border:0;background:#9cd68a;color:#14211a;font-weight:700;border-radius:10px;padding:0 16px;font-size:15px}
.tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-top:30px}
.tile{display:flex;flex-direction:column;align-items:center;gap:6px;color:#e8f0ea;text-decoration:none;font-size:12px;text-align:center;word-break:break-all}
.ic{width:52px;height:52px;border-radius:16px;background:#2f5d3a;display:flex;align-items:center;justify-content:center;font-size:20px;font-weight:700}
.sec{margin-top:34px;color:#8fa597;font-size:13px;text-align:center}
.sec a{color:#9cd68a;margin:0 10px;text-decoration:none;font-size:15px}
</style></head><body><main>
<div class="clock" id="c"></div><div class="hello" id="h"></div>
<form id="f"><input id="q" placeholder="検索 / URL（例: yt ねこ）" autocomplete="off" autocapitalize="none"><button>開く</button></form>
$tilesBlock
<div class="sec">集中モード <a href="/focus-start?m=25">25分</a><a href="/focus-start?m=50">50分</a></div>
$focusNote
</main><script>
const c=document.getElementById('c'),h=document.getElementById('h');
function tick(){const d=new Date();c.textContent=d.toLocaleTimeString('ja-JP',{hour:'2-digit',minute:'2-digit'});
const H=d.getHours();h.textContent=H<5?'おつかれさまです':H<11?'おはようございます':H<18?'こんにちは':'こんばんは';}
tick();setInterval(tick,10000);
document.getElementById('f').addEventListener('submit',function(e){e.preventDefault();
const v=document.getElementById('q').value.trim();if(v)location.href='/go?q='+encodeURIComponent(v);});
</script></body></html>"""
    }

    private fun focusHtml(): String {
        val host = esc(Uri.parse(lastFocusBlocked).host ?: "このサイト")
        val remain = ((store.focusUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return """<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>集中モード</title>
<style>
:root{color-scheme:dark}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#14211a;color:#e8f0ea;font-family:system-ui,sans-serif;text-align:center}
main{padding:24px}
h1{font-size:24px;margin:0 0 8px}
.big{font-size:56px;font-weight:700;color:#9cd68a;margin:18px 0}
p{color:#8fa597}
a{display:inline-block;margin-top:16px;padding:12px 18px;border-radius:12px;background:#243a2d;color:#e8f0ea;text-decoration:none}
</style></head><body><main>
<h1>🎯 集中モード中</h1><p>$host は、集中モード中は開けません。</p>
<div class="big" id="t"></div>
<a href="/focus-stop">集中モードを終了して開く</a>
</main><script>
let s=$remain;const t=document.getElementById('t');
function f(){const m=Math.floor(s/60),r=s%60;t.textContent=String(m).padStart(2,'0')+':'+String(r).padStart(2,'0');}
f();setInterval(function(){if(s>0){s--;f();}},1000);
</script></body></html>"""
    }

    // ---- ページごとの設定（強制ダークモード・非表示にした要素）をページに適用 ----
    private fun applySite(t: Tab) {
        val w = t.web ?: return
        val host = t.host
        if (host.isEmpty() || host == INTERNAL_HOST) return
        val o = store.site(host)
        val dark = if (o.has("dark")) o.optBoolean("dark") else store.forceDarkAll
        w.evaluateJavascript(
            if (dark) PageScripts.insertCss(PageScripts.DARK_CSS, "dark") else PageScripts.removeCss("dark"),
            null
        )
        val arr = o.optJSONArray("hidden")
        if (arr != null && arr.length() > 0) {
            val css = (0 until arr.length()).joinToString("\n") { arr.optString(it) + "{display:none!important}" }
            w.evaluateJavascript(PageScripts.insertCss(css, "hidden"), null)
        }
    }

    // ---- 権限（カメラ・マイク・位置情報）の確認 ----
    private fun handleWebPermission(req: PermissionRequest) {
        val origin = req.origin.toString()
        val res = req.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (res.isEmpty()) {
            req.deny()
            return
        }
        val names = res.joinToString("・") { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) "カメラ" else "マイク" }
        val key = "perm:$origin:${res.sorted().joinToString(",")}"
        val grant: () -> Unit = {
            val need = res.map {
                if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
            }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) {
                req.grant(res.toTypedArray())
            } else {
                permCallback = { m -> if (m.values.all { it }) req.grant(res.toTypedArray()) else req.deny() }
                permLauncher.launch(need.toTypedArray())
            }
        }
        if (store.flag(key)) {
            grant()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("$names の使用")
            .setMessage("$origin が $names を使おうとしています。")
            .setPositiveButton("許可") { _, _ -> grant() }
            .setNeutralButton("常に許可") { _, _ ->
                store.setFlag(key, true)
                grant()
            }
            .setNegativeButton("拒否") { _, _ -> req.deny() }
            .setOnCancelListener { req.deny() }
            .show()
    }

    private fun handleGeolocation(origin: String, cb: GeolocationPermissions.Callback) {
        val key = "perm:$origin:geo"
        val grant: () -> Unit = {
            val p = Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) {
                cb.invoke(origin, true, false)
            } else {
                permCallback = { m -> cb.invoke(origin, m.values.any { it }, false) }
                permLauncher.launch(arrayOf(p, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        if (store.flag(key)) {
            grant()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("位置情報の使用")
            .setMessage("$origin が現在地を使おうとしています。")
            .setPositiveButton("許可") { _, _ -> grant() }
            .setNeutralButton("常に許可") { _, _ ->
                store.setFlag(key, true)
                grant()
            }
            .setNegativeButton("拒否") { _, _ -> cb.invoke(origin, false, false) }
            .setOnCancelListener { cb.invoke(origin, false, false) }
            .show()
    }

    // ---- 動画の全画面表示 ----
    private fun enterFull(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCb = cb
        view.setBackgroundColor(Color.BLACK)
        (window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFull() {
        val v = customView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        customView = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customCb?.onCustomViewHidden()
        customCb = null
    }

    // ---- ダウンロード ----
    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) {
            toast("この形式のダウンロードには対応していません")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrBlank()) req.setMimeType(mime)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (!ua.isNullOrBlank()) req.addRequestHeader("User-Agent", ua)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("ダウンロードを開始: $name")
        } catch (e: Exception) {
            toast("ダウンロードできませんでした")
        }
    }

    // ---- 長押しメニュー（リンク・画像）----
    private fun onLongPress(w: WebView): Boolean {
        val r = w.hitTestResult
        val extra = r.extra ?: return false
        return when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                linkMenu(extra, false)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                linkMenu(extra, true)
                true
            }
            else -> false
        }
    }

    private fun linkMenu(url: String, isImage: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(entry(if (isImage) "画像を新しいタブで開く" else "新しいタブで開く") { newTab(url) })
        items.add(entry("URLをコピー（追跡情報を除く）") { copy(Blocklist.cleanTrackingUrl(url)) })
        items.add(entry("共有") { share(url) })
        if (isImage) items.add(entry("画像を保存") { download(url, mobileUa, null, null) })
        AlertDialog.Builder(this)
            .setTitle(url.take(60))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun entry(label: String, run: () -> Unit): Pair<String, () -> Unit> = label to run

    private fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", s))
        toast("コピーしました")
    }

    private fun share(s: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, s)
        }
        startActivity(Intent.createChooser(i, "共有"))
    }

    // ================================================================ 集中モード
    private fun focusActive(): Boolean = store.focusUntil > System.currentTimeMillis()

    private fun focusBlocked(host: String): Boolean {
        if (host.isBlank() || !focusActive()) return false
        val h = host.lowercase()
        return store.focusSites.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .any { h == it || h.endsWith(".$it") }
    }

    private fun showFocusBlock(t: Tab, blockedUrl: String) {
        lastFocusBlocked = blockedUrl
        t.url = FOCUS_URL
        t.host = INTERNAL_HOST
        ensureWeb(t).loadUrl(FOCUS_URL)
        if (t === cur) updateUi()
    }

    private fun startFocus(minutes: Int) {
        val m = minutes.coerceIn(1, 480)
        store.focusUntil = System.currentTimeMillis() + m * 60_000L
        toast("集中モードを開始しました（${m}分）")
        updateFocusChip()
        val t = cur
        if (t != null && focusBlocked(t.host)) showFocusBlock(t, t.url)
    }

    private fun stopFocus() {
        store.focusUntil = 0
        updateFocusChip()
    }

    @SuppressLint("SetTextI18n")
    private fun updateFocusChip() {
        val until = store.focusUntil
        if (until == 0L) {
            focusChip.visibility = View.GONE
            return
        }
        val remain = (until - System.currentTimeMillis()) / 1000
        if (remain <= 0) {
            store.focusUntil = 0
            focusChip.visibility = View.GONE
            toast("集中モードが終わりました。おつかれさま！")
            return
        }
        focusChip.text = "🎯 集中モード 残り %02d:%02d（タップで終了）".format(remain / 60, remain % 60)
        focusChip.visibility = View.VISIBLE
    }

    // ================================================================ 各機能
    private fun toggleBookmark() {
        val t = cur ?: return
        if (displayUrl(t.url).isEmpty()) {
            toast("このページはブックマークできません")
            return
        }
        toast(if (store.toggleBookmark(t.title, t.url)) "ブックマークに追加しました" else "ブックマークを解除しました")
    }

    private fun toggleReader() {
        val t = cur ?: return
        val w = t.web ?: return
        if (t.host.isEmpty() || t.host == INTERNAL_HOST) {
            toast("このページではリーダーモードを使えません")
            return
        }
        w.evaluateJavascript(PageScripts.insertCss(PageScripts.READER_CSS, "reader"), null)
        w.evaluateJavascript(PageScripts.READER_JS) { r ->
            when (r) {
                "\"fail\"" -> toast("本文を検出できませんでした")
                "\"already\"" -> w.reload()
            }
        }
    }

    private fun startPicker() {
        val t = cur ?: return
        val w = t.web ?: return
        if (t.host.isEmpty() || t.host == INTERNAL_HOST) {
            toast("このページでは使えません")
            return
        }
        pickerGen++
        w.evaluateJavascript(PageScripts.PICKER_JS, null)
        pollPicker(t, pickerGen, 0)
    }

    // ページ側が window.__hzPicked に結果を入れるので、それを読み取る
    private fun pollPicker(t: Tab, gen: Int, n: Int) {
        if (gen != pickerGen) return
        val w = t.web ?: return
        w.evaluateJavascript("window.__hzPicked") { r ->
            if (gen == pickerGen) {
                if (r == null || r == "null") {
                    if (n < 300) ui.postDelayed({ pollPicker(t, gen, n + 1) }, 400)
                } else {
                    val s = try { JSONArray("[$r]").getString(0) } catch (e: Exception) { "" }
                    if (s.isNotEmpty()) {
                        store.addHidden(t.host, s)
                        applySite(t)
                        toast("消しました（このサイトでは次回も消えます）")
                    }
                }
            }
        }
    }

    private fun restoreHidden() {
        val t = cur ?: return
        if (t.host.isEmpty() || t.host == INTERNAL_HOST) return
        val o = store.site(t.host)
        o.remove("hidden")
        store.saveSite(t.host, o)
        t.web?.reload()
        toast("非表示にした要素を元に戻しました")
    }

    private fun toggleDarkSite() {
        val t = cur ?: return
        if (t.host.isEmpty() || t.host == INTERNAL_HOST) return
        val o = store.site(t.host)
        val now = if (o.has("dark")) o.optBoolean("dark") else store.forceDarkAll
        o.put("dark", !now)
        store.saveSite(t.host, o)
        applySite(t)
        toast(if (!now) "このサイトを強制ダークモードにしました" else "このサイトの強制ダークモードを解除しました")
    }

    private fun toggleAdSite() {
        val t = cur ?: return
        if (t.host.isEmpty() || t.host == INTERNAL_HOST) return
        val o = store.site(t.host)
        val off = !o.optBoolean("adOff")
        o.put("adOff", off)
        store.saveSite(t.host, o)
        t.adOff = off
        updateShield()
        t.web?.reload()
        toast(if (off) "このサイトの広告ブロックをオフにしました" else "このサイトの広告ブロックをオンにしました")
    }

    private fun toggleDesktop() {
        val t = cur ?: return
        t.desktop = !t.desktop
        t.web?.settings?.userAgentString = if (t.desktop) desktopUa else mobileUa
        t.web?.reload()
        toast(if (t.desktop) "PC版サイトで表示します" else "スマホ版サイトで表示します")
    }

    private fun changeZoom(delta: Int) {
        val t = cur ?: return
        if (t.host.isEmpty() || t.host == INTERNAL_HOST) return
        val o = store.site(t.host)
        val z = if (delta == 0) 100 else (o.optInt("zoom", 100) + delta).coerceIn(50, 300)
        if (z == 100) o.remove("zoom") else o.put("zoom", z)
        store.saveSite(t.host, o)
        t.web?.settings?.textZoom = z
        toast("文字サイズ $z%")
    }

    private fun showFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, 0)
    }

    private fun hideFind() {
        findBar.visibility = View.GONE
        findInput.setText("")
        cur?.web?.clearMatches()
        hideKeyboard()
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("閲覧データを削除")
            .setMessage("Cookie・キャッシュ・サイトのデータを削除します。ログイン状態も解除されます。（履歴・ブックマークは消えません）")
            .setPositiveButton("削除する") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web?.clearCache(true) }
                cur?.web?.reload()
                toast("削除しました")
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun editFocusSites() {
        val input = EditText(this).apply {
            setText(store.focusSites)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("集中モードでブロックするサイト（1行に1つ）")
            .setView(box)
            .setPositiveButton("保存") { _, _ -> store.focusSites = input.text.toString() }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun askFocusMinutes() {
        val input = EditText(this).apply {
            setText("25")
            inputType = InputType.TYPE_CLASS_NUMBER
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("集中モード（分）")
            .setView(box)
            .setPositiveButton("開始") { _, _ -> startFocus(input.text.toString().toIntOrNull() ?: 25) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun chooseEngine() {
        val keys = Search.engines.keys.toList()
        val names = keys.map { Search.engines.getValue(it).first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("検索エンジン")
            .setSingleChoiceItems(names, keys.indexOf(store.engine).coerceAtLeast(0)) { d, which ->
                store.engine = keys[which]
                d.dismiss()
                toast("検索エンジン: ${names[which]}")
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun showAbout() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        AlertDialog.Builder(this)
            .setTitle("Hizashi（日差し）Android 版 v$v")
            .setMessage(
                "Windows 版 Hizashi の Android 版です。表示は Android System WebView（Chromium）で、Windows 版のすべての機能があるわけではありません。\n\n" +
                    "・拡張機能には対応していません\n・広告ブロックはドメイン単位です\n" +
                    "・履歴・ブックマークなどは、この端末の中にだけ保存されます（送信しません）\n\n" +
                    "MIT ライセンス / 個人開発"
            )
            .setPositiveButton("更新を確認") { _, _ -> checkForUpdate() }
            .setNeutralButton("公式サイト") { _, _ -> newTab(SITE_URL) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    // ================================================================ 更新（GitHub のリリースを見て、新しい版に入れ替える）
    private var updating = false

    @Suppress("DEPRECATION")
    private fun currentVersionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun checkForUpdate() {
        if (updating) {
            toast("更新を処理しています")
            return
        }
        updating = true
        toast("更新を確認しています…")
        Thread {
            try {
                val rel = Updater.fetchLatest()
                runOnUiThread {
                    updating = false
                    onUpdateInfo(rel)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updating = false
                    toast("更新を確認できませんでした（ネットワークを確認してください）")
                }
            }
        }.start()
    }

    private fun onUpdateInfo(rel: Updater.Release) {
        val cur = Updater.parseVersion(currentVersionName()) ?: listOf(0)
        if (!Updater.isNewer(rel.version, cur)) {
            toast("最新版です（v${currentVersionName()}）")
            return
        }
        val notes = Updater.summarize(rel.notes)
        AlertDialog.Builder(this)
            .setTitle("新しい版 v${rel.versionName} があります")
            .setMessage(
                "いまは v${currentVersionName()} です。更新しますか？\n" +
                    "（ダウンロードして、Android のインストール画面を開きます。開いているタブは、更新後も残ります）" +
                    (if (notes.isEmpty()) "" else "\n\n$notes")
            )
            .setPositiveButton("更新する") { _, _ -> downloadUpdate(rel) }
            .setNeutralButton("リリースページ") { _, _ -> newTab(rel.pageUrl) }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun downloadUpdate(rel: Updater.Release) {
        if (updating) return
        updating = true
        saveSession()
        val dest = File(File(cacheDir, "updates"), rel.apkName)
        toast("ダウンロードを始めます…")
        Thread {
            try {
                var last = -20
                Updater.download(rel, dest) { p ->
                    if (p >= last + 20 || p == 100) {
                        last = p
                        runOnUiThread { toast("ダウンロード中… $p%") }
                    }
                }
                runOnUiThread {
                    updating = false
                    installApk(dest)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updating = false
                    toast("更新できませんでした: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun installApk(f: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("インストールの許可が必要です")
                .setMessage("次の画面で「このアプリからのインストールを許可」をオンにして戻り、もう一度「更新を確認」を押してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        toast("設定を開けませんでした")
                    }
                }
                .setNegativeButton("やめる", null)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("インストール画面を開けませんでした")
        }
    }

    private fun toggleSetting(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val v = !get()
        set(v)
        updateShield()
        toast("$name: ${if (v) "オン" else "オフ"}")
    }

    // ================================================================ コマンド一覧（パレット・メニュー共通）
    private fun buildCommands(): List<Cmd> = listOf(
        Cmd("新しいタブ", "") { newTab() },
        Cmd("タブを閉じる", "") { cur?.let { closeTab(it) } },
        Cmd("閉じたタブを開く", "") { reopenClosed() },
        Cmd("タブ一覧", "") { showTabs() },
        Cmd("他のタブをすべて閉じる", "") { cur?.let { c -> tabs.filter { it !== c }.forEach { closeTab(it) } } },
        Cmd("他のタブをすべて休止（メモリを解放）", "") {
            tabs.forEach { sleepTab(it) }
            toast("休止しました（タップすると読み込み直します）")
        },
        Cmd("再読み込み", "") { cur?.web?.reload() },
        Cmd("戻る", "") { goBack() },
        Cmd("進む", "") { goForward() },
        Cmd("ホーム（新しいタブページ）へ", "") { cur?.let { loadUrl(it, NEWTAB_URL) } },
        Cmd("ページ内検索", "") { showFind() },
        Cmd("リーダーモード", "本文だけを読みやすく表示") { toggleReader() },
        Cmd("要素ブロッカー（タップした要素を消す）", "サイトごとに記憶") { startPicker() },
        Cmd("このサイトの非表示要素をすべて元に戻す", "") { restoreHidden() },
        Cmd("このサイトを強制ダークモード（切替）", "") { toggleDarkSite() },
        Cmd("全サイトを強制ダークモード（切替）", "") {
            toggleSetting("全サイト強制ダーク", { store.forceDarkAll }, { store.forceDarkAll = it })
            cur?.let { applySite(it) }
        },
        Cmd("このサイトの広告ブロックを切替", "") { toggleAdSite() },
        Cmd("広告・トラッカーのブロックを切替（全体）", "") { toggleSetting("広告ブロック", { store.adblock }, { store.adblock = it }) },
        Cmd("HTTPS自動アップグレードを切替", "") { toggleSetting("HTTPS自動アップグレード", { store.httpsUpgrade }, { store.httpsUpgrade = it }) },
        Cmd("追跡パラメータの自動除去を切替", "") { toggleSetting("追跡パラメータの除去", { store.stripParams }, { store.stripParams = it }) },
        Cmd("PC版サイトで表示（切替）", "") { toggleDesktop() },
        Cmd("文字を大きく", "このサイトだけ") { changeZoom(10) },
        Cmd("文字を小さく", "このサイトだけ") { changeZoom(-10) },
        Cmd("文字サイズを100%に戻す", "") { changeZoom(0) },
        Cmd("ブックマークに追加 / 解除", "") { toggleBookmark() },
        Cmd("ブックマーク一覧", "") { showPalette("*") },
        Cmd("履歴を開く", "") { showPalette("?") },
        Cmd("履歴をすべて削除", "") {
            store.clearHistory()
            toast("履歴を削除しました")
        },
        Cmd("ダウンロードを開く", "") {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e: Exception) {
                toast("開けませんでした")
            }
        },
        Cmd("集中モード 25分", "") { startFocus(25) },
        Cmd("集中モード 50分", "") { startFocus(50) },
        Cmd("集中モード（時間を指定）", "") { askFocusMinutes() },
        Cmd("集中モードを終了", "") { stopFocus() },
        Cmd("集中モードのブロックサイトを編集", "") { editFocusSites() },
        Cmd("共有", "") { cur?.let { if (displayUrl(it.url).isNotEmpty()) share(it.url) } },
        Cmd("URLをコピー（追跡情報を除く）", "") { cur?.let { if (displayUrl(it.url).isNotEmpty()) copy(Blocklist.cleanTrackingUrl(it.url)) } },
        Cmd("ほかのブラウザで開く", "") {
            cur?.let {
                if (displayUrl(it.url).isNotEmpty()) {
                    try {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(it.url))
                        startActivity(Intent.createChooser(i, "開くアプリを選ぶ"))
                    } catch (e: Exception) {
                        toast("開けませんでした")
                    }
                }
            }
        },
        Cmd("検索エンジンを変更", "") { chooseEngine() },
        Cmd("閲覧データ（Cookie・キャッシュ）を削除", "") { clearBrowsingData() },
        Cmd("更新を確認（GitHub）", "新しい版があれば、ダウンロードして更新") { checkForUpdate() },
        Cmd("Hizashi について", "") { showAbout() },
    )

    private val menuTitles = listOf(
        "新しいタブ", "タブ一覧", "ブックマークに追加 / 解除", "ブックマーク一覧", "履歴を開く",
        "ページ内検索", "リーダーモード", "要素ブロッカー（タップした要素を消す）",
        "このサイトを強制ダークモード（切替）", "PC版サイトで表示（切替）", "共有",
        "ダウンロードを開く", "更新を確認（GitHub）", "Hizashi について",
    )

    private fun showMenu() {
        val list = menuTitles.mapNotNull { n -> commands.firstOrNull { it.title == n } }
        val labels = (list.map { it.title } + "すべてのコマンド（パレット）").toTypedArray()
        AlertDialog.Builder(this)
            .setItems(labels) { _, i -> if (i < list.size) list[i].run() else showPalette(">") }
            .show()
    }

    // シールドのボタン: このサイトの設定
    private fun showSiteMenu() {
        val t = cur ?: return
        val internal = t.host.isEmpty() || t.host == INTERNAL_HOST
        val o = store.site(t.host)
        val hiddenCount = o.optJSONArray("hidden")?.length() ?: 0
        val dark = if (o.has("dark")) o.optBoolean("dark") else store.forceDarkAll
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!internal) {
            items.add(entry("広告ブロック（このサイト）: ${if (t.adOff) "オフ" else "オン"}") { toggleAdSite() })
            items.add(entry("強制ダークモード（このサイト）: ${if (dark) "オン" else "オフ"}") { toggleDarkSite() })
            items.add(entry("非表示にした要素を元に戻す（${hiddenCount}件）") { restoreHidden() })
            items.add(entry("文字サイズ: ${o.optInt("zoom", 100)}%（大きく）") { changeZoom(10) })
            items.add(entry("文字サイズ: 小さく") { changeZoom(-10) })
        }
        items.add(entry("広告ブロック（全体）: ${if (store.adblock) "オン" else "オフ"}") {
            toggleSetting("広告ブロック", { store.adblock }, { store.adblock = it })
        })
        AlertDialog.Builder(this)
            .setTitle("ブロックした通信: ${t.adCount}件")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    // ================================================================ タブ一覧
    private fun showTabs() {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR)
        }
        val header = TextView(this).apply {
            text = "タブ"
            textSize = 16f
            setTextColor(TEXT)
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        val list = ListView(this).apply {
            divider = null
            setBackgroundColor(BAR)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(position: Int): Any = tabs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val t = tabs[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(4), dp(8))
                    if (t === cur) setBackgroundColor(FIELD)
                }
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@MainActivity).apply {
                    text = t.title.ifBlank { displayUrl(t.url).ifBlank { "新しいタブ" } }
                    textSize = 15f
                    setTextColor(TEXT)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                col.addView(TextView(this@MainActivity).apply {
                    text = displayUrl(t.url) + if (t.web == null) "  （休止中）" else ""
                    textSize = 12f
                    setTextColor(SUB)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this@MainActivity).apply {
                    text = "✕"
                    textSize = 18f
                    setTextColor(SUB)
                    gravity = Gravity.CENTER
                    setBackgroundResource(selectable())
                    setOnClickListener {
                        closeTab(t)
                        notifyDataSetChanged()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(44)))
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            tabs.getOrNull(pos)?.let { show(it) }
        }
        // タブが多いときは一覧をスクロールできる。開いたときは、いまのタブの位置まで動かしておく
        list.isVerticalScrollBarEnabled = true
        list.post { list.setSelection(tabs.indexOf(cur).coerceAtLeast(0)) }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(barButton("＋ 新しいタブ") {
            dlg.dismiss()
            newTab()
        }.apply { textSize = 15f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(barButton("閉じる") { dlg.dismiss() }.apply { textSize = 15f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        box.addView(header)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(footer)
        dlg.setContentView(box)
        dlg.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6).toInt())
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(BAR))
        }
        dlg.show()
    }

    // ================================================================ コマンドパレット
    private fun buildPaletteItems(raw: String): List<PItem> {
        var query = raw.trim()
        var mode = ' '
        if (query.isNotEmpty() && query[0] in ">@*?") {
            mode = query[0]
            query = query.substring(1).trim()
        }
        val items = ArrayList<PItem>()

        if (mode == ' ') {
            Search.tryEval(query)?.let { r -> items.add(PItem("= $r", "タップでコピー", 1e9) { copy(r) }) }
        }
        if (mode == ' ' || mode == '>') {
            commands.forEach { c ->
                val s = Search.fuzzyAll(query, c.title + " " + c.hint)
                if (s > 0) items.add(PItem(c.title, c.hint, s + 50, c.run))
            }
        }
        if (mode == ' ' || mode == '@') {
            tabs.forEach { t ->
                val s = Search.fuzzyAll(query, t.title + " " + t.url)
                if (s > 0 && (query.isNotEmpty() || mode == '@')) {
                    items.add(PItem("タブ: " + t.title.ifBlank { displayUrl(t.url) }, displayUrl(t.url), s + 30) { show(t) })
                }
            }
        }
        if (mode == ' ' || mode == '*') {
            store.bookmarks().forEach { b ->
                val s = Search.fuzzyAll(query, b.title + " " + b.url)
                if (s > 0 && (query.isNotEmpty() || mode == '*')) {
                    items.add(PItem("★ " + b.title, b.url, s + 20) { cur?.let { loadUrl(it, b.url) } })
                }
            }
        }
        if (mode == ' ' || mode == '?') {
            store.history().take(300).forEach { h ->
                val s = Search.fuzzyAll(query, h.title + " " + h.url)
                if (s > 0 && (query.isNotEmpty() || mode == '?')) {
                    items.add(PItem(h.title, h.url, s) { cur?.let { loadUrl(it, h.url) } })
                }
            }
        }
        val sorted = items.sortedByDescending { it.score }.take(40).toMutableList()
        if (mode == ' ' && query.isNotEmpty()) {
            val url = Search.toUrl(query, store.engine)
            if (url != null) {
                val label = if (Search.looksLikeUrl(query)) "開く: $query" else "${Search.engineName(store.engine)}で検索: $query"
                sorted.add(PItem(label, "", -1.0) { navigate(query) })
            }
        }
        return sorted
    }

    private fun showPalette(prefill: String = "") {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        val input = EditText(this).apply {
            hint = "コマンド・タブ・ブックマーク・履歴、URL、計算"
            setHintTextColor(SUB)
            setTextColor(TEXT)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(FIELD, 20)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val list = ListView(this).apply {
            divider = null
            setBackgroundColor(BAR)
        }
        var items: List<PItem> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    addView(TextView(this@MainActivity).apply {
                        textSize = 15f
                        setTextColor(TEXT)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 12f
                        setTextColor(SUB)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                val item = items[position]
                (row.getChildAt(0) as TextView).text = item.title
                val sub = row.getChildAt(1) as TextView
                sub.text = item.sub
                sub.visibility = if (item.sub.isEmpty()) View.GONE else View.VISIBLE
                return row
            }
        }
        list.adapter = adapter
        fun refresh() {
            items = buildPaletteItems(input.text.toString())
            adapter.notifyDataSetChanged()
        }
        input.doAfterTextChanged { refresh() }
        input.setOnEditorActionListener { _, _, _ ->
            items.firstOrNull()?.let { first ->
                dlg.dismiss()
                first.run()
            }
            true
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val item = items.getOrNull(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            item.run()
        }
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg.setContentView(box)
        dlg.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.62).toInt())
            setGravity(Gravity.TOP)
            setBackgroundDrawable(ColorDrawable(BAR))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        input.setText(prefill)
        refresh()
        dlg.show()
        input.requestFocus()
        input.setSelection(input.text.length)
    }
}
