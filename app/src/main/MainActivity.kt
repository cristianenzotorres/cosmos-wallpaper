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
  window.setCosmosNativeBands = function(bands) {
    if (!bands) return;
    window.__cosmosNativeBands = bands;
    var vals = [bands.bass||0, bands.low||0, bands.vocal||0, bands.treble||0];
    if (typeof state !== 'undefined') {
      state.rhythmEnabled = true;
      state.rhythmSimulated = false;
      state.bassEnergy = vals[0];
      state.lowMidEnergy = vals[1];
      state.vocalEnergy = vals[2];
      state.trebleEnergy = vals[3];
      for (var i=0; i<72; i++) {
        var group = i<18 ? vals[0] : i<36 ? vals[1] : i<54 ? vals[2] : vals[3];
        state.spectrum[i] = Math.round(Math.max(0, Math.min(1, group))*255);
      }
    }
  };
  function syncNativeState() {
    var bands = window.__cosmosNativeBands;
    if (typeof state !== 'undefined') {
      state.rhythmEnabled = true;
      state.rhythmSimulated = false;
      state.bassEnergy = bands.bass||0;
      state.lowMidEnergy = bands.low||0;
      state.vocalEnergy = bands.vocal||0;
      state.trebleEnergy = bands.treble||0;
      var vals = [state.bassEnergy,state.lowMidEnergy,state.vocalEnergy,state.trebleEnergy];
      for (var i=0; i<72; i++) {
        var group = i<18 ? vals[0] : i<36 ? vals[1] : i<54 ? vals[2] : vals[3];
        state.spectrum[i] = Math.round(Math.max(0,Math.min(1,group))*255);
      }
    }
    var meter = document.getElementById('rhythmMeterBox');
    if (meter) meter.classList.remove('hidden');
    requestAnimationFrame(syncNativeState);
  }
  requestAnimationFrame(syncNativeState);
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
  window.addEventListener('resize',resize);resize();
  function render() {
    var b=window.__cosmosNativeBands;
    var bands=[b.bass||0,b.low||0,b.vocal||0,b.treble||0];
    var ids=[['bassPct','bassBar'],['lowMidPct','lowMidBar'],['vocalPct','vocalBar'],['treblePct','trebleBar']];
    ids.forEach(function(pair,index){var amount=Math.round(Math.max(0,Math.min(1,bands[index]))*100);var label=document.getElementById(pair[0]),bar=document.getElementById(pair[1]);if(label)label.textContent=amount+'%';if(bar)bar.style.width=amount+'%'});
    var status=document.getElementById('beatStatus');
    if(status)status.textContent=(b.kick||0)>.3?'Bombo detectado':bands[2]>.2?'Voz / melodía':bands[1]>.2?'Bajo / acordes':bands[3]>.18?'Agudos':(b.energy||0)>.025?'Audio en vivo':'Esperando sonido';
    var tempo=document.getElementById('tempoBadge');
    if(tempo)tempo.textContent=(b.energy||0)>.025?'MIC • '+Math.round(b.energy*100)+'%':'-- BPM';
    var width=window.innerWidth,height=window.innerHeight,cx=width/2,cy=height/2;
    ctx.clearRect(0,0,width,height);
    var radius=Math.min(width,height)*(.12+bands[0]*.08),colors=['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'];
    ctx.save();ctx.lineCap='round';
    for(var i=0;i<72;i++){var group=Math.min(3,Math.floor(i/18)),energy=bands[group],angle=i*Math.PI*2/72-Math.PI/2,length=8+energy*Math.min(width,height)*.24;ctx.globalAlpha=.18+energy*.82;ctx.strokeStyle=colors[i%colors.length];ctx.lineWidth=2+energy*3;ctx.beginPath();ctx.moveTo(cx+Math.cos(angle)*radius,cy+Math.sin(angle)*radius);ctx.lineTo(cx+Math.cos(angle)*(radius+length),cy+Math.sin(angle)*(radius+length));ctx.stroke()}
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
    private var pendingMicAction: (() -> Unit)? = null
    private val micAnalyzer = LiveMicAnalyzer()

    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action=pendingMicAction;pendingMicAction=null
        if(granted)action?.invoke()else Toast.makeText(this,"Permite el micrófono para activar el modo audiorítmico.",Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view=WebView(this);webView=view
        view.setBackgroundColor(Color.rgb(9,9,11));configureWebView(view)
        view.addJavascriptInterface(AppBridge(),"CosmosApp")
        view.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,url:String?){super.onPageFinished(view,url);view?.evaluateJavascript(RHYTHM_NATIVE_BRIDGE,null)}}
        setContentView(view);view.loadUrl(STUDIO_ASSET)
    }

    private fun requireMicrophone(action:()->Unit){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)action()
        else{pendingMicAction=action;requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)}
    }

    private inner class AppBridge{
        @android.webkit.JavascriptInterface
        fun enableAudioReaction(){runOnUiThread{requireMicrophone{if(micAnalyzer.isRunning()){micAnalyzer.stop();webView?.evaluateJavascript("window.setCosmosNativeBands&&window.setCosmosNativeBands({bass:0,low:0,vocal:0,treble:0,energy:0,kick:0})",null)}else{micAnalyzer.start{json->webView?.post{webView?.evaluateJavascript("window.setCosmosNativeBands&&window.setCosmosNativeBands($json)",null)}}}}}}
        @android.webkit.JavascriptInterface
        fun setLiveWallpaper(){runOnUiThread{val intent=Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply{putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this@MainActivity,CosmosWallpaperService::class.java))};try{startActivity(intent)}catch(_:Exception){try{startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))}catch(_:Exception){Toast.makeText(this@MainActivity,"No se pudo abrir el selector de fondos.",Toast.LENGTH_LONG).show()}}}}
    }

    override fun onResume(){super.onResume();webView?.onResume()}
    override fun onPause(){webView?.onPause();super.onPause()}
    override fun onDestroy(){micAnalyzer.stop();webView?.apply{stopLoading();removeJavascriptInterface("CosmosApp");removeAllViews();destroy()};webView=null;super.onDestroy()}
}

private class LiveMicAnalyzer{
    @Volatile private var running=false
    @Volatile private var recorder:AudioRecord?=null
    private var worker:Thread?=null
    fun isRunning()=running
    @Synchronized fun stop(){running=false;try{recorder?.stop()}catch(_:Exception){};recorder?.release();recorder=null;worker?.interrupt();worker=null}
    @Synchronized fun start(onBands:(String)->Unit){
        if(running)return
        val sampleRate=16000
        val minBytes=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minBytes<=0)return
        val record=try{AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minBytes,4096))}catch(_:SecurityException){return}
        if(record.state!=AudioRecord.STATE_INITIALIZED){record.release();return}
        recorder=record;running=true
        worker=thread(name="cosmos-mic",start=true){val samples=ShortArray(1024);var bassEnvelope=0f;try{record.startRecording();while(running&&!Thread.currentThread().isInterrupted){val count=record.read(samples,0,samples.size);if(count<=0)continue;var square=0.0;for(i in 0 until count){val value=samples[i].toDouble();square+=value*value};val rms=sqrt(square/count);val bass=bandLevel(samples,count,sampleRate,intArrayOf(45,65,85,110,135));val low=bandLevel(samples,count,sampleRate,intArrayOf(160,210,270,340,420));val vocal=bandLevel(samples,count,sampleRate,intArrayOf(500,700,950,1300,1800,2400));val treble=bandLevel(samples,count,sampleRate,intArrayOf(3000,4000,5200,6500));val energy=(rms/10000.0).toFloat().coerceIn(0f,1f);val kick=(bass-bassEnvelope-.04f).coerceIn(0f,1f);bassEnvelope=bassEnvelope*.82f+bass*.18f;onBands("{\"bass\":$bass,\"low\":$low,\"vocal\":$vocal,\"treble\":$treble,\"energy\":$energy,\"kick\":$kick}");Thread.sleep(24)}}catch(_:Exception){}finally{running=false;try{record.stop()}catch(_:Exception){};record.release();if(recorder===record)recorder=null}}
    }
    private fun bandLevel(samples:ShortArray,count:Int,sampleRate:Int,frequencies:IntArray):Float{if(count<2)return 0f;var total=0.0;for(frequency in frequencies){val bin=(.5+count*frequency.toDouble()/sampleRate).toInt();val omega=2.0*PI*bin/count;val coefficient=2.0*cos(omega);var previous=0.0;var previous2=0.0;for(i in 0 until count){val sample=samples[i]/32768.0;val current=sample+coefficient*previous-previous2;previous2=previous;previous=current};val power=(previous*previous+previous2*previous2-coefficient*previous*previous2).coerceAtLeast(0.0);total+=sqrt(power)/count};return((total/frequencies.size)*18.0).toFloat().coerceIn(0f,1f)}
}

class CosmosWallpaperService:WallpaperService(){override fun onCreateEngine():Engine=CosmosEngine();inner class CosmosEngine:Engine(){private val handler=Handler(Looper.getMainLooper());private var webView:WebView?=null;private var framePending=false;private var destroyed=false;private val drawFrame=object:Runnable{override fun run(){framePending=false;val view=webView?:return;if(destroyed||!isVisible)return;view.invalidate();var canvas:Canvas?=null;try{canvas=surfaceHolder.lockCanvas();if(canvas!=null)view.draw(canvas)}catch(_:Exception){}finally{if(canvas!=null)try{surfaceHolder.unlockCanvasAndPost(canvas)}catch(_:Exception){}};scheduleFrame()}};@SuppressLint("SetJavaScriptEnabled")override fun onCreate(surfaceHolder:SurfaceHolder?){super.onCreate(surfaceHolder);setTouchEventsEnabled(true);val view=WebView(this@CosmosWallpaperService);webView=view;view.setBackgroundColor(Color.BLACK);view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);configureWebView(view);view.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,url:String?){super.onPageFinished(view,url);view?.evaluateJavascript(RHYTHM_NATIVE_BRIDGE,null)}};view.loadUrl(STUDIO_ASSET)};override fun onSurfaceChanged(holder:SurfaceHolder?,format:Int,width:Int,height:Int){super.onSurfaceChanged(holder,format,width,height);if(width>0&&height>0){webView?.apply{measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));layout(0,0,width,height);evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas()",null)}}scheduleFrame()};override fun onVisibilityChanged(visible:Boolean){super.onVisibilityChanged(visible);if(visible){webView?.onResume();scheduleFrame()}else{handler.removeCallbacks(drawFrame);framePending=false;webView?.onPause()}};override fun onTouchEvent(event:MotionEvent){webView?.dispatchTouchEvent(event);super.onTouchEvent(event)};private fun scheduleFrame(){if(destroyed||!isVisible||framePending||webView==null)return;framePending=true;handler.postDelayed(drawFrame,33L)};override fun onDestroy(){destroyed=true;handler.removeCallbacks(drawFrame);framePending=false;webView?.apply{stopLoading();onPause();removeAllViews();destroy()};webView=null;super.onDestroy()}}}
