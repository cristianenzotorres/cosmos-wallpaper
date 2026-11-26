package com.cosmos.audiorhythmwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val STUDIO_PATH = "/assets/Cosmos%20Generativo.html"

private fun createAssetLoader(context: android.content.Context): WebViewAssetLoader =
    WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
        .build()

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
    }
}

private const val WEB_AUDIO_FIX = """
(function() {
  if (window.__cosmosAudioControlsInstalled) return;
  window.__cosmosAudioControlsInstalled = true;

  var rhythmButton = document.getElementById('rhythmToggleBtn');
  if (rhythmButton) {
    rhythmButton.addEventListener('click', function(event) {
      event.preventDefault(); event.stopImmediatePropagation();
      if (window.CosmosApp) window.CosmosApp.enableAudioReaction();
    }, true);
  }
  var installButton = document.getElementById('installWallpaperBtn');
  if (installButton) {
    installButton.addEventListener('click', function(event) {
      event.preventDefault(); event.stopImmediatePropagation();
      if (window.CosmosApp) window.CosmosApp.setLiveWallpaper();
    }, true);
  }

  // The studio asset's sound button had no handler. Create a quiet Web Audio theremin
  // directly from the user gesture, then glide its pitch while the canvas is touched.
  var soundButton = document.getElementById('audioToggleBtn');
  var iconOff = document.getElementById('audioIconOff');
  var iconOn = document.getElementById('audioIconOn');
  var audioContext = null, oscillator = null, voiceGain = null, filter = null;
  var soundOn = false, pointerIsDown = false;
  function ensureAudio() {
    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) throw new Error('Web Audio no está disponible en este dispositivo');
    if (!audioContext || audioContext.state === 'closed') {
      audioContext = new AudioContextClass();
      oscillator = audioContext.createOscillator();
      oscillator.type = 'sine';
      filter = audioContext.createBiquadFilter();
      filter.type = 'lowpass';
      filter.frequency.value = 1800;
      voiceGain = audioContext.createGain();
      voiceGain.gain.value = 0;
      oscillator.connect(filter); filter.connect(voiceGain); voiceGain.connect(audioContext.destination);
      oscillator.start();
    }
    if (audioContext.state === 'suspended') audioContext.resume();
  }
  function updateVoice(x, y, active) {
    if (!audioContext || !oscillator || !voiceGain) return;
    var now = audioContext.currentTime;
    var width = Math.max(1, window.innerWidth), height = Math.max(1, window.innerHeight);
    var vertical = Math.max(0, Math.min(1, y / height));
    var horizontal = Math.max(0, Math.min(1, x / width));
    oscillator.frequency.setTargetAtTime(220 * Math.pow(2, 2 * (1 - vertical)), now, 0.045);
    filter.frequency.setTargetAtTime(700 + horizontal * 4000, now, 0.08);
    voiceGain.gain.setTargetAtTime(active && soundOn ? 0.12 : 0, now, active ? 0.05 : 0.15);
  }
  if (soundButton) {
    soundButton.addEventListener('click', function(event) {
      event.preventDefault(); event.stopImmediatePropagation();
      try {
        ensureAudio(); soundOn = !soundOn;
        if (iconOff) iconOff.classList.toggle('hidden', soundOn);
        if (iconOn) iconOn.classList.toggle('hidden', !soundOn);
        if (soundOn && pointerIsDown) updateVoice(window.innerWidth / 2, window.innerHeight / 2, true);
        else if (!soundOn && voiceGain) voiceGain.gain.setTargetAtTime(0, audioContext.currentTime, 0.12);
      } catch (error) {
        soundOn = false;
        var toast = document.getElementById('toast');
        if (toast) { toast.textContent = error.message || 'No se pudo iniciar el audio'; toast.classList.remove('opacity-0'); }
      }
    }, true);
  }
  var artwork = document.getElementById('artCanvas');
  if (artwork) {
    artwork.addEventListener('pointerdown', function(event) {
      pointerIsDown = true;
      if (soundOn) updateVoice(event.clientX, event.clientY, true);
    }, {passive:true});
    artwork.addEventListener('pointermove', function(event) {
      if (pointerIsDown && soundOn) updateVoice(event.clientX, event.clientY, true);
    }, {passive:true});
    ['pointerup','pointercancel','lostpointercapture'].forEach(function(type) {
      artwork.addEventListener(type, function() {
        pointerIsDown = false;
        if (soundOn && audioContext) updateVoice(0, 0, false);
      }, {passive:true});
    });
  }
})();
"""

private fun installWebPermissionHandler(
    view: WebView,
    context: android.content.Context,
    handler: Handler,
    isDestroyed: () -> Boolean
) {
    view.webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            handler.post {
                val origin = request.origin
                val trusted = origin?.scheme == "https" && origin.host == "appassets.androidplatform.net"
                val audioOnly = request.resources.isNotEmpty() &&
                    request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                val micGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (trusted && audioOnly && micGranted && !isDestroyed()) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingMicAction: (() -> Unit)? = null
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "Permite el micrófono en Ajustes para activar el modo audiorítmico.", Toast.LENGTH_LONG).show()
    }
    private val assetLoader by lazy { createAssetLoader(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.rgb(9, 9, 11))
        configureWebView(view)
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(WEB_AUDIO_FIX, null)
            }
        }
        installWebPermissionHandler(view, this, Handler(Looper.getMainLooper())) { isFinishing || isDestroyed }
        setContentView(view)
        view.loadUrl(ASSET_ORIGIN + STUDIO_PATH)
    }

    private fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMicAction = action
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
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
            try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            catch (_: Exception) { Toast.makeText(this, "No se pudo abrir el selector de fondos.", Toast.LENGTH_LONG).show() }
        }
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun enableAudioReaction() {
            runOnUiThread {
                withMicrophonePermission {
                    webView?.evaluateJavascript(
                        "if (typeof window.toggleRhythmMode === 'function') window.toggleRhythmMode();",
                        null
                    )
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun setLiveWallpaper() { runOnUiThread { openWallpaperPicker() } }
    }

    override fun onResume() { super.onResume(); webView?.onResume() }
    override fun onPause() { webView?.onPause(); super.onPause() }
    override fun onDestroy() {
        webView?.apply { stopLoading(); removeJavascriptInterface("CosmosApp"); removeAllViews(); destroy() }
        webView = null
        super.onDestroy()
    }
}

class CosmosWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = CosmosWebViewEngine()

    inner class CosmosWebViewEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val assetLoader by lazy { createAssetLoader(this@CosmosWallpaperService) }
        private var webView: WebView? = null
        private var framePending = false
        private var destroyed = false

        private val drawFrame = object : Runnable {
            override fun run() {
                framePending = false
                val view = webView ?: return
                if (destroyed || !isVisible) return
                view.invalidate()
                var canvas: Canvas? = null
                try { canvas = surfaceHolder.lockCanvas(); if (canvas != null) view.draw(canvas) }
                catch (_: Exception) {}
                finally { if (canvas != null) try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) {} }
                scheduleFrame()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            val view = WebView(this@CosmosWallpaperService)
            webView = view
            view.setBackgroundColor(Color.BLACK)
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            configureWebView(view)
            view.webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        "(function(){['controlPanel','togglePanelBtn','wallpaperModal','exitWallpaperBtn','toast'].forEach(function(id){var e=document.getElementById(id);if(e)e.style.display='none'});if(typeof resizeCanvas==='function')resizeCanvas()})();",
                        null
                    )
                    view?.evaluateJavascript(WEB_AUDIO_FIX, null)
                }
            }
            installWebPermissionHandler(view, this@CosmosWallpaperService, handler) { destroyed }
            view.loadUrl(ASSET_ORIGIN + STUDIO_PATH + "?nativeWallpaper=1")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            resizeWebView(width, height)
            scheduleFrame()
        }

        private fun resizeWebView(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val view = webView ?: return
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            view.evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas();", null)
            view.invalidate()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) { webView?.onResume(); scheduleFrame() }
            else { handler.removeCallbacks(drawFrame); framePending = false; webView?.onPause() }
        }
        override fun onTouchEvent(event: MotionEvent) { webView?.dispatchTouchEvent(event); super.onTouchEvent(event) }
        private fun scheduleFrame() {
            if (destroyed || !isVisible || framePending || webView == null) return
            framePending = true
            handler.postDelayed(drawFrame, 16L)
        }
        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacks(drawFrame)
            framePending = false
            webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }
            webView = null
            super.onDestroy()
        }
    }
}
