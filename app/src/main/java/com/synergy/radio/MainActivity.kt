package com.synergy.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Synchronisation bidirectionnelle : État du lecteur natif (Play/Pause/Titre) vers la WebView
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SYNC_STATE) {
                val isPlaying = intent.getBooleanExtra("isPlaying", false)
                val title = intent.getStringExtra("title")?.replace("'", "\\'") ?: ""
                val artist = intent.getStringExtra("artist")?.replace("'", "\\'") ?: ""
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(typeof window.onNativePlaybackStateChanged === 'function') { window.onNativePlaybackStateChanged($isPlaying, '$title', '$artist'); }",
                        null
                    )
                }
            }
        }
    }

    // Demande de permission au runtime pour les notifications (Android 13+ / API 33+)
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Accordé ou refusé */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Permission Android 13+ pour afficher la notification du lecteur sans blocage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
        }

        webView.webViewClient = WebViewClient()
        // Évite le blocage du thread UI par les alertes/prompts JavaScript
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                result?.confirm(defaultValue ?: "")
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter(ACTION_SYNC_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    // Gestion du cycle de vie : Résout le problème de freeze tactile lors des allers-retours en arrière-plan
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {}
        webView.destroy()
        super.onDestroy()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun playStream(url: String) {
            val intent = Intent(this@MainActivity, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY
                putExtra("url", url)
                putExtra("startPlaying", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this@MainActivity.startForegroundService(intent)
            } else {
                this@MainActivity.startService(intent)
            }
        }

        @JavascriptInterface
        fun pauseStream() {
            val intent = Intent(RadioService.ACTION_PAUSE).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        @JavascriptInterface
        fun toggleStream() {
            val intent = Intent(RadioService.ACTION_TOGGLE).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        @JavascriptInterface
        fun updateMetadata(title: String, artist: String) {
            val intent = Intent(RadioService.ACTION_UPDATE_META).apply {
                putExtra("title", title)
                putExtra("artist", artist)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        @JavascriptInterface
        fun startForegroundService() {
            toggleStream()
        }

        @JavascriptInterface
        fun stopForegroundService() {
            pauseStream()
        }
    }

    companion object {
        const val ACTION_SYNC_STATE = "com.synergy.radio.SYNC_STATE"
    }
}
