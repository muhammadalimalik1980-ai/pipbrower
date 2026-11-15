package com.example.pipbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout

/** JS <-> native bridge for one floating window. */
class FloatBridge(
    private val post: (() -> Unit) -> Unit,
    private val onStateCb: (Boolean, Boolean, Double) -> Unit,
    private val swipeCb: (Boolean, Int) -> Unit
) {
    @JavascriptInterface
    fun onState(p: Boolean, h: Boolean, r: Double) { post { onStateCb(p, h, r) } }

    @JavascriptInterface
    fun log(m: String) {}

    @JavascriptInterface
    fun swipe(next: Boolean, style: Int) { post { swipeCb(next, style) } }
}

/**
 * Keeps floating video windows alive (drawn over other apps).
 * Every window is its own WebView, so several can exist at the same time.
 */
class FloatService : Service() {

    companion object {
        const val ACTION_ADD = "com.example.pipbrowser.FLOAT_ADD"
        const val ACTION_CLOSE_ALL = "com.example.pipbrowser.FLOAT_CLOSE_ALL"
        private const val CHANNEL = "float_windows"
        private const val NOTIF_ID = 42
    }

    private lateinit var wm: WindowManager
    private lateinit var mobileUa: String
    private lateinit var desktopUa: String
    private val wins = mutableListOf<FloatWin>()
    private val ui = Handler(Looper.getMainLooper())
    private val pipJs by lazy { assets.open("pip.js").bufferedReader().use { it.readText() } }

    private val touchDeviceId: Int by lazy {
        InputDevice.getDeviceIds().firstOrNull {
            ((InputDevice.getDevice(it)?.sources ?: 0) and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        } ?: 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val defUa = WebSettings.getDefaultUserAgent(this)
        mobileUa = defUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        val chromeVer = Regex("Chrome/([\\d.]+)").find(defUa)?.groupValues?.get(1) ?: "124.0.0.0"
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"
        goForeground()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating videos", NotificationManager.IMPORTANCE_LOW))
        val closeAll = PendingIntent.getService(
            this, 0, Intent(this, FloatService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Floating videos")
            .setContentText("Tap Close all to remove every floating window")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Close all", closeAll).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_ADD -> {
                val w = FloatWin(
                    intent.getStringExtra("url") ?: "https://duckduckgo.com",
                    intent.getBooleanExtra("desktop", false),
                    intent.getDoubleExtra("startAt", 0.0))
                wins.add(w)
                w.show(wins.size - 1)
            }
            ACTION_CLOSE_ALL -> { closeAll(); stopSelf() }
        }
        if (wins.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun closeAll() { wins.toList().forEach { it.close() } }

    override fun onDestroy() {
        closeAll()
        super.onDestroy()
    }

    inner class FloatWin(
        private val url: String,
        private val desktop: Boolean,
        private val startAt: Double
    ) {
        private val d = resources.displayMetrics.density
        private val ctx = ContextThemeWrapper(this@FloatService, android.R.style.Theme_Material_Light_NoActionBar)
        private val web = WebView(ctx)
        private val box = FrameLayout(ctx)
        private val touch = View(ctx)
        private var playBtn: ImageButton? = null
        private var winW = (180 * d).toInt()
        private var ratio = if (Regex("shorts|tiktok|reel|stories").containsMatchIn(url)) 0.5625f else 1.7778f
        private var ratioLocked = false
        private val virtW = (360 * d).toInt()
        private var startApplied = false
        private val lp = WindowManager.LayoutParams(
            0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT)

        private fun winH() = (winW / ratio).toInt()

        // page keeps a normal phone-sized layout and is scaled down into the small window
        private fun layoutWeb() {
            val vh = (virtW / ratio).toInt()
            web.layoutParams = FrameLayout.LayoutParams(virtW, vh)
            val s = winW.toFloat() / virtW
            web.pivotX = 0f
            web.pivotY = 0f
            web.scaleX = s
            web.scaleY = s
        }

        private fun applySize() {
            lp.width = winW
            lp.height = winH()
            layoutWeb()
            try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
        }

        private fun ib(icon: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(ctx).apply {
                setImageResource(icon)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = desc
                setOnClickListener { onClick() }
            }
        }

        private fun js(code: String) { web.evaluateJavascript(code, null) }

        private fun onState(p: Boolean, h: Boolean, r: Double) {
            playBtn?.setImageResource(
                if (p) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            if (!ratioLocked && r > 0.0) {
                ratioLocked = true
                ratio = r.toFloat().coerceIn(0.5f, 2f)
                applySize()
            }
        }

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
                    touchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                web.dispatchTouchEvent(e)
                e.recycle()
            }
            send(MotionEvent.ACTION_DOWN, y0)
            for (i in 1..steps) ui.postDelayed({ send(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps) }, i * gap)
            ui.postDelayed({ send(MotionEvent.ACTION_UP, y1) }, (steps + 1) * gap)
        }

        fun show(index: Int) {
            val s = web.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.mediaPlaybackRequiresUserGesture = false
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            s.userAgentString = if (desktop) desktopUa else mobileUa
            if (desktop) { s.useWideViewPort = true; s.loadWithOverviewMode = true }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            web.addJavascriptInterface(
                FloatBridge({ r -> ui.post { r() } }, { p, h, r -> onState(p, h, r) }, { n, st -> doSwipe(n, st) }),
                "AndroidPip")
            web.webChromeClient = WebChromeClient()
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    val sc = req.url.scheme ?: return false
                    return !(sc == "http" || sc == "https" || sc == "about" || sc == "blob" || sc == "data" || sc == "javascript")
                }
                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                    v.evaluateJavascript(pipJs, null)
                }
                override fun onPageFinished(v: WebView, u: String) {
                    v.evaluateJavascript(pipJs, null)
                    var cmd = "window.__pip&&(__pip.hold(),__pip.setPip(true),__pip.setSound(true)"
                    if (!startApplied && startAt > 1) { cmd += ",__pip.startAt($startAt)"; startApplied = true }
                    cmd += ")"
                    v.evaluateJavascript(cmd, null)
                }
            }

            box.setBackgroundColor(Color.BLACK)
            layoutWeb()
            box.addView(web)

            // transparent layer: drag with one finger, pinch to resize
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var resync = false
            val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    val maxW = (resources.displayMetrics.widthPixels * 0.95f).toInt()
                    winW = (winW * det.scaleFactor).toInt().coerceIn((110 * d).toInt(), maxW)
                    applySize()
                    return true
                }
            })
            touch.setOnTouchListener { _, e ->
                pinch.onTouchEvent(e)
                if (!pinch.isInProgress && e.pointerCount == 1) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; resync = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (resync) {
                                downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; resync = false
                            }
                            lp.x = startX + (e.rawX - downX).toInt()
                            lp.y = startY + (e.rawY - downY).toInt()
                            try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
                        }
                    }
                } else if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    resync = true
                }
                true
            }
            box.addView(touch, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            // controls: previous / play-pause / next / close
            val controls = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#99000000"))
            }
            val soundBtn = ib(android.R.drawable.ic_lock_silent_mode_off, "Sound on or off") {}
            soundBtn.setOnClickListener {
                web.evaluateJavascript("window.__pip&&__pip.toggleSound()") { r ->
                    soundBtn.setImageResource(
                        if (r == "true") android.R.drawable.ic_lock_silent_mode_off
                        else android.R.drawable.ic_lock_silent_mode)
                }
            }
            val pb = ib(android.R.drawable.ic_media_pause, "Play or pause") { js("window.__pip&&__pip.toggle()") }
            playBtn = pb
            listOf(
                ib(android.R.drawable.ic_media_previous, "Previous") { js("window.__pip&&__pip.go(-1)") },
                pb,
                ib(android.R.drawable.ic_media_next, "Next") { js("window.__pip&&__pip.go(1)") },
                soundBtn,
                ib(android.R.drawable.ic_menu_close_clear_cancel, "Close") { close() }
            ).forEach {
                controls.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            box.addView(controls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (36 * d).toInt(), Gravity.BOTTOM))

            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = winW
            lp.height = winH()
            lp.x = (16 * d).toInt() + (index % 5) * (28 * d).toInt()
            lp.y = (80 * d).toInt() + (index % 5) * (36 * d).toInt()
            try {
                wm.addView(box, lp)
            } catch (e: Exception) {
                close()
                return
            }
            web.loadUrl(url)
        }

        fun close() {
            try { wm.removeView(box) } catch (_: Exception) {}
            box.removeAllViews()
            web.destroy()
            wins.remove(this)
            if (wins.isEmpty()) stopSelf()
        }
    }
}
