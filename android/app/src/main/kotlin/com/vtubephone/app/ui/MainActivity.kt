package com.vtubephone.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vtubephone.app.VtubePhoneApplication
import com.vtubephone.app.haptics.HapticsService
import com.vtubephone.app.bridge.AvatarBridge
import com.vtubephone.app.brain.mainThreadPoster
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val service get() = (application as VtubePhoneApplication).haptics
    private val sensing get() = (application as VtubePhoneApplication).sensing
    private val app get() = application as VtubePhoneApplication
    private val brain get() = app.brain
    private lateinit var avatarBridge: AvatarBridge
    private lateinit var webView: WebView
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        avatarBridge = AvatarBridge(sensing)
        avatarBridge.inbound = { data ->
            when (data.getString("type")) {
                "bump" -> { brain.onBump(data.getDouble("impact")); true }
                "lifeReady" -> { brain.onAvatarReady(); true }
                else -> false
            }
        }
        brain.toAvatar = mainThreadPoster { avatarBridge.post(it) }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    avatarBridge.onPageStarted()
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "Blocked".byteInputStream())
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.toString() != PAGE
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "vtubephone", setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, reply ->
                val receivedMs = SystemClock.elapsedRealtimeNanos() / 1e6
                if (!isMainFrame || sourceOrigin.toString() != ORIGIN) return@addWebMessageListener
                try {
                    val data = JSONObject(message.data ?: return@addWebMessageListener)
                    if (avatarBridge.handle(data, reply)) return@addWebMessageListener
                    when (data.getString("type")) {
                        "clockSync" -> {
                            reply.postMessage(JSONObject().put("type", "clockSync")
                                .put("jsSentMs", data.getDouble("sentMs"))
                                .put("nativeReceivedMs", receivedMs)
                                .put("nativeSentMs", SystemClock.elapsedRealtimeNanos() / 1e6).toString())
                        }
                        "haptic" -> {
                            val sent = data.optDouble("sentMs")
                            val offset = data.optDouble("clockOffsetMs")
                            val uncertainty = data.optDouble("uncertaintyMs")
                            if (sent.isFinite() && offset.isFinite() && uncertainty.isFinite() && uncertainty >= 0) {
                                val latencyUs = (receivedMs - sent - offset) * 1000
                                Log.i(HapticsService.TAG, "JS→native estimated=${"%.0f".format(latencyUs)}μs ±${"%.0f".format(uncertainty * 1000)}μs (clock-sync RTT/2)")
                            } else Log.i(HapticsService.TAG, "JS→native timing unavailable: clock sync pending")
                            val played = service.play(data.getString("id"))
                            reply.postMessage(JSONObject().put("type", "hapticResult").put("accepted", played).toString())
                        }
                    }
                } catch (e: RuntimeException) {
                    Log.w(HapticsService.TAG, "Rejected bridge message", e)
                } catch (e: org.json.JSONException) {
                    Log.w(HapticsService.TAG, "Invalid bridge JSON", e)
                }
            }
        }
        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(Button(this).apply {
            text = "⚙"; contentDescription = "設定を開く"; alpha = 0.6f; background = null; textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, FrameLayout.LayoutParams(64.dp, 64.dp, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = 32.dp; rightMargin = 12.dp
        })
        // Optional voice: hold to talk. Only with a Gemini key; the body stays the main way to talk.
        if (app.voice.llm != null) root.addView(Button(this).apply {
            text = "🎤"; contentDescription = "押している間だけ声で話しかける"; alpha = 0.7f; textSize = 30f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xCC1D1530.toInt()); setStroke(4, 0xFFFFD76A.toInt())
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
                        } else if (app.voice.recorder.start()) { v.alpha = 1f; v.isPressed = true }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 0.7f; v.isPressed = false
                        app.voice.recorder.stop()?.let { wav -> brain.onVoice(wav) }
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        }, FrameLayout.LayoutParams(72.dp, 72.dp, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
            bottomMargin = 24.dp; rightMargin = 12.dp
        })
        setContentView(root)
        webView.loadUrl(PAGE)
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    override fun onResume() { super.onResume(); service.suspend(false); sensing.start(); webView.onResume(); avatarBridge.resume(); brain.resume() }
    override fun onPause() { app.voice.recorder.stop(); brain.pause(); avatarBridge.pause(); sensing.stop(); service.suspend(true); webView.onPause(); super.onPause() }
    override fun onDestroy() { brain.toAvatar = null; avatarBridge.destroy(); webView.destroy(); super.onDestroy() }
    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE = "$ORIGIN/assets/web/index.html"
    }
}
