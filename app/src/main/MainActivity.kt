package com.cosmos.audiorhythmwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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

class MainActivity : ComponentActivity() {
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openWallpaperPicker()
        } else {
            Toast.makeText(
                this,
                "Sin permiso de micrófono el fondo no podrá reaccionar al audio.",
                Toast.LENGTH_LONG
            ).show()
            openWallpaperPicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            setBackgroundColor(0xFF09090B.toInt())
        }

        layout.addView(TextView(this).apply {
            text = "Cosmos Audiorítmico\nFondo Vivo"
            textSize = 26f
            setTextColor(0xFFFAFAFA.toInt())
            setPadding(0, 0, 0, 24)
        })

        layout.addView(TextView(this).apply {
            text = "El permiso de micrófono permite que la animación reaccione al ritmo."
            textSize = 15f
            setTextColor(0xFFA1A1AA.toInt())
            setPadding(0, 0, 0, 48)
        })

        layout.addView(Button(this).apply {
            text = "Definir como Fondo de Pantalla"
            setOnClickListener {
                checkMicAndLaunchWallpaper()
            }
        })

        setContentView(layout)
    }

    private fun checkMicAndLaunchWallpaper() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openWallpaperPicker()
        } else {
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
                Toast.makeText(
                    this,
                    "No se pudo abrir el selector de fondos",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

class CosmosWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = CosmosWebViewEngine()

    inner class CosmosWebViewEngine : Engine() {
        private val mainHandler = Handler(Looper.getMainLooper())
        private var webView: WebView? = null
        private var frameScheduled = false
        private var destroyed = false

        private val drawFrameRunnable = object : Runnable {
            override fun run() {
                frameScheduled = false
                val view = webView ?: return
                if (destroyed || !isVisible) return

                var canvas: android.graphics.Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        view.draw(canvas)
                    }
                } catch (_: Exception) {
                    // La superficie puede desaparecer al cambiar la visibilidad.
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                            // Surface destruida durante el dibujo.
                        }
                    }
                }

                scheduleFrame()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            try {
                webView = WebView(this@CosmosWallpaperService).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = true
                        allowContentAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    webViewClient = WebViewClient()

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            mainHandler.post {
                                val audioOnly =
                                    request.resources.isNotEmpty() &&
                                    request.resources.all {
                                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                    }

                                val micGranted =
                                    ContextCompat.checkSelfPermission(
                                        this@CosmosWallpaperService,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                if (audioOnly && micGranted && !destroyed) {
                                    request.grant(
                                        arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                    )
                                } else {
                                    request.deny()
                                }
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            request.deny()
                        }
                    }

                    loadUrl(
                        "file:///android_asset/fondo-celular-audioritmico.html"
                    )
                }
            } catch (_: RuntimeException) {
                webView?.destroy()
                webView = null
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            resizeWebView(width, height)
            scheduleFrame()
        }

        private fun resizeWebView(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return

            webView?.let { view ->
                val widthSpec =
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val heightSpec =
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)

                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, width, height)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                webView?.onResume()
                scheduleFrame()
            } else {
                mainHandler.removeCallbacks(drawFrameRunnable)
                frameScheduled = false
                webView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event != null) {
                webView?.dispatchTouchEvent(event)
            }
            super.onTouchEvent(event)
        }

        private fun scheduleFrame() {
            if (destroyed || !isVisible || frameScheduled || webView == null) {
                return
            }

            frameScheduled = true
            mainHandler.post(drawFrameRunnable)
        }

        override fun onDestroy() {
            destroyed = true
            mainHandler.removeCallbacks(drawFrameRunnable)
            frameScheduled = false

            webView?.apply {
                stopLoading()
                onPause()
                removeAllViews()
                destroy()
            }

            webView = null
            super.onDestroy()
        }
    }
}
