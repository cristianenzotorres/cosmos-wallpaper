package com.cosmos.audiorhythmwallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

private const val STUDIO_ASSET = "file:///android_asset/Cosmos%20Generativo.html"
private const val CHANNEL_ID = "cosmos_audio_capture"

private object CaptureState { @Volatile var onBands: ((String) -> Unit)? = null }

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = true
        allowContentAccess = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
    }
}

private const val RHYTHM_BRIDGE = """
(function(){
  if(window.__cosmosRhythmBridge)return;
  window.__cosmosRhythmBridge=true;
  window.__cosmosBands={bass:0,low:0,vocal:0,treble:0,energy:0,kick:0};
  window.setCosmosBands=function(b){if(!b)return;if(b.error){var s=document.getElementById('beatStatus');if(s)s.textContent=b.error;return}window.__cosmosBands=b;if(window.state){window.state.rhythmEnabled=true;window.state.bassEnergy=b.bass||0;window.state.lowMidEnergy=b.low||0;window.state.vocalEnergy=b.vocal||0;window.state.trebleEnergy=b.treble||0;for(var i=0;i<72;i++){var q=i<18?b.bass:i<36?b.low:i<54?b.vocal:b.treble;window.state.spectrum[i]=Math.round(Math.max(0,Math.min(1,q||0))*255)}}};
  var meter=document.getElementById('rhythmMeterBox');if(meter)meter.classList.remove('hidden');
  var panel=document.getElementById('controlPanel');var canvas=document.createElement('canvas');canvas.id='cosmosRhythmCanvas';canvas.style.cssText='position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:5';document.body.appendChild(canvas);var ctx=canvas.getContext('2d');var mode='radar';
  function resize(){var d=Math.min(devicePixelRatio||1,2);canvas.width=Math.round(innerWidth*d);canvas.height=Math.round(innerHeight*d);ctx.setTransform(d,0,0,d,0,0)}addEventListener('resize',resize);resize();
  if(panel){var title=document.createElement('div');title.textContent='Visualización audiorítmica';title.style.cssText='font-size:11px;color:#d4d4d8;margin-top:8px';var box=document.createElement('div');box.style.cssText='display:grid;grid-template-columns:repeat(3,1fr);gap:5px;margin:8px 0';var names=['Radar','Barras','Olas','Flor','Túnel','ADN'],modes=['radar','bars','waves','bloom','tunnel','dna'];names.forEach(function(n,i){var b=document.createElement('button');b.textContent=n;b.dataset.mode=modes[i];b.style.cssText='padding:7px 3px;border:1px solid #52525b;border-radius:9px;background:#27272a;color:#e4e4e7;font-size:11px';b.onclick=function(){mode=modes[i];box.querySelectorAll('button').forEach(function(x){x.style.borderColor=x===b?'#d946ef':'#52525b'})};box.appendChild(b)});panel.appendChild(title);panel.appendChild(box)}
  function draw(){var b=window.__cosmosBands,v=[b.bass||0,b.low||0,b.vocal||0,b.treble||0];var ids=[['bassPct','bassBar'],['lowMidPct','lowMidBar'],['vocalPct','vocalBar'],['treblePct','trebleBar']];ids.forEach(function(p,i){var n=Math.round(v[i]*100),l=document.getElementById(p[0]),bar=document.getElementById(p[1]);if(l)l.textContent=n+'%';if(bar)bar.style.width=n+'%'});var st=document.getElementById('beatStatus');if(st)st.textContent=(b.kick||0)>.3?'Bombo detectado':v[2]>.2?'Voz / melodía':v[1]>.2?'Bajo / acordes':v[3]>.18?'Agudos':(b.energy||0)>.025?'Audio interno en vivo':'Esperando reproducción';
  var w=innerWidth,h=innerHeight,cx=w/2,cy=h/2,N=72,c=['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'],s=[];for(var i=0;i<N;i++)s.push(v[Math.min(3,Math.floor(i/18))]);ctx.clearRect(0,0,w,h);ctx.save();ctx.lineCap='round';
  if(mode==='radar'){var base=Math.min(w,h)*(.12+v[0]*.08);for(var r=0;r<N;r++){var a=r*Math.PI*2/N-Math.PI/2,x=s[r];ctx.globalAlpha=.25+x*.75;ctx.strokeStyle=c[r%5];ctx.lineWidth=2+x*3;ctx.beginPath();ctx.moveTo(cx+Math.cos(a)*base,cy+Math.sin(a)*base);ctx.lineTo(cx+Math.cos(a)*(base+8+x*Math.min(w,h)*.22),cy+Math.sin(a)*(base+8+x*Math.min(w,h)*.22));ctx.stroke()}}
  else if(mode==='bars'){var bw=w/N;for(var j=0;j<N;j++){var bh=s[j]*h*.36;ctx.globalAlpha=.35+s[j]*.65;ctx.fillStyle=c[j%5];ctx.fillRect(j*bw,cy-bh/2,Math.max(1,bw-2),bh)}}
  else if(mode==='waves'){for(var row=0;row<4;row++){ctx.globalAlpha=.55;ctx.strokeStyle=c[row];ctx.lineWidth=2;ctx.beginPath();for(var x=0;x<=w;x+=6){var e=s[Math.floor(x/Math.max(1,w)*(N-1))],y=cy+(row-1.5)*35+Math.sin(x*.025+performance.now()*.003+row)*e*h*.12;if(x===0)ctx.moveTo(x,y);else ctx.lineTo(x,y)}ctx.stroke()}}
  else if(mode==='bloom'){for(var p=0;p<36;p++){var q=s[p*2%N],a=p*Math.PI*2/36,rr=20+q*Math.min(w,h)*.28;ctx.globalAlpha=.3+q*.7;ctx.strokeStyle=c[p%5];ctx.lineWidth=2+q*3;ctx.beginPath();ctx.moveTo(cx+Math.cos(a)*12,cy+Math.sin(a)*12);ctx.quadraticCurveTo(cx+Math.cos(a+.55)*rr*.8,cy+Math.sin(a+.55)*rr*.8,cx+Math.cos(a)*rr,cy+Math.sin(a)*rr);ctx.stroke()}}
  else if(mode==='tunnel'){var tm=performance.now()/1000;for(var z=0;z<10;z++){var ph=(z/10+tm*.25)%1,rr=18+ph*Math.min(w,h)*.48;ctx.globalAlpha=.15+ph*.55;ctx.strokeStyle=c[z%5];ctx.lineWidth=2+v[z%4]*3;ctx.beginPath();for(var side=0;side<N;side++){var aa=side*Math.PI*2/N,deform=1+s[side]*.12,px=cx+Math.cos(aa)*rr*deform,py=cy+Math.sin(aa)*rr*deform;if(side===0)ctx.moveTo(px,py);else ctx.lineTo(px,py)}ctx.closePath();ctx.stroke()}}
  else{for(var d=0;d<38;d++){var rt=d/37,xx=w*.08+rt*w*.84,amp=20+s[d*2%N]*Math.min(w,h)*.16,aa=rt*Math.PI*4+performance.now()/500,y1=cy+Math.sin(aa)*amp,y2=cy-Math.sin(aa)*amp;ctx.globalAlpha=.3+s[d*2%N]*.7;ctx.fillStyle=c[d%5];ctx.beginPath();ctx.arc(xx,y1,2+s[d*2%N]*5,0,Math.PI*2);ctx.fill();ctx.beginPath();ctx.arc(xx,y2,2+s[d*2%N]*5,0,Math.PI*2);ctx.fill()}ctx.globalAlpha=.7;ctx.strokeStyle='#d946ef';ctx.beginPath();for(var dl=0;dl<=38;dl++){var rr=dl/38,xx=w*.08+rr*w*.84,dy=cy+Math.sin(rr*Math.PI*4+performance.now()/500)*(20+v[2]*Math.min(w,h)*.16);if(dl===0)ctx.moveTo(xx,dy);else ctx.lineTo(xx,dy)}ctx.stroke()}
  ctx.restore();requestAnimationFrame(draw)}requestAnimationFrame(draw);
  var rhythm=document.getElementById('rhythmToggleBtn');if(rhythm&&!rhythm.dataset.cosmosNative){rhythm.dataset.cosmosNative='1';rhythm.addEventListener('click',function(e){e.preventEvent?.();e.preventDefault();e.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.enableAudioReaction()},true)}
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
        CaptureState.onBands = { json -> runOnUiThread { webView?.evaluateJavascript("window.setCosmosBands && window.setCosmosBands($json)", null) } }
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.rgb(9,9,11))
        configureWebView(view)
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(RHYTHM_BRIDGE, null)
            }
        }
        setContentView(view)
        view.loadUrl(STUDIO_ASSET)
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
                    try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "No se pudo abrir el selector.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        CaptureState.onBands = null
        stopService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
        webView?.apply { stopLoading(); removeJavascriptInterface("CosmosApp"); removeAllViews(); destroy() }
        webView = null
        super.onDestroy()
    }
}

class PlaybackCaptureService : android.app.Service() {
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
        createChannel(); startForegroundInternal()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        if (code != Activity.RESULT_OK || data == null) { reportError("No se recibió la autorización"); stopCapture(); stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            analyzer.start(projection!!) { json -> CaptureState.onBands?.invoke(json) }
            if (!analyzer.isRunning()) { reportError("No se pudo iniciar la captura interna"); stopCapture(); stopSelf() }
        } catch (e: Exception) { reportError("Captura no disponible: " + (e.localizedMessage ?: "error")); stopCapture(); stopSelf() }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Captura de audio", NotificationManager.IMPORTANCE_LOW))
    }

    private fun startForegroundInternal() {
        val stopIntent = Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Cosmos audio interno")
            .setContentText("Analizando reproducción de RiPlay")
            .setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(null, "Detener", pending).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7102, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7102, notification)
    }

    private fun reportError(message: String) {
        val escaped = org.json.JSONObject.quote(message)
        CaptureState.onBands?.invoke("{\"error\":" + escaped + "}")
    }

    private fun stopCapture() { analyzer.stop(); projection?.stop(); projection = null; stopForeground(STOP_FOREGROUND_REMOVE) }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
}

private class PlaybackAnalyzer {
    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    fun isRunning() = running

    @Synchronized fun stop() {
        running = false
        recorder?.let { record -> try { record.stop() } catch (_: Exception) {}; try { record.release() } catch (_: Exception) {} }
        recorder = null
        worker?.interrupt()
        worker = null
    }

    @Synchronized fun start(projection: MediaProjection, onBands: (String) -> Unit) {
        if (running || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return
        val record = try { AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum, 4096)).setAudioPlaybackCaptureConfig(captureConfig).build() } catch (_: Exception) { return }
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
                try { canvas = surfaceHolder.lockCanvas(); if (canvas != null) view.draw(canvas) } catch (_: Exception) {}
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
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(RHYTHM_BRIDGE, null)
                }
            }
            view.loadUrl(STUDIO_ASSET)
        }
        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width > 0 && height > 0) webView?.apply { measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); layout(0, 0, width, height); evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas()", null) }
            scheduleFrame()
        }
        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) { webView?.onResume(); scheduleFrame() } else { handler.removeCallbacks(drawFrame); framePending = false; webView?.onPause() }
        }
        override fun onTouchEvent(event: MotionEvent) { webView?.dispatchTouchEvent(event); super.onTouchEvent(event) }
        private fun scheduleFrame() { if (destroyed || !isVisible || framePending || webView == null) return; framePending = true; handler.postDelayed(drawFrame, 16L) }
        override fun onDestroy() { destroyed = true; handler.removeCallbacks(drawFrame); framePending = false; webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }; webView = null; super.onDestroy() }
    }
}
