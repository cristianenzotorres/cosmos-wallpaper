package com.cosmos.audiorhythmwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var pendingMicAction: (() -> Unit)? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else Toast.makeText(
            this,
            "Puedes usar el estudio sin micrófono; la reacción musical requiere este permiso.",
            Toast.LENGTH_LONG
        ).show()
    }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.rgb(9, 9, 11))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installNativeActions(view)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val audioOnly = request.resources.isNotEmpty() &&
                        request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    val micGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (audioOnly && micGranted) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        request.deny()
                    }
                }
            }
        }

        setContentView(view)
        view.loadUrl("https://appassets.androidplatform.net/assets/fondo-celular-audioritmico.html")
    }

    private fun installNativeActions(view: WebView?) {
        val script = """
            (function() {
                var rhythm = document.getElementById('rhythmToggleBtn');
                if (rhythm && !rhythm.dataset.nativeHooked) {
                    rhythm.dataset.nativeHooked = 'true';
                    rhythm.addEventListener('click', function(event) {
                        event.preventDefault();
                        event.stopImmediatePropagation();
                        CosmosApp.enableAudioReaction();
                    }, true);
                }
                var install = document.getElementById('installWallpaperBtn');
                if (install && !install.dataset.nativeHooked) {
                    install.dataset.nativeHooked = 'true';
                    install.addEventListener('click', function(event) {
                        event.preventDefault();
                        event.stopImmediatePropagation();
                        CosmosApp.setLiveWallpaper();
                    }, true);
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun requireMicrophone(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingMicAction = onGranted
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioReaction() {
        requireMicrophone {
            webView?.evaluateJavascript("window.toggleRhythmMode && window.toggleRhythmMode();", null)
        }
    }

    private fun openWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, CosmosWallpaperService::class.java)
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de fondos.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun enableAudioReaction() {
            runOnUiThread { startAudioReaction() }
        }

        @android.webkit.JavascriptInterface
        fun setLiveWallpaper() {
            runOnUiThread {
                requireMicrophone { openWallpaperPicker() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("CosmosApp")
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

class CosmosWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CosmosWebViewEngine()

    inner class CosmosWebViewEngine : Engine() {
        private val mainHandler = Handler(Looper.getMainLooper())
        private var webView: WebView? = null
        private var framePending = false
        private var engineDestroyed = false

        private val drawFrameRunnable = object : Runnable {
            override fun run() {
                framePending = false
                val view = webView
                if (view == null || engineDestroyed || !isVisible) return
                view.invalidate()

                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) view.draw(canvas)
                } catch (_: Exception) {
                } finally {
                    if (canvas != null) {
                        try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }
                scheduleNextFrame()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            try {
                val view = WebView(this@CosmosWallpaperService)
                webView = view
                view.setBackgroundColor(Color.BLACK)
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val script = """
                            (function() {
                                ['controlPanel','togglePanelBtn','wallpaperModal','exitWallpaperBtn','toast'].forEach(function(id){
                                    var el=document.getElementById(id); if(el) el.style.display='none';
                                });
                                if(typeof resizeCanvas==='function') resizeCanvas();
                                if(typeof toggleRhythmMode==='function' && !window.__cosmosNativeRhythmStarted){
                                    window.__cosmosNativeRhythmStarted=true; toggleRhythmMode();
                                }
                            })();
                        """.trimIndent()
                        view?.post { view.evaluateJavascript(script, null); view.invalidate() }
                    }
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        mainHandler.post {
                            val audioOnly = request.resources.isNotEmpty() &&
                                request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                            val micGranted = ContextCompat.checkSelfPermission(
                                this@CosmosWallpaperService,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (audioOnly && micGranted && !engineDestroyed) {
                                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            } else request.deny()
                        }
                    }
                }
                view.loadUrl("file:///android_asset/fondo-celular-audioritmico.html?nativeWallpaper=1")
            } catch (exception: RuntimeException) {
                android.util.Log.e("CosmosWallpaper", "No se pudo cargar el WebView", exception)
                webView?.destroy()
                webView = null
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            resizeWebView(width, height)
            scheduleNextFrame()
        }

        private fun resizeWebView(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val view = webView ?: return
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
            view.evaluateJavascript("if(typeof resizeCanvas==='function') resizeCanvas();", null)
            view.invalidate()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                webView?.onResume()
                scheduleNextFrame()
            } else {
                mainHandler.removeCallbacks(drawFrameRunnable)
                framePending = false
                webView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            webView?.dispatchTouchEvent(event)
            super.onTouchEvent(event)
        }

        private fun scheduleNextFrame() {
            if (engineDestroyed || !isVisible || framePending || webView == null) return
            framePending = true
            mainHandler.postDelayed(drawFrameRunnable, 16L)
        }

        override fun onDestroy() {
            engineDestroyed = true
            mainHandler.removeCallbacks(drawFrameRunnable)
            framePending = false
            webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }
            webView = null
            super.onDestroy()
        }
    }
}
