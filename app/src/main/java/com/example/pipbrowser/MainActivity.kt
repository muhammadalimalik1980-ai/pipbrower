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
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var urlBox: EditText

    private var hasVideo = false
    private var playing = false
    private var ratio = 0.0
    private var lastKey = ""
    private var desktopMode = false
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

    inner class Bridge {
        @JavascriptInterface
        fun onState(p: Boolean, h: Boolean, r: Double) {
            runOnUiThread {
                val key = "$p|$h|${(r * 100).toInt()}"
                if (key == lastKey) return@runOnUiThread
                lastKey = key
                playing = p; hasVideo = h; ratio = r
                addLog("state playing=$p video=$h ratio=${"%.2f".format(r)}")
                updatePip()
            }
        }

        @JavascriptInterface
        fun log(m: String) { addLog("js: $m") }

        @JavascriptInterface
        fun swipe(next: Boolean) { runOnUiThread { doSwipe(next) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val pcBtn = Button(this).apply { text = "PC" }
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

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(Bridge(), "AndroidPip")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
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
                    if (!urlBox.hasFocus()) urlBox.setText(u)
                }
                override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) {
                        addLog("load error ${err.errorCode} ${err.description}")
                        Toast.makeText(this@MainActivity,
                            "Load error: ${err.description} (${err.errorCode})", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val defUa = web.settings.userAgentString
        mobileUa = defUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        val chromeVer = Regex("Chrome/([\\d.]+)").find(defUa)?.groupValues?.get(1) ?: "124.0.0.0"
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"
        web.settings.userAgentString = mobileUa

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        pcBtn.setOnClickListener {
            desktopMode = !desktopMode
            web.settings.userAgentString = if (desktopMode) desktopUa else mobileUa
            web.settings.useWideViewPort = desktopMode
            web.settings.loadWithOverviewMode = desktopMode
            pcBtn.text = if (desktopMode) "Mobile" else "PC"
            web.reload()
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(web, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // remember the normal (full screen) page width; in PiP keep that width and scale down
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!inPipMode) { if (web.width > 0) fullW = web.width } else applyPipScale()
        }

        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        load(intent?.dataString ?: "https://m.youtube.com/shorts")
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

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
    private fun doSwipe(next: Boolean) {
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val x = w / 2f
        val y0 = if (next) h * 0.75f else h * 0.25f
        val y1 = if (next) h * 0.25f else h * 0.75f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(e)
            e.recycle()
        }
        addLog("swipe ${if (next) "next" else "prev"}")
        send(MotionEvent.ACTION_DOWN, y0)
        val steps = 8
        for (i in 1..steps) ui.postDelayed({ send(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps) }, i * 16L)
        ui.postDelayed({ send(MotionEvent.ACTION_UP, y1) }, (steps + 1) * 16L)
    }

    // ---------- Picture-in-Picture ----------
    private fun action(code: Int, tag: String, icon: Int, title: String) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(
            this, code, Intent(ACTION).setPackage(packageName).putExtra("t", tag),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun pipParams(): PictureInPictureParams {
        val r = if (ratio > 0.0) ratio.coerceIn(0.42, 2.39) else 16.0 / 9.0
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((r * 1000).toInt(), 1000))
            .setActions(listOf(
                action(1, "prev", android.R.drawable.ic_media_previous, "Previous"),
                action(2, "toggle",
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play"),
                action(3, "next", android.R.drawable.ic_media_next, "Next")
            ))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(hasVideo && playing)
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
        val rw = root.width
        val rh = root.height
        if (rw <= 0 || rh <= 0 || fullW <= 0) return
        val s = rw.toFloat() / fullW
        val fh = (rh / s).toInt()
        val lp = web.layoutParams
        if (lp.width != fullW || lp.height != fh) {
            web.layoutParams = LinearLayout.LayoutParams(fullW, fh)
        }
        web.pivotX = 0f
        web.pivotY = 0f
        web.scaleX = s
        web.scaleY = s
    }

    private fun resetScale() {
        web.scaleX = 1f
        web.scaleY = 1f
        web.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < 31 && hasVideo && playing) enterPip()
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
        bar.visibility = if (inPip) View.GONE else View.VISIBLE
        root.setBackgroundColor(if (inPip) Color.BLACK else Color.WHITE)
        if (inPip) {
            web.onResume(); web.resumeTimers()
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
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        web.destroy()
        super.onDestroy()
    }

    companion object { const val ACTION = "com.example.pipbrowser.PIP_ACTION" }
}
