package com.cosmos.audiorhythmwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
(function(){
  if(window.__cosmosNativeBridgeInstalled)return;
  window.__cosmosNativeBridgeInstalled=true;
  window.__cosmosNativeBands={bass:0,low:0,vocal:0,treble:0,energy:0,kick:0};
  window.setCosmosNativeBands=function(v){window.__cosmosNativeBands=v||window.__cosmosNativeBands};
  var meter=document.getElementById('rhythmMeterBox');if(meter)meter.classList.remove('hidden');
  var ov=document.getElementById('cosmosNativeRhythmCanvas');
  if(!ov){ov=document.createElement('canvas');ov.id='cosmosNativeRhythmCanvas';ov.style.cssText='position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:5';document.body.appendChild(ov)}
  var ox=ov.getContext('2d');
  function rez(){var d=Math.min(devicePixelRatio||1,2);ov.width=Math.round(innerWidth*d);ov.height=Math.round(innerHeight*d);ox.setTransform(d,0,0,d,0,0)}
  addEventListener('resize',rez);rez();
  function draw(){
    var v=window.__cosmosNativeBands,b=v.bass||0,l=v.low||0,m=v.vocal||0,t=v.treble||0,e=v.energy||0,k=v.kick||0;
    var ids=[['bassPct','bassBar',b],['lowMidPct','lowMidBar',l],['vocalPct','vocalBar',m],['treblePct','trebleBar',t]];
    ids.forEach(function(x){var p=document.getElementById(x[0]),g=document.getElementById(x[1]);if(p)p.textContent=Math.round(x[2]*100)+'%';if(g)g.style.width=Math.round(x[2]*100)+'%'});
    var status=document.getElementById('beatStatus');if(status)status.textContent=k>.35?'Bombo detectado':m>.22?'Voz / melodía':l>.22?'Bajo / acordes':t>.18?'Agudos':e>.03?'Audio en vivo':'Esperando sonido';
    var tempo=document.getElementById('tempoBadge');if(tempo)tempo.textContent=e>.03?'MIC • '+Math.round(e*100)+'%':'-- BPM';
    ox.clearRect(0,0,innerWidth,innerHeight);var cx=innerWidth/2,cy=innerHeight/2,base=Math.min(innerWidth,innerHeight)*(.12+b*.08),colors=['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'];
    ox.save();ox.lineCap='round';for(var i=0;i<72;i++){var p=i/72,vv=(i<18?b:i<36?l:i<54?m:t)*(0.7+0.3*Math.sin(i*1.7+performance.now*.006));var a=i*Math.PI*2/72-Math.PI/2,len=8+vv*Math.min(innerWidth,innerHeight)*.22;ox.globalAlpha=.18+vv*.82;ox.strokeStyle=colors[i%colors.length];ox.lineWidth=2+vv*3;ox.beginPath();ox.moveTo(cx+Math.cos(a)*base,cy+Math.sin(a)*base);ox.lineTo(cx+Math.cos(a)*(base+len),cy+Math.sin(a)*(base+len));ox.stroke()}
    ox.globalAlpha=.35+k*.65;ox.strokeStyle='#ec4899';ox.lineWidth=2+k*7;ox.beginPath();ox.arc(cx,cy,base+k*70,0,Math.PI*2);ox.stroke();ox.restore();requestAnimationFrame(draw)
  }
  requestAnimationFrame(draw);
})();
"""

private const class? = null

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingMicAction: (() -> Unit)? = null
    private val recording = LiveMicAnalyzer()

    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "Permite el micrófono para activar el modo audiorítmico.", Toast.LENGTH_LONG).show()
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
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val trusted = request.origin?.scheme == "https" && request.origin?.host == "appassets.androidplatform.net"
                    val audioOnly = request.resources.isNotEmpty() && request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    val mic = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (trusted && audioOnly && mic) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                }
            }
        }
        setContentView(view)
        view.loadUrl(STUDIO_ASSET)
    }

    private fun requireMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingMicAction = action; requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun enableAudioReaction() {
            runOnUiThread {
                requireMic {
                    if (recording.isRunning()) recording.stop()
                    else recording.start { json -> webView?.post { webView?.evaluateJavascript("window.setCosmosNativeBands && window.setCosmosNativeBands($json)",null) } }
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
                    catch (_: Exception) { Toast.makeText(this@MainActivity,"No se pudo abrir el selector de fondos",Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onDestroy(){ recording.stop(); webView?.apply{stopLoading();removeJavascriptInterface("CosmosApp");removeAllViews();destroy()}; webView=null; super.onDestroy() }
    override fun onResume(){super.onResume();webView?.onResume()}
    override fun onPause(){webView?.onPause();super.onPause()}
}

private class LiveMicAnalyzer {
    @Volatile private var running=false
    private var threadRef: Thread?=null
    fun isRunning()=running
    fun stop(){ running=false; threadRef?.interrupt(); threadRef=null }

    fun start(onBands:(String)->Unit){
        if(running)return
        val sampleRate=16000
        val bufferSize=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val recorder=try{AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize*2)}catch(e:Exception){null}
        if(recorder==null||recorder.state!=AudioRecord.STATE_INITIALIZED)return
        running=true
        threadRef=thread(name="cosmos-mic-analyzer",start=true){
            val data=ShortArray(1024); var previousBass=0f
            recorder.startRecording()
            while(running&&!Thread.currentThread().isInterrupted){
                val read=recorder.read(data,0,data.size)
                if(read<=0)continue
                val rms=sqrt(data.take(read).fold(0.0){sum,v->sum+v.toDouble()*v.toDouble()}/read)
                val bass=goertzel(data,read,sampleRate,75f)
                val low=goertzel(data,read,sampleRate,220f)
                val vocal=goertzel(data,read,sampleRate,900f)
                val treble=goertzel(data,read,sampleRate,5500f)
                val energy=(rms/12000.0).toFloat().coerceIn(0f,1f)
                val kick=(bass.coerceIn(0f,1f)-previousBass).coerceIn(0f,1f)
                previousBass=previousBass*.82f+bass*.18f
                val json="{\"bass\":${bass.format()},\"low\":${low.format()},\"vocal\":${vocal.format()},\"treble\":${treble.format()},\"energy\":${energy.format()},\"kick\":${kick.format()}}"
                onBands(json)
                Thread.sleep(25)
            }
            recorder.stop();recorder.release()
        }
    }

    private fun goertzel(samples:ShortArray,count:Int,sampleRate:Int,targetHz:Float):Float{
        val k=(0.5+count*targetHz/sampleRate).toInt();val omega=2*PI*k/count;val coeff=2*cos(omega);var s0=0.0;var s1=0.0;var s2=0.0
        for(i in 0 until count){val x=samples[i]/32768.0;s0=x+coeff*s1-s2;s2=s1;s1=s0}
        val power=s1*s1+s2*s2-coeff*s1*s2
        val normalized=Math.sqrt(power)/count
        return (normalized*5.0).toFloat().coerceIn(0f,1f)
    }
    private fun Float.format():String=this.toString()
}

class CosmosWallpaperService:WallpaperService(){
    override fun onCreateEngine():Engine=CosmosEngine()
    inner class CosmosEngine:Engine(){
        private val handler=Handler(Looper.getMainLooper());private var view:WebView?=null;private var pending=false;private var destroyed=false
        private val frame=object:Runnable{override fun run(){pending=false;if(destroyed||!isVisible)return;view?.invalidate();var c:Canvas?=null;try{c=surfaceHolder.lockCanvas();if(c!=null)view?.draw(c)}catch(_:Exception){}finally{if(c!=null)try{surfaceHolder.unlockCanvasAndPost(c)}catch(_:Exception){}}schedule()} }
        @SuppressLint("SetJavaScriptEnabled") override fun onCreate(holder:SurfaceHolder?){super.onCreate(holder);setTouchEventsEnabled(true);val v=WebView(this@CosmosWallpaperService);view=v;v.setBackgroundColor(Color.BLACK);v.setLayerType(View.LAYER_TYPE_SOFTWARE,null);configureWebView(v);v.webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView?,url:String?){super.onPageFinished(v,url);v?.evaluateJavascript(RHYTHM_NATIVE_BRIDGE,null)}};v.loadUrl(STUDIO_ASSET)}
        override fun onSurfaceChanged(holder:SurfaceHolder?,format:Int,width:Int,height:Int){super.onSurfaceChanged(holder,format,width,height);val v=view?:return;v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,width,height);v.evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas()",null);schedule()}
        override fun onVisibilityChanged(visible:Boolean){super.onVisibilityChanged(visible);if(visible)schedule()else{handler.removeCallbacks(frame);pending=false}}
        override fun onTouchEvent(event:MotionEvent){view?.dispatchTouchEvent(event);super.onTouchEvent(event)}
        private fun schedule(){if(destroyed||!isVisible||pending||view==null)return;pending=true;handler.postDelayed(frame,16)}
        override fun onDestroy(){destroyed=true;handler.removeCallbacks(frame);pending=false;view?.apply{stopLoading();removeAllViews();destroy()};view=null;super.onDestroy()}
    }
}
