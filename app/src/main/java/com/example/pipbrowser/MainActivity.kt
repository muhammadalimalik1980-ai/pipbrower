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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Rational
import android.view.Gravity
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var progress: ProgressBar
    private val BG = Color.parseColor("#121212")
    private val SURFACE = Color.parseColor("#2A2B31")
    private val ACCENT = Color.parseColor("#8AB4F8")
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
        fun swipe(next: Boolean, style: Int) {
            runOnUiThread { if (tabs.isNotEmpty() && owner === web) doSwipe(next, style) }
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
            hint = "Search or type address"
            setHintTextColor(Color.parseColor("#9AA0A6"))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(SURFACE, 22)
            setPadding(dp(16), 0, dp(16), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        val backBtn = iconBtn(R.drawable.ic_back, "Back", Color.WHITE) { goBackAction() }
        val pipBtn = iconBtn(R.drawable.ic_pip, "Picture in picture", ACCENT) { enterPip() }
        val floatBtn = iconBtn(R.drawable.ic_float, "Floating window", ACCENT) { startFloat() }
        val menuBtn = iconBtn(R.drawable.ic_more, "Menu", Color.WHITE) { showMenu(it) }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(6), dp(6), dp(6), dp(4))
            addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(urlBox, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            addView(pipBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(floatBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            visibility = View.GONE
        }

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }
        val plus = iconBtn(R.drawable.ic_add, "New tab", Color.WHITE) { newTab(HOME) }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(8), 0, dp(4), dp(6))
            addView(scroll, LinearLayout.LayoutParams(0, dp(34), 1f))
            addView(plus, LinearLayout.LayoutParams(dp(34), dp(34)))
        }

        container = FrameLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            addView(bar)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
            addView(tabBar)
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        window.statusBarColor = BG
        window.navigationBarColor = BG

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

        val filter = IntentFilter(pipAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        newTab(intent?.dataString ?: HOME)
    }

    // ---------- look & feel helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconBtn(res: Int, desc: String, tint: Int, onClick: (View) -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            contentDescription = desc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            setOnClickListener { onClick(it) }
        }
    }

    private fun displayUrl(u: String?): String =
        if (u == null || u.startsWith(HOME_BASE)) "" else u

    private fun loadHome(w: WebView) {
        val html = assets.open("home.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL(HOME_BASE, html, "text/html", "utf-8", HOME_BASE)
    }

    private fun goBackAction() {
        if (cascade) exitCascade() else if (tabs.isNotEmpty() && web.canGoBack()) web.goBack()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val t = tabs[cur]
        pm.menu.add(0, 1, 0, "New tab")
        pm.menu.add(0, 2, 1, "Reload")
        pm.menu.add(0, 3, 2, if (t.desktop) "Mobile site" else "Desktop site")
        pm.menu.add(0, 4, 3, if (cascade) "Normal view" else "Cascade tabs")
        pm.menu.add(0, 5, 4, "New window")
        pm.menu.add(0, 6, 5, "Home page")
        pm.menu.add(0, 7, 6, "Log")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(HOME)
                2 -> web.reload()
                3 -> { t.desktop = !t.desktop; applyUa(t); t.web.reload() }
                4 -> { if (cascade) exitCascade() else enterCascade() }
                5 -> openNewWindow()
                6 -> loadHome(web)
                7 -> showLog()
            }
            true
        }
        pm.show()
    }

    private fun showLog() {
        val text = synchronized(logs) { logs.joinToString("\n") }
        AlertDialog.Builder(this)
            .setTitle("Log")
            .setMessage(if (text.isEmpty()) "(empty)" else text)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- floating window: current video in a small window drawn over other apps ----------
    private fun startFloat() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "Turn on \"Allow display over other apps\" for this app, then come back and press Float again.",
                Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) { addLog("overlay settings failed: ${e.message}") }
            return
        }
        val pageUrl = web.url
        if (pageUrl.isNullOrBlank()) return
        if (pageUrl.startsWith(HOME_BASE)) {
            Toast.makeText(this, "Open a video page first, then tap Float.", Toast.LENGTH_SHORT).show()
            return
        }
        val desktop = tabs[cur].desktop
        web.evaluateJavascript(
            "(function(){try{return window.__pip&&__pip.time?__pip.time():0}catch(e){return 0}})()"
        ) { r ->
            val t = r?.trim('"')?.toDoubleOrNull() ?: 0.0
            val i = Intent(this, FloatService::class.java)
                .setAction(FloatService.ACTION_ADD)
                .putExtra("url", pageUrl)
                .putExtra("desktop", desktop)
                .putExtra("startAt", t)
            try {
                startForegroundService(i)
                addLog("float added: $pageUrl at ${t.toInt()}s")
                js("window.__pip&&__pip.pause()")   // the floating copy plays instead
                Toast.makeText(this,
                    "Floating window added. Drag it, pinch to resize, X closes it.",
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                addLog("float failed: ${e.message}")
                Toast.makeText(this, "Could not start floating window", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- second window (separate task, can sit next to this one in split-screen) ----------
    private fun openNewWindow() {
        val i = Intent(this, MainActivity::class.java)
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
        applyCascade()
    }

    private fun exitCascade() {
        if (!cascade) return
        cascade = false
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
        urlBox.setText(displayUrl(web.url))
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
                setBackgroundColor(if (i == cur) Color.parseColor("#33507F") else SURFACE)
                setTextColor(if (i == cur) Color.WHITE else Color.parseColor("#B0B4BA"))
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
            override fun onProgressChanged(v: WebView, p: Int) {
                if (tabs.isNotEmpty() && v === web) {
                    progress.progress = p
                    progress.visibility = if (p in 1..99 && !inPipMode) View.VISIBLE else View.GONE
                }
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
                reapplyPip(v)
            }
            override fun onPageFinished(v: WebView, u: String) {
                v.evaluateJavascript(pipJs, null)
                reapplyPip(v)
            }
            override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
                if (tabs.isNotEmpty() && v === web && !urlBox.hasFocus()) urlBox.setText(displayUrl(u))
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
        if (url == HOME) loadHome(w) else w.loadUrl(url)
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
        urlBox.setText(displayUrl(web.url))
        lastKey = ""; hasVideo = false; playing = false
        js("window.__pip&&__pip.report&&__pip.report()")
        refreshTabs()
        web.requestFocus()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        if (tabs.size <= 1) { loadHome(web); return }
        val t = tabs.removeAt(i)
        container.removeView(t.web)
        t.web.destroy()
        if (i < cur) cur-- else if (i == cur) cur = minOf(cur, tabs.size - 1)
        selectTab(cur)
        if (cascade && tabs.size < 2) exitCascade()
    }

    private fun refreshTabs() {
        runOnUiThread {
            if (!::tabRow.isInitialized) return@runOnUiThread
            tabRow.removeAllViews()
            tabs.forEachIndexed { i, t ->
                val sel = i == cur
                val fg = if (sel) Color.WHITE else Color.parseColor("#B0B4BA")
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (sel) Color.parseColor("#33507F") else SURFACE, 16)
                    setPadding(dp(12), 0, dp(2), 0)
                    setOnClickListener { selectTab(i) }
                }
                val tv = TextView(this).apply {
                    text = if (t.title.isBlank()) "Tab ${i + 1}" else t.title
                    textSize = 12f
                    maxLines = 1
                    maxWidth = dp(110)
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(fg)
                }
                val x = ImageView(this).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(fg)
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    setOnClickListener { closeTab(i) }
                }
                chip.addView(tv)
                chip.addView(x, LinearLayout.LayoutParams(dp(28), dp(28)))
                tabRow.addView(chip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { setMargins(0, 0, dp(6), 0) })
            }
        }
    }

    // a new page document has no PiP picture layer yet: add it again (otherwise PiP shows the raw page)
    private fun reapplyPip(v: WebView) {
        if (inPipMode && tabs.isNotEmpty() && v === web) {
            v.evaluateJavascript("window.__pip&&(__pip.hold(),__pip.setPip(true))", null)
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

    private val touchDeviceId: Int by lazy {
        InputDevice.getDeviceIds().firstOrNull {
            ((InputDevice.getDevice(it)?.sources ?: 0) and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        } ?: 0
    }

    // real finger swipe on the page (works on touch-based short-video feeds)
    // style 0 = normal, 1 = long and smooth, 2 = quick flick
    private fun doSwipe(next: Boolean, style: Int) {
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val fx = w / 2f
        val a = when (style) { 1 -> 0.85f; 2 -> 0.72f; else -> 0.70f }
        val b = when (style) { 1 -> 0.15f; 2 -> 0.28f; else -> 0.25f }
        val steps = when (style) { 1 -> 16; 2 -> 5; else -> 12 }
        val gap = when (style) { 1 -> 16L; 2 -> 12L; else -> 18L }
        val y0 = if (next) h * a else h * b
        val y1 = if (next) h * b else h * a
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fy: Float) {
            // describe the touch as a real finger on a real touchscreen
            val pp = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val pc = MotionEvent.PointerCoords().apply {
                this.x = fx
                this.y = fy
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 0.1f
            }
            val e = MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f,
                touchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            web.dispatchTouchEvent(e)
            e.recycle()
        }
        addLog("swipe ${if (next) "next" else "prev"} style=$style")
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
            addLog("pip layout window=${rw}x$rh page=${fullW}x$fh scale=${"%.2f".format(s)}")
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
        root.setBackgroundColor(if (inPip) Color.BLACK else BG)
        if (inPip) progress.visibility = View.GONE
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

    companion object {
        const val ACTION = "com.example.pipbrowser.PIP_ACTION"
        const val HOME = "about:home"
        const val HOME_BASE = "https://home.local/"
    }
}
