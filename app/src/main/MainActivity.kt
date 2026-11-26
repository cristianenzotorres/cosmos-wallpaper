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

class MainActivity : ComponentActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openWallpaperPicker()
        } else {
            Toast.makeText(
                this,
                "Sin permiso de micrófono, el fondo no reaccionará al audio.",
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
            text = "Define la animación como fondo vivo. El permiso de micrófono permite que reaccione al ritmo."
            textSize = 15f
            setTextColor(0xFFA1A1AA.toInt())
            setPadding(0, 0, 0, 48)
        })

        layout.addView(Button(this).apply {
            text = "Definir como Fondo de Pantalla"
            setOnClickListener {
                checkMicPermissionAndOpenPicker()
            }
        })

        setContentView(layout)
    }

    private fun checkMicPermissionAndOpenPicker() {
        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            openWallpaperPicker()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openWallpaperPicker() {
        val wallpaperIntent = Intent(
            WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
        ).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, CosmosWallpaperService::class.java)
            )
        }

        try {
            startActivity(wallpaperIntent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "No se pudo abrir el selector de fondos.",
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
        private var framePending = false
        private var engineDestroyed = false

        private val drawFrameRunnable = object : Runnable {
            override fun run() {
                this@CosmosWebViewEngine.framePending = false

                val view = this@CosmosWebViewEngine.webView
                if (
                    view == null ||
                    this@CosmosWebViewEngine.engineDestroyed ||
                    !this@CosmosWebViewEngine.isVisible
                ) {
                    return
                }

                var canvas: Canvas? = null
                try {
                    canvas = this@CosmosWebViewEngine.surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        view.draw(canvas)
                    }
                } catch (_: Exception) {
                    // Android puede destruir o reemplazar la superficie durante el dibujo.
                } finally {
                    if (canvas != null) {
                        try {
                            this@CosmosWebViewEngine.surfaceHolder
                                .unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                            // La superficie ya no está disponible.
                        }
                    }
                }

                this@CosmosWebViewEngine.scheduleNextFrame()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            try {
                val view = WebView(this@CosmosWallpaperService)
                this@CosmosWebViewEngine.webView = view

                view.setBackgroundColor(Color.BLACK)
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = false
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                view.webViewClient = WebViewClient()

                view.webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        this@CosmosWebViewEngine.mainHandler.post {
                            val audioOnly = request.resources.isNotEmpty() &&
                                request.resources.all { resource ->
                                    resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                }

                            val micPermissionGranted =
                                ContextCompat.checkSelfPermission(
                                    this@CosmosWallpaperService,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                            if (
                                audioOnly &&
                                micPermissionGranted &&
                                !this@CosmosWebViewEngine.engineDestroyed
                            ) {
                                request.grant(
                                    arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                )
                            } else {
                                request.deny()
                            }
                        }
                    }
                }

                view.loadUrl(
                    "file:///android_asset/fondo-celular-audioritmico.html"
                )
            } catch (exception: RuntimeException) {
                android.util.Log.e(
                    "CosmosWallpaper",
                    "No se pudo crear o cargar el WebView.",
                    exception
                )
                this@CosmosWebViewEngine.webView?.destroy()
                this@CosmosWebViewEngine.webView = null
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
            scheduleNextFrame()
        }

        private fun resizeWebView(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return

            val view = this@CosmosWebViewEngine.webView ?: return
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                width,
                View.MeasureSpec.EXACTLY
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(
                height,
                View.MeasureSpec.EXACTLY
            )

            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                this@CosmosWebViewEngine.webView?.onResume()
                scheduleNextFrame()
            } else {
                this@CosmosWebViewEngine.mainHandler.removeCallbacks(
                    this@CosmosWebViewEngine.drawFrameRunnable
                )
                this@CosmosWebViewEngine.framePending = false
                this@CosmosWebViewEngine.webView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            this@CosmosWebViewEngine.webView?.dispatchTouchEvent(event)
            super.onTouchEvent(event)
        }

        private fun scheduleNextFrame() {
            if (
                this@CosmosWebViewEngine.engineDestroyed ||
                !this@CosmosWebViewEngine.isVisible ||
                this@CosmosWebViewEngine.framePending ||
                this@CosmosWebViewEngine.webView == null
            ) {
                return
            }

            this@CosmosWebViewEngine.framePending = true
            this@CosmosWebViewEngine.mainHandler.postDelayed(
                this@CosmosWebViewEngine.drawFrameRunnable,
                16L
            )
        }

        override fun onDestroy() {
            this@CosmosWebViewEngine.engineDestroyed = true
            this@CosmosWebViewEngine.mainHandler.removeCallbacks(
                this@CosmosWebViewEngine.drawFrameRunnable
            )
            this@CosmosWebViewEngine.framePending = false

            this@CosmosWebViewEngine.webView?.apply {
                stopLoading()
                onPause()
                removeAllViews()
                destroy()
            }
            this@CosmosWebViewEngine.webView = null

            super.onDestroy()
        }
    }
}
