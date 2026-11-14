package com.example.pipbrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Rational
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Tab(val web: WebView) {
    var title: String = ""
    var desktop: Boolean = false
}

class MainActivity : Activity() {

    private lateinit var bar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var container: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var pcBtn: Button
    private lateinit var cascadeBtn: Button
    private lateinit var pipAction: String
    private var cascade = false
    private val headers = mutableListOf<Button>()
    private var lastCW = 0
    private var lastCH = 0

    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val web: WebView get() = tabs[cur].web

    private var hasVideo = false
    private var playing = false
    private var lastPlayAt = 0L
    private var ratio = 0.0
    private var lockedRatio = 0.0
    private var lastKey = ""
    private var inPipMode = false
    private var fullW = 0
    private lateinit var mobileUa: String
    private lateinit var desktopUa: String

    private val ui = Handler(Looper.getMainLooper())
    private val logs = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val pipJs by lazy { assets.open("pip.js").bufferedReader().use { it.readText() } }

    private fun addLog(s: String) {
        synchronized(logs) {
            logs.addLast(timeFmt.format(Date()) + " " + s)
            while (logs.size > 80) logs.removeFirst()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val t = i.getStringExtra("t")
            addLog("pip button: $t")
            when (t) {
                "prev" -> js("window.__pip&&__pip.go(-1)")
                "next" -> js("window.__pip&&__pip.go(1)")
                "toggle" -> js("window.__pip&&__pip.toggle()")
            }
        }
    }

    inner class Bridge(private val owner: WebView) {
        @JavascriptInterface
        fun onState(p: Boolean, h: Boolean, r: Double) {
            runOnUiThread {
                if (tabs.isEmpty() || owner !== web) return@runOnUiThread
                val key = "$p|$h|${(r * 100).toInt()}"
                if (key == lastKey) return@runOnUiThread
                lastKey = key
                playing = p; hasVideo = h
                if (r > 0.0) ratio = r            // ignore 0 (new video still loading)
                if (inPipMode && lockedRatio <= 0.0 && ratio > 0.0) lockedRatio = ratio
                if (p) lastPlayAt = SystemClock.uptimeMillis()
                addLog("state playing=$p video=$h ratio=${"%.2f".format(r)}")
                updatePip()
            }
        }

        @JavascriptInterface
        fun log(m: String) { addLog("js: $m") }

        @JavascriptInterface
        fun swipe(next: Boolean, slow: Boolean) {
            runOnUiThread { if (tabs.isNotEmpty() && owner === web) doSwipe(next, slow) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipAction = ACTION + "." + taskId

        val defUa = WebSettings.getDefaultUserAgent(this)
        mobileUa = defUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        val chromeVer = Regex("Chrome/([\\d.]+)").find(defUa)?.groupValues?.get(1) ?: "124.0.0.0"
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"

        urlBox = EditText(this).apply {
            hint = "Search or enter address"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        val logBtn = Button(this).apply {
            text = "Log"
            setOnClickListener {
                val text = synchronized(logs) { logs.joinToString("\n") }
                AlertDialog.Builder(this@MainActivity).setTitle("Log")
                    .setMessage(if (text.isEmpty()) "(empty)" else text)
                    .setPositiveButton("OK", null).show()
            }
        }
        pcBtn = Button(this).apply { text = "PC" }
        val pipBtn = Button(this).apply {
            text = "PiP"
            setOnClickListener { enterPip() }
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(logBtn)
            addView(pcBtn)
            addView(pipBtn)
        }

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }
        val plus = Button(this).apply {
            text = "+"
            setOnClickListener { newTab("https://duckduckgo.com") }
        }
        val closeBtn = Button(this).apply {
            text = "X"
            setOnClickListener { closeCurrentTab() }
        }
        cascadeBtn = Button(this).apply {
            text = "Cascade"
            setAllCaps(false)
            setOnClickListener { if (cascade) exitCascade() else enterCascade() }
        }
        val winBtn = Button(this).apply {
            text = "Window"
            setAllCaps(false)
            setOnClickListener { openNewWindow() }
        }
        // compact buttons so everything fits on a phone
        listOf(plus, closeBtn, cascadeBtn, winBtn).forEach {
            it.minWidth = 0; it.minimumWidth = 0; it.textSize = 12f; it.setPadding(14, 0, 14, 0)
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(plus)
            addView(winBtn)
            addView(cascadeBtn)
            addView(closeBtn)
        }

        container = FrameLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(tabBar)
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // remember the normal (full screen) page width; in PiP keep that width and scale down
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (tabs.isNotEmpty()) {
                if (cascade) {
                    if (container.width != lastCW || container.height != lastCH) applyCascade()
                } else if (!inPipMode) {
                    if (web.width > 0) fullW = web.width
                } else applyPipScale()
            }
        }

        pcBtn.setOnClickListener {
            val t = tabs[cur]
            t.desktop = !t.desktop
            applyUa(t)
            pcBtn.text = if (t.desktop) "Mobile" else "PC"
            t.web.reload()
        }

        val filter = IntentFilter(pipAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        newTab(intent?.dataString ?: "https://m.youtube.com/shorts")
    }

    // ---------- second window (separate task, can sit next to this one in split-screen) ----------
    private fun openNewWindow() {
        val i = Intent(this, MainActivity::class.java)
            .setData(Uri.parse("https://duckduckgo.com"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
        if (!isInMultiWindowMode) {
            Toast.makeText(this,
                "New window opened. To see both: open Recents, tap the app icon on a card, choose Split screen.",
                Toast.LENGTH_LONG).show()
        }
        try { startActivity(i) } catch (e: Exception) {
            addLog("new window failed: ${e.message}")
            Toast.makeText(this, "Could not open a new window", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- cascade: all tabs shown as overlapping cards ----------
    private fun enterCascade() {
        if (tabs.size < 2) {
            Toast.makeText(this, "Open at least 2 tabs first (+)", Toast.LENGTH_SHORT).show()
            return
        }
        cascade = true
        cascadeBtn.text = "Normal"
        applyCascade()
    }

    private fun exitCascade() {
        if (!cascade) return
        cascade = false
        cascadeBtn.text = "Cascade"
        headers.forEach { container.removeView(it) }
        headers.clear()
        tabs.forEachIndexed { idx, t ->
            t.web.scaleX = 1f
            t.web.scaleY = 1f
            t.web.translationX = 0f
            t.web.translationY = 0f
            t.web.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            t.web.visibility = if (idx == cur) View.VISIBLE else View.GONE
            if (idx != cur) t.web.evaluateJavascript("window.__pip&&__pip.pause()", null)
        }
        if (container.width > 0) fullW = container.width
    }

    private fun focusCascade(i: Int) {
        if (i !in tabs.indices) return
        cur = i
        urlBox.setText(web.url ?: "")
        pcBtn.text = if (tabs[i].desktop) "Mobile" else "PC"
        lastKey = ""; hasVideo = false; playing = false
        js("window.__pip&&__pip.report&&__pip.report()")
        refreshTabs()
        applyCascade()
    }

    private fun applyCascade() {
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0) { container.post { applyCascade() }; return }
        lastCW = cw; lastCH = ch
        headers.forEach { container.removeView(it) }
        headers.clear()
        val d = resources.displayMetrics.density
        val hh = (34 * d).toInt()
        val s = 0.62f
        val n = tabs.size
        val cardW = (cw * s).toInt()
        val availW = cw * (1 - s)
        val availH = ch * (1 - s) - hh
        val dx = if (n > 1) minOf(availW / (n - 1), 48 * d) else 0f
        val dy = if (n > 1) minOf(availH / (n - 1), 56 * d) else 0f
        tabs.forEachIndexed { i, t ->
            val w = t.web
            w.layoutParams = FrameLayout.LayoutParams(cw, ch)
            w.pivotX = 0f
            w.pivotY = 0f
            w.scaleX = s
            w.scaleY = s
            w.translationX = i * dx
            w.translationY = i * dy + hh
            w.visibility = View.VISIBLE
            w.setBackgroundColor(Color.WHITE)
            val hb = Button(this).apply {
                setAllCaps(false)
                minHeight = 0
                minimumHeight = 0
                textSize = 12f
                setPadding(8, 0, 8, 0)
                text = "${i + 1}. " + (if (t.title.isBlank()) "Tab" else t.title).take(18)
                setBackgroundColor(Color.parseColor(if (i == cur) "#1A73E8" else "#DDDDDD"))
                setTextColor(if (i == cur) Color.WHITE else Color.BLACK)
                setOnClickListener { focusCascade(i) }
            }
            container.addView(hb, FrameLayout.LayoutParams(cardW, hh))
            hb.translationX = i * dx
            hb.translationY = i * dy
            headers.add(hb)
        }
        // stacking: the focused tab is drawn on top
        val order = tabs.indices.filter { it != cur } + cur
        for (i in order) {
            tabs[i].web.bringToFront()
            headers[i].bringToFront()
        }
    }

    // ---------- tabs ----------
    private fun applyUa(t: Tab) {
        t.web.settings.userAgentString = if (t.desktop) desktopUa else mobileUa
        t.web.settings.useWideViewPort = t.desktop
        t.web.settings.loadWithOverviewMode = t.desktop
    }

    private fun createTab(url: String): Tab {
        val w = WebView(this)
        val tab = Tab(w)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        w.settings.setSupportMultipleWindows(false)
        applyUa(tab)
        w.addJavascriptInterface(Bridge(w), "AndroidPip")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, t: String?) {
                tab.title = t ?: ""
                refreshTabs()
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val s = u.scheme ?: return false
                if (s == "http" || s == "https" || s == "about" || s == "blob" || s == "data" || s == "javascript") return false
                if (s == "intent") {
                    try {
                        val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        i.getStringExtra("browser_fallback_url")?.let { v.loadUrl(it) }
                    } catch (_: Exception) {}
                }
                return true
            }
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                v.evaluateJavascript(pipJs, null)
            }
            override fun onPageFinished(v: WebView, u: String) {
                v.evaluateJavascript(pipJs, null)
            }
            override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
                if (tabs.isNotEmpty() && v === web && !urlBox.hasFocus()) urlBox.setText(u)
            }
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    addLog("load error ${err.errorCode} ${err.description}")
                    Toast.makeText(this@MainActivity,
                        "Load error: ${err.description} (${err.errorCode})", Toast.LENGTH_LONG).show()
                }
            }
        }

        container.addView(w, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs.add(tab)
        w.loadUrl(url)
        return tab
    }

    private fun newTab(url: String) {
        createTab(url)
        selectTab(tabs.lastIndex)
    }

    private fun selectTab(i: Int) {
        if (i !in tabs.indices) return
        if (cascade) { focusCascade(i); return }
        // only one video plays at a time: pause the tab we leave
        if (cur in tabs.indices && cur != i) {
            tabs[cur].web.evaluateJavascript("window.__pip&&__pip.pause()", null)
        }
        cur = i
        tabs.forEachIndexed { idx, t -> t.web.visibility = if (idx == i) View.VISIBLE else View.GONE }
        urlBox.setText(web.url ?: "")
        pcBtn.text = if (tabs[i].desktop) "Mobile" else "PC"
        lastKey = ""; hasVideo = false; playing = false
        js("window.__pip&&__pip.report&&__pip.report()")
        refreshTabs()
        web.requestFocus()
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) { load("https://duckduckgo.com"); return }
        val t = tabs.removeAt(cur)
        container.removeView(t.web)
        t.web.destroy()
        cur = minOf(cur, tabs.size - 1)
        selectTab(cur)
        if (cascade && tabs.size < 2) exitCascade()
    }

    private fun refreshTabs() {
        runOnUiThread {
            if (!::tabRow.isInitialized) return@runOnUiThread
            tabRow.removeAllViews()
            tabs.forEachIndexed { i, t ->
                val label = (if (t.title.isBlank()) "Tab ${i + 1}" else t.title).take(14)
                tabRow.addView(Button(this).apply {
                    text = label
                    setAllCaps(false)
                    alpha = if (i == cur) 1f else 0.55f
                    setOnClickListener { selectTab(i) }
                })
            }
        }
    }

    private fun js(code: String) {
        if (tabs.isNotEmpty()) web.evaluateJavascript(code, null)
    }

    private fun load(input: String) {
        val s = input.trim()
        val u = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(s)
        }
        web.loadUrl(u)
        web.requestFocus()
    }

    // real finger swipe on the page (works on touch-based short-video feeds)
    private fun doSwipe(next: Boolean, slow: Boolean) {
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val x = w / 2f
        val a = if (slow) 0.72f else 0.75f
        val b = if (slow) 0.28f else 0.25f
        val y0 = if (next) h * a else h * b
        val y1 = if (next) h * b else h * a
        val steps = if (slow) 5 else 12      // "slow" flag now means a quick flick
        val gap = if (slow) 12L else 18L
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(e)
            e.recycle()
        }
        addLog("swipe ${if (next) "next" else "prev"}${if (slow) " (flick)" else ""}")
        send(MotionEvent.ACTION_DOWN, y0)
        for (i in 1..steps) ui.postDelayed({ send(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps) }, i * gap)
        ui.postDelayed({ send(MotionEvent.ACTION_UP, y1) }, (steps + 1) * gap)
    }

    // ---------- Picture-in-Picture ----------
    private fun action(code: Int, tag: String, icon: Int, title: String) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(
            this, code, Intent(pipAction).setPackage(packageName).putExtra("t", tag),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun pipReady() =
        hasVideo && (playing || SystemClock.uptimeMillis() - lastPlayAt < 4000)

    private fun pipParams(): PictureInPictureParams {
        // while in PiP keep the window shape fixed (a changing shape reflows the page and breaks swiping)
        val base = if (inPipMode && lockedRatio > 0.0) lockedRatio else ratio
        val r = if (base > 0.0) base.coerceIn(0.42, 2.39) else 16.0 / 9.0
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((r * 1000).toInt(), 1000))
            .setActions(listOf(
                action(1, "prev", android.R.drawable.ic_media_previous, "Previous"),
                action(2, "toggle",
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play"),
                action(3, "next", android.R.drawable.ic_media_next, "Next")
            ))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipReady())
        return b.build()
    }

    private fun updatePip() {
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun enterPip() {
        try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
    }

    // keep the page laid out at normal size inside the small PiP window (avoids tiny-viewport reflow)
    private fun applyPipScale() {
        if (tabs.isEmpty()) return
        val rw = container.width
        val rh = container.height
        if (rw <= 0 || rh <= 0 || fullW <= 0) return
        val s = rw.toFloat() / fullW
        val fh = (rh / s).toInt()
        val lp = web.layoutParams
        if (lp.width != fullW || lp.height != fh) {
            web.layoutParams = FrameLayout.LayoutParams(fullW, fh)
        }
        web.pivotX = 0f
        web.pivotY = 0f
        web.scaleX = s
        web.scaleY = s
    }

    private fun resetScale() {
        if (tabs.isEmpty()) return
        web.scaleX = 1f
        web.scaleY = 1f
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < 31 && pipReady()) enterPip()
    }

    override fun onPause() {
        super.onPause()
        addLog("onPause")
        js("window.__pip&&__pip.hold()")
    }

    override fun onResume() {
        super.onResume()
        addLog("onResume")
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        addLog("pip mode $inPip")
        inPipMode = inPip
        lockedRatio = if (inPip) ratio else 0.0
        if (inPip) exitCascade()
        bar.visibility = if (inPip) View.GONE else View.VISIBLE
        tabBar.visibility = if (inPip) View.GONE else View.VISIBLE
        root.setBackgroundColor(if (inPip) Color.BLACK else Color.WHITE)
        if (inPip) {
            if (tabs.isNotEmpty()) { web.onResume(); web.resumeTimers() }
            root.post { applyPipScale() }
        } else {
            resetScale()
        }
        js("window.__pip&&(__pip.hold(),__pip.setPip($inPip))")
    }

    override fun onStop() {
        super.onStop()
        addLog("onStop inPip=$isInPictureInPictureMode")
        // PiP window was dismissed with X: stop playback
        if (isInPictureInPictureMode) js("window.__pip&&__pip.pause()")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cascade) exitCascade()
        else if (tabs.isNotEmpty() && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    companion object { const val ACTION = "com.example.pipbrowser.PIP_ACTION" }
}
