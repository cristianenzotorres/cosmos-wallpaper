package com.cosmos.audiorhythmwallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

private const val ASSET = "file:///android_asset/Cosmos%20Generativo.html"
private const val CHANNEL = "cosmos_capture"

private object SharedBands {
    @Volatile var json = "{\"bass\":0,\"low\":0,\"vocal\":0,\"treble\":0,\"energy\":0,\"kick\":0}"
    private val listeners = mutableMapOf<String, (String) -> Unit>()
    @Synchronized fun subscribe(id: String, listener: (String) -> Unit) { listeners[id] = listener; listener(json) }
    @Synchronized fun unsubscribe(id: String) { listeners.remove(id) }
    @Synchronized fun publish(value: String) { json = value; listeners.values.toList().forEach { it(value) } }
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = true
        allowContentAccess = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
}

private const val BRIDGE = """
(function(){
 if(window.__cosmosBridge)return;window.__cosmosBridge=true;
 window.__cosmosBands={bass:0,low:0,vocal:0,treble:0,energy:0,kick:0};
 window.setCosmosBands=function(b){if(!b)return;if(b.error){var s=document.getElementById('beatStatus');if(s)s.textContent=b.error;return}window.__cosmosBands=b;var vals=[b.bass||0,b.low||0,b.vocal||0,b.treble||0];var ids=[['bassPct','bassBar'],['lowMidPct','lowMidBar'],['vocalPct','vocalBar'],['treblePct','trebleBar']];ids.forEach(function(p,i){var n=Math.round(vals[i]*100),l=document.getElementById(p[0]),bar=document.getElementById(p[1]);if(l)l.textContent=n+'%';if(bar)bar.style.width=n+'%'});var st=document.getElementById('beatStatus');if(st)st.textContent=(b.kick||0)>.3?'Bombo detectado':vals[2]>.2?'Voz / melodía':vals[1]>.2?'Bajo / acordes':vals[3]>.18?'Agudos':(b.energy||0)>.025?'Audio interno en vivo':'Esperando reproducción'};
 var meter=document.getElementById('rhythmMeterBox');if(meter)meter.classList.remove('hidden');
 var rhythm=document.getElementById('rhythmToggleBtn');if(rhythm&&!rhythm.__cosmos){rhythm.__cosmos=true;rhythm.addEventListener('click',function(e){e.preventDefault();e.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.enableAudioReaction()},true)}
 var install=document.getElementById('installWallpaperBtn');if(install&&!install.__cosmos){install.__cosmos=true;install.addEventListener('click',function(e){e.preventEvent?.();e.preventDefault();e.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.setLiveWallpaper()},true)}
})();
"""

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var captureActive = false

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, PlaybackCaptureService::class.java).apply {
                action = PlaybackCaptureService.ACTION_START
                putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
            captureActive = true
            Toast.makeText(this, "Captura activa. Reproduce música en RiPlay.", Toast.LENGTH_LONG).show()
        } else Toast.makeText(this, "No se autorizó la captura.", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedBands.subscribe("activity") { json -> runOnUiThread { webView?.evaluateJavascript("window.setCosmosBands&&window.setCosmosBands($json)", null) } }
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.rgb(9, 9, 11))
        setupWebView(view)
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(BRIDGE, null)
                view?.evaluateJavascript("window.setCosmosBands&&window.setCosmosBands(${SharedBands.json})", null)
            }
        }
        setContentView(view)
        view.loadUrl(ASSET)
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Android 10 o superior es necesario.", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun enableAudioReaction() {
            runOnUiThread {
                if (captureActive) {
                    captureActive = false
                    stopService(Intent(this@MainActivity, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
                } else requestCapture()
            }
        }

        @android.webkit.JavascriptInterface
        fun setLiveWallpaper() {
            runOnUiThread {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, CosmosWallpaperService::class.java))
                }
                try { startActivity(intent) } catch (_: Exception) {
                    try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
                    catch (_: Exception) { Toast.makeText(this@MainActivity, "No se pudo abrir el selector.", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onDestroy() {
        SharedBands.unsubscribe("activity")
        stopService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
        webView?.apply { stopLoading(); removeJavascriptInterface("CosmosApp"); removeAllViews(); destroy() }
        webView = null
        super.onDestroy()
    }
}

class PlaybackCaptureService : Service() {
    companion object {
        const val ACTION_START = "cosmos.START_PLAYBACK"
        const val ACTION_STOP = "cosmos.STOP_PLAYBACK"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private var projection: MediaProjection? = null
    private val analyzer = PlaybackAnalyzer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        createChannel()
        startForegroundNotification()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION") val data = intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        if (code != Activity.RESULT_OK || data == null) { reportError("No se recibió la autorización"); stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            analyzer.start(projection!!) { SharedBands.publish(it) }
            if (!analyzer.isRunning()) { reportError("No se pudo iniciar la captura"); stopCapture(); stopSelf() }
        } catch (e: Exception) {
            reportError("Captura no disponible: " + (e.localizedMessage ?: "error"))
            stopCapture(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Captura de audio", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Cosmos audio interno")
            .setContentText("Analizando reproducción de RiPlay")
            .setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(null, "Detener", pending).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7102, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7102, notification)
    }

    private fun reportError(message: String) { SharedBands.publish("{\"error\":" + org.json.JSONObject.quote(message) + "}") }

    private fun stopCapture() {
        analyzer.stop()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
}

private class PlaybackAnalyzer {
    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    fun isRunning() = running

    @Synchronized fun stop() {
        running = false
        recorder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        recorder = null
        worker?.interrupt()
        worker = null
    }

    @Synchronized fun start(projection: MediaProjection, onBands: (String) -> Unit) {
        if (running || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        val record = try { AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuffer, 4096)).setAudioPlaybackCaptureConfig(config).build() } catch (_: Exception) { return }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return }
        recorder = record
        running = true
        worker = thread(name = "cosmos-playback", start = true) {
            val samples = ShortArray(1024)
            var bassEnvelope = 0f
            try {
                record.startRecording()
                while (running && !Thread.currentThread().isInterrupted) {
                    val count = record.read(samples, 0, samples.size)
                    if (count <= 0) continue
                    var square = 0.0
                    for (i in 0 until count) { val value = samples[i].toDouble(); square += value * value }
                    val rms = sqrt(square / count)
                    val bass = band(samples, count, intArrayOf(45,65,85,110,135))
                    val low = band(samples, count, intArrayOf(160,210,270,340,420))
                    val vocal = band(samples, count, intArrayOf(500,700,950,1300,1800,2400))
                    val treble = band(samples, count, intArrayOf(3000,4000,5200,6500))
                    val energy = (rms / 10000.0).toFloat().coerceIn(0f, 1f)
                    val kick = (bass - bassEnvelope - 0.04f).coerceIn(0f, 1f)
                    bassEnvelope = bassEnvelope * 0.82f + bass * 0.18f
                    onBands("{\"bass\":" + bass + ",\"low\":" + low + ",\"vocal\":" + vocal + ",\"treble\":" + treble + ",\"energy\":" + energy + ",\"kick\":" + kick + "}")
                    Thread.sleep(24)
                }
            } catch (_: Exception) {
            } finally {
                running = false
                if (recorder === record) {
                    recorder = null
                    try { record.stop() } catch (_: Exception) {}
                    try { record.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun band(samples: ShortArray, count: Int, frequencies: IntArray): Float {
        if (count < 2) return 0f
        var total = 0.0
        for (frequency in frequencies) {
            val bin = (0.5 + count * frequency.toDouble() / 16000.0).toInt()
            val omega = 2.0 * PI * bin / count
            val coefficient = 2.0 * cos(omega)
            var previous = 0.0
            var previous2 = 0.0
            for (i in 0 until count) {
                val sample = samples[i] / 32768.0
                val current = sample + coefficient * previous - previous2
                previous2 = previous
                previous = current
            }
            val power = (previous * previous + previous2 * previous2 - coefficient * previous * previous2).coerceAtLeast(0.0)
            total += sqrt(power) / count
        }
        return ((total / frequencies.size) * 18.0).toFloat().coerceIn(0f, 1f)
    }
}

class CosmosWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = CosmosEngine()

    inner class CosmosEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val listenerKey = "wall-${System.identityHashCode(this)}"
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
            setupWebView(view)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript("window.__cosmosWallpaperMode=true", null)
                    view?.evaluateJavascript(BRIDGE, null)
                    view?.evaluateJavascript("window.setCosmosBands&&window.setCosmosBands(${SharedBands.json})", null)
                }
            }
            SharedBands.subscribe(listenerKey) { json -> handler.post { webView?.evaluateJavascript("window.setCosmosBands&&window.setCosmosBands($json)", null) } }
            view.loadUrl(ASSET)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width > 0 && height > 0) webView?.apply {
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                layout(0, 0, width, height)
            }
            scheduleFrame()
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
            handler.postDelayed(drawFrame, 33L)
        }

        override fun onDestroy() {
            destroyed = true
            SharedBands.unsubscribe(listenerKey)
            handler.removeCallbacks(drawFrame)
            framePending = false
            webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }
            webView = null
            super.onDestroy()
        }
    }
}
