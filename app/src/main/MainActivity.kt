package com.cosmos.audiorhythmwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
                "Se necesita el micrófono para que el fondo reaccione a la música",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            setBackgroundColor(0xFF09090B.toInt())
        }

        val title = TextView(this).apply {
            text = "Cosmos Audiorítmico\nFondo Vivo"
            textSize = 26f
            setTextColor(0xFFFAFAFA.toInt())
            setPadding(0, 0, 0, 24)
        }

        val subtitle = TextView(this).apply {
            text = "Este servicio coloca tu visualización interactiva detrás de los íconos de tu celular, reaccionando al tacto y al ritmo de la música en tiempo real."
            textSize = 15f
            setTextColor(0xFFA1A1AA.toInt())
            setPadding(0, 0, 0, 64)
        }

        val applyBtn = Button(this).apply {
            text = "Definir como Fondo de Pantalla"
            textSize = 16f
            setBackgroundColor(0xFF9333EA.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 36, 48, 36)
            setOnClickListener {
                checkMicAndLaunchWallpaper()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(applyBtn)
        setContentView(layout)
    }

    private fun checkMicAndLaunchWallpaper() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMic) {
            openWallpaperPicker()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openWallpaperPicker() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, CosmosWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}

class CosmosWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return CosmosWebViewEngine()
    }

    inner class CosmosWebViewEngine : Engine() {
        private var webView: WebView? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            webView = WebView(applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }
                }

                webViewClient = WebViewClient()
                loadUrl("file:///android_asset/fondo-celular-audioritmico.html")
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            webView?.let { wv ->
                val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                wv.measure(widthSpec, heightSpec)
                wv.layout(0, 0, width, height)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                webView?.onResume()
                drawFrame()
            } else {
                webView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event != null) {
                webView?.dispatchTouchEvent(event)
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            val wv = webView ?: return
            if (!isVisible) return

            try {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    wv.draw(canvas)
                    holder.unlockCanvasAndPost(canvas)
                }
            } catch (_: Exception) {
            }

            wv.postDelayed({ drawFrame() }, 16L)
        }

        override fun onDestroy() {
            webView?.destroy()
            webView = null
            super.onDestroy()
        }
    }
}
