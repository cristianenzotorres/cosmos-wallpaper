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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

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
            "El modo audiorítmico necesita permiso de micrófono. Puedes seguir usando las demás opciones.",
            Toast.LENGTH_LONG
        ).show()
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
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(INTERFACE_SCRIPT, null)
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
        view.loadUrl("file:///android_asset/Cosmos%20Generativo.html")
    }

    private fun requireMicrophone(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            pendingMicAction = onGranted
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
            runOnUiThread {
                requireMicrophone {
                    webView?.evaluateJavascript(
                        "window.toggleRhythmMode && window.toggleRhythmMode();",
                        null
                    )
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun setLiveWallpaper() {
            runOnUiThread { requireMicrophone { openWallpaperPicker() } }
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

    private companion object {
        val INTERFACE_SCRIPT = """
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

                // The provided HTML did not attach a handler to its audio button.
                // Add a small Web Audio instrument directly in the trusted click handler.
                var audioButton = document.getElementById('audioToggleBtn');
                var audioOff = document.getElementById('audioIconOff');
                var audioOn = document.getElementById('audioIconOn');
                if (audioButton && !audioButton.dataset.audioHooked) {
                    audioButton.dataset.audioHooked = 'true';
                    var audioContext = null;
                    var oscillators = [];
                    var master = null;
                    var selectedTheme = 'angels';
                    var themes = {
                        angels: { wave: 'sine', notes: [261.63, 329.63, 392.00, 523.25] },
                        soul: { wave: 'triangle', notes: [293.66, 369.99, 440.00, 587.33] },
                        cosmos: { wave: 'sine', notes: [130.81, 196.00, 261.63, 392.00] },
                        sanctuary: { wave: 'sine', notes: [164.81, 220.00, 277.18, 329.63] },
                        crystal: { wave: 'triangle', notes: [392.00, 493.88, 587.33, 783.99] },
                        rain: { wave: 'sine', notes: [110.00, 164.81, 220.00, 329.63] },
                        aurora: { wave: 'triangle', notes: [174.61, 261.63, 349.23, 440.00] },
                        eternity: { wave: 'sine', notes: [98.00, 146.83, 196.00, 293.66] }
                    };
                    var timer = null;
                    var noteIndex = 0;
                    function stopAudio() {
                        if (timer) { clearInterval(timer); timer = null; }
                        if (master && audioContext) {
                            master.gain.cancelScheduledValues(audioContext.currentTime);
                            master.gain.setTargetAtTime(0, audioContext.currentTime, 0.12);
                        }
                        oscillators.forEach(function(osc) { try { osc.stop(); } catch (_) {} });
                        oscillators = [];
                        if (audioContext) { audioContext.close().catch(function(){}); audioContext = null; }
                        master = null;
                        if (audioOff) audioOff.classList.remove('hidden');
                        if (audioOn) audioOn.classList.add('hidden');
                        audioButton.dataset.audioActive = 'false';
                    }
                    function startAudio() {
                        var AudioContextClass = window.AudioContext || window.webkitAudioContext;
                        if (!AudioContextClass) return;
                        audioContext = new AudioContextClass();
                        var profile = themes[selectedTheme] || themes.angels;
                        master = audioContext.createGain();
                        master.gain.value = 0.0001;
                        master.connect(audioContext.destination);
                        profile.notes.forEach(function(freq, index) {
                            var osc = audioContext.createOscillator();
                            var voiceGain = audioContext.createGain();
                            osc.type = profile.wave;
                            osc.frequency.value = freq;
                            voiceGain.gain.value = index === 0 ? 0.14 : 0.055;
                            osc.connect(voiceGain);
                            voiceGain.connect(master);
                            osc.start();
                            oscillators.push(osc);
                        });
                        master.gain.setTargetAtTime(0.55, audioContext.currentTime, 0.3);
                        timer = setInterval(function() {
                            if (!audioContext || !oscillators.length) return;
                            var current = themes[selectedTheme] || themes.angels;
                            noteIndex = (noteIndex + 1) % current.notes.length;
                            oscillators.forEach(function(osc, index) {
                                osc.type = current.wave;
                                osc.frequency.setTargetAtTime(current.notes[(noteIndex + index) % current.notes.length], audioContext.currentTime, 0.35);
                            });
                        }, 1800);
                        if (audioOff) audioOff.classList.add('hidden');
                        if (audioOn) audioOn.classList.remove('hidden');
                        audioButton.dataset.audioActive = 'true';
                    }
                    audioButton.addEventListener('click', function(event) {
                        event.preventDefault();
                        event.stopImmediatePropagation();
                        if (audioButton.dataset.audioActive === 'true') stopAudio();
                        else startAudio();
                    }, true);

                    var panel = document.getElementById('controlPanel');
                    if (panel && !document.getElementById('soundThemeSelect')) {
                        var section = document.createElement('div');
                        section.className = 'space-y-1.5';
                        section.innerHTML = '<label for="soundThemeSelect" class="block text-xs font-medium text-zinc-300">Atmósfera sonora</label>' +
                            '<select id="soundThemeSelect" class="w-full rounded-xl border border-zinc-700 bg-zinc-800 px-3 py-2 text-xs text-white">' +
                            '<option value="angels">Ángeles</option><option value="soul">Alma</option><option value="cosmos">Cosmos</option>' +
                            '<option value="sanctuary">Santuario</option><option value="crystal">Cristal</option><option value="rain">Lluvia</option>' +
                            '<option value="aurora">Aurora</option><option value="eternity">Eternidad</option></select>';
                        var description = panel.querySelector('p');
                        if (description && description.nextSibling) panel.insertBefore(section, description.nextSibling);
                        else panel.insertBefore(section, panel.firstChild);
                        document.getElementById('soundThemeSelect').addEventListener('change', function(event) {
                            selectedTheme = event.target.value;
                            if (audioButton.dataset.audioActive === 'true') {
                                stopAudio();
                                startAudio();
                            }
                        });
                    }
                }
            })();
        """.trimIndent()
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
                view.loadUrl("file:///android_asset/Cosmos%20Generativo.html?nativeWallpaper=1")
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
