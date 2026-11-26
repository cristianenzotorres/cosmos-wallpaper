package com.cosmos.audiorhythmwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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

private const val RHYTHM_NATIVE_BRIDGE = """
(function() {
  if (window.__cosmosNativeBridgeInstalled) return;
  window.__cosmosNativeBridgeInstalled = true;
  window.__cosmosNativeBands = {bass:0,low:0,vocal:0,treble:0,energy:0,kick:0};
  window.setCosmosNativeBands = function(bands) { window.__cosmosNativeBands = bands || window.__cosmosNativeBands; };
  var meter = document.getElementById('rhythmMeterBox');
  if (meter) meter.classList.remove('hidden');
  var canvas = document.getElementById('cosmosNativeRhythmCanvas');
  if (!canvas) {
    canvas = document.createElement('canvas');
    canvas.id = 'cosmosNativeRhythmCanvas';
    canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:5';
    document.body.appendChild(canvas);
  }
  var ctx = canvas.getContext('2d');
  function resize() {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.round(window.innerWidth*dpr));
    canvas.height = Math.max(1, Math.round(window.innerHeight*dpr));
    ctx.setTransform(dpr,0,0,dpr,0,0);
  }
  window.addEventListener('resize',resize); resize();
  function render() {
    var b=window.__cosmosNativeBands;
    var bands=[b.bass||0,b.low||0,b.vocal||0,b.treble||0];
    var ids=[['bassPct','bassBar'],['lowMidPct','lowMidBar'],['vocalPct','vocalBar'],['treblePct','trebleBar']];
    ids.forEach(function(pair,index){var amount=Math.round(Math.max(0,Math.min(1,bands[index]))*100);var label=document.getElementById(pair[0]),bar=document.getElementById(pair[1]);if(label)label.textContent=amount+'%';if(bar)bar.style.width=amount+'%'});
    var status=document.getElementById('beatStatus');
    if(status)status.textContent=(b.kick||0)>.3?'Bombo detectado':bands[2]>.2?'Voz / melodía':bands[1]>.2?'Bajo / acordes':bands[3]>.18?'Agudos':(b.energy||0)>.025?'Reproducción interna en vivo':'Esperando reproducción';
    var tempo=document.getElementById('tempoBadge');
    if(tempo)tempo.textContent=(b.energy||0)>.025?'AUDIO • '+Math.round(b.energy*100)+'%':'-- BPM';
    var width=innerWidth,height=innerHeight,cx=width/2,cy=height/2;
    ctx.clearRect(0,0,width,height);
    var radius=Math.min(width,height)*(0.12+bands[0]*0.08),colors=['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'];
    ctx.save();ctx.lineCap='round';
    for(var i=0;i<72;i++){var group=Math.min(3,Math.floor(i/18)),energy=bands[group],angle=i*Math.PI*2/72-Math.PI/2,length=8+energy*Math.min(width,height)*0.24;ctx.globalAlpha=.18+energy*.82;ctx.strokeStyle=colors[i%colors.length];ctx.lineWidth=2+energy*3;ctx.beginPath();ctx.moveTo(cx+Math.cos(angle)*radius,cy+Math.sin(angle)*radius);ctx.lineTo(cx+Math.cos(angle)*(radius+length),cy+Math.sin(angle)*(radius+length));ctx.stroke()}
    var kick=b.kick||0;ctx.globalAlpha=.25+kick*.7;ctx.strokeStyle='#ec4899';ctx.lineWidth=2+kick*7;ctx.beginPath();ctx.arc(cx,cy,radius+kick*70,0,Math.PI*2);ctx.stroke();ctx.restore();requestAnimationFrame(render);
  }
  requestAnimationFrame(render);
  var rhythmButton=document.getElementById('rhythmToggleBtn');
  if(rhythmButton&&!rhythmButton.dataset.cosmosNativeHooked){rhythmButton.dataset.cosmosNativeHooked='1';rhythmButton.addEventListener('click',function(event){event.preventDefault();event.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.enableAudioReaction()},true)}
  var installButton=document.getElementById('installWallpaperBtn');
  if(installButton&&!installButton.dataset.cosmosWallpaperHooked){installButton.dataset.cosmosWallpaperHooked='1';installButton.addEventListener('click',function(event){event.preventDefault();event.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.setLiveWallpaper()},true)}
})();
"""

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var mediaProjection: MediaProjection? = null
    private val playbackAnalyzer = PlaybackCaptureAnalyzer()

    private val requestProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            mediaProjection = projection
            playbackAnalyzer.start(projection) { json ->
                webView?.post {
                    webView?.evaluateJavascript("window.setCosmosNativeBands && window.setCosmosNativeBands($json)", null)
                }
            }
        } else {
            Toast.makeText(this, "La captura interna fue cancelada.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.rgb(9,9,11))
        configureWebView(view)
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view,url)
                view?.evaluateJavascript(RHYTHM_NATIVE_BRIDGE,null)
            }
        }
        setContentView(view)
        view.loadUrl(STUDIO_ASSET)
    }

    private fun requestPlaybackCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "La captura interna requiere Android 10 o superior.", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestProjection.launch(manager.createScreenCaptureIntent())
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun enableAudioReaction() {
            runOnUiThread {
                if (playbackAnalyzer.isRunning()) {
                    playbackAnalyzer.stop()
                    mediaProjection?.stop()
                    mediaProjection = null
                    webView?.evaluateJavascript("window.setCosmosNativeBands && window.setCosmosNativeBands({bass:0,low:0,vocal:0,treble:0,energy:0,kick:0})", null)
                } else {
                    requestPlaybackCapture()
                }
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
                    catch (_: Exception) { Toast.makeText(this@MainActivity, "No se pudo abrir el selector de fondos.", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onResume(){super.onResume();webView?.onResume()}
    override fun onPause(){webView?.onPause();super.onPause()}
    override fun onDestroy(){playbackAnalyzer.stop();mediaProjection?.stop();webView?.apply{stopLoading();removeJavascriptInterface("CosmosApp");removeAllViews();destroy()};webView=null;super.onDestroy()}
}

private class PlaybackCaptureAnalyzer {
    @Volatile private var running=false
    @Volatile private var recorder:AudioRecord?=null
    private var worker:Thread?=null
    fun isRunning()=running

    @Synchronized fun stop(){running=false;try{recorder?.stop()}catch(_:IllegalStateException){};recorder?.release();recorder=null;worker?.interrupt();worker=null}

    @Synchronized fun start(projection:MediaProjection,onBands:(String)->Unit){
        if(running||Build.VERSION.SDK_INT<29)return
        val playbackConfig=AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minBuffer=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minBuffer<=0)return
        val audioRecord=try{AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuffer,4096)).setAudioPlaybackCaptureConfig(playbackConfig).build()}catch(_:SecurityException){return}catch(_:UnsupportedOperationException){return}
        if(audioRecord.state!=AudioRecord.STATE_INITIALIZED){audioRecord.release();return}
        recorder=audioRecord;running=true
        worker=thread(name="cosmos-playback-analyzer",start=true){
            val samples=ShortArray(1024);var bassEnvelope=0f
            try{audioRecord.startRecording();while(running&&!Thread.currentThread().isInterrupted){val count=audioRecord.read(samples,0,samples.size);if(count<=0)continue
                val rms=sqrt(samples.take(count).fold(0.0){sum,v->sum+v.toDouble()*v.toDouble()}/count)
                val bass=bandLevel(samples,count,16000,intArrayOf(45,65,85,110,135));val low=bandLevel(samples,count,16000,intArrayOf(160,210,270,340,420));val vocal=bandLevel(samples,count,16000,intArrayOf(500,700,950,1300,1800,2400));val treble=bandLevel(samples,count,16000,intArrayOf(3000,4000,5200,6500))
                val energy=(rms/10000.0).toFloat().coerceIn(0f,1f);val kick=(bass-bassEnvelope-0.04f).coerceIn(0f,1f);bassEnvelope=bassEnvelope*.82f+bass*.18f
                onBands("{\"bass\":${bass},\"low\":${low},\"vocal\":${vocal},\"treble\":${treble},\"energy\":${energy},\"kick\":${kick}}")
                Thread.sleep(24)
            }}catch(_:IllegalStateException){}catch(_:InterruptedException){Thread.currentThread().interrupt()}finally{running=false;try{audioRecord.stop()}catch(_:IllegalStateException){};audioRecord.release();if(recorder===audioRecord)recorder=null}
        }
    }

    private fun bandLevel(samples:ShortArray,count:Int,sampleRate:Int,frequencies:IntArray):Float{if(count<2)return 0f;var total=0.0;for(frequency in frequencies){val bin=(0.5+count*frequency.toDouble()/sampleRate).toInt();val omega=2.0*PI*bin/count;val coefficient=2.0*cos(omega);var previous=0.0;var previous2=0.0;for(i in 0 until count){val sample=samples[i]/32768.0;val current=sample+coefficient*previous-previous2;previous2=previous;previous=current};val power=(previous*previous+previous2*previous2-coefficient*previous*previous2).coerceAtLeast(0.0);total+=sqrt(power)/count};return((total/frequencies.size)*18.0).toFloat().coerceIn(0f,1f)}
}

class CosmosWallpaperService:WallpaperService(){override fun onCreateEngine():Engine=CosmosEngine();inner class CosmosEngine:Engine(){private val handler=Handler(Looper.getMainLooper());private var webView:WebView?=null;private var framePending=false;private var destroyed=false;private val drawFrame=object:Runnable{override fun run(){framePending=false;val view=webView?:return;if(destroyed||!isVisible)return;view.invalidate();var canvas:Canvas?=null;try{canvas=surfaceHolder.lockCanvas();if(canvas!=null)view.draw(canvas)}catch(_:Exception){}finally{if(canvas!=null)try{surfaceHolder.unlockCanvasAndPost(canvas)}catch(_:Exception){}}scheduleFrame()}};@SuppressLint("SetJavaScriptEnabled")override fun onCreate(surfaceHolder:SurfaceHolder?){super.onCreate(surfaceHolder);setTouchEventsEnabled(true);val view=WebView(this@CosmosWallpaperService);webView=view;view.setBackgroundColor(Color.BLACK);view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);configureWebView(view);view.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,url:String?){super.onPageFinished(view,url);view?.evaluateJavascript(RHYTHM_NATIVE_BRIDGE,null)}};view.loadUrl(STUDIO_ASSET)};override fun onSurfaceChanged(holder:SurfaceHolder?,format:Int,width:Int,height:Int){super.onSurfaceChanged(holder,format,width,height);if(width>0&&height>0){webView?.apply{measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));layout(0,0,width,height);evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas()",null)}}scheduleFrame()};override fun onVisibilityChanged(visible:Boolean){super.onVisibilityChanged(visible);if(visible){webView?.onResume();scheduleFrame()}else{handler.removeCallbacks(drawFrame);framePending=false;webView?.onPause()}};override fun onTouchEvent(event:MotionEvent){webView?.dispatchTouchEvent(event);super.onTouchEvent(event)};private fun scheduleFrame(){if(destroyed||!isVisible||framePending||webView==null)return;framePending=true;handler.postDelayed(drawFrame,16L)};override fun onDestroy(){destroyed=true;handler.removeCallbacks(drawFrame);framePending=false;webView?.apply{stopLoading();onPause();removeAllViews();destroy()};webView=null;super.onDestroy()}}}
