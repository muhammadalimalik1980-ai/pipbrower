package com.example.pipbrowser

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Rational
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var urlBox: EditText

    private var hasVideo = false
    private var playing = false
    private var ratio = 0.0
    private var lastKey = ""

    private val pipJs by lazy { assets.open("pip.js").bufferedReader().use { it.readText() } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra("t")) {
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
                updatePip()
            }
        }
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
        val pipBtn = Button(this).apply {
            text = "PiP"
            setOnClickListener { enterPip() }
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pipBtn)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "AndroidPip")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, f: android.graphics.Bitmap?) {
                    v.evaluateJavascript(pipJs, null)
                }
                override fun onPageFinished(v: WebView, u: String) {
                    v.evaluateJavascript(pipJs, null)
                }
                override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
                    if (!urlBox.hasFocus()) urlBox.setText(u)
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(web, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

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

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < 31 && hasVideo && playing) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        bar.visibility = if (inPip) View.GONE else View.VISIBLE
        js("window.__pip&&__pip.setPip($inPip)")
    }

    override fun onStop() {
        super.onStop()
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
