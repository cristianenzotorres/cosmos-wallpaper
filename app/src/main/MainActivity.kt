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

private const val APP_ASSET_ORIGIN = "https://appassets.androidplatform.net"
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

private const val AUDIO_REACTIVE_SCRIPT = """
(function() {
  if (window.__cosmosAudioFixInstalled) return;
  window.__cosmosAudioFixInstalled = true;
  var stream = null, audioContext = null, analyser = null, bins = null;
  var active = false, raf = 0, smooth = new Float32Array(4), lastKick = 0;
  var canvas = document.getElementById('cosmosReactiveCanvas');
  if (!canvas) {
    canvas = document.createElement('canvas'); canvas.id = 'cosmosReactiveCanvas';
    canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:4';
    document.body.appendChild(canvas);
  }
  var ctx = canvas.getContext('2d');
  function resize() {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.round(innerWidth * dpr));
    canvas.height = Math.max(1, Math.round(innerHeight * dpr));
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener('resize', resize); resize();
  function showStatus(text) {
    var status = document.getElementById('beatStatus');
    if (status) status.textContent = text;
    var box = document.getElementById('rhythmMeterBox');
    if (box) box.classList.remove('hidden');
  }
  function stop() {
    active = false; cancelAnimationFrame(raf); ctx.clearRect(0,0,innerWidth,innerHeight);
    if (stream) { stream.getTracks().forEach(function(t){t.stop()}); stream = null; }
    if (audioContext) { audioContext.close().catch(function(){}); audioContext = null; }
    analyser = null; bins = null; smooth.fill(0); showStatus('Modo audiorítmico desactivado');
  }
  function updateMeter(id, pct, bar) {
    var value = Math.max(0, Math.min(100, Math.round(pct * 100)));
    var label = document.getElementById(id), meter = document.getElementById(bar);
    if (label) label.textContent = value + '%';
    if (meter) meter.style.width = value + '%';
  }
  function tick() {
    if (!active || !analyser || !bins) return;
    analyser.getByteFrequencyData(bins);
    var nyquist = audioContext.sampleRate / 2, binHz = nyquist / bins.length;
    function band(lo, hi) {
      var first = Math.max(1, Math.floor(lo / binHz));
      var last = Math.min(bins.length - 1, Math.ceil(hi / binHz));
      var total = 0, peak = 0, count = 0;
      for (var i = first; i <= last; i++) { var v = bins[i] / 255; total += v; peak = Math.max(peak, v); count++; }
      return count ? Math.min(1, (total / count) * .35 + peak * .65) : 0;
    }
    var raw = [band(35,150), band(150,450), band(450,3000), band(5000,15000)];
    for (var j=0;j<4;j++) smooth[j] = smooth[j] * .62 + raw[j] * .38;
    updateMeter('bassPct',smooth[0],'bassBar'); updateMeter('lowMidPct',smooth[1],'lowMidBar');
    updateMeter('vocalPct',smooth[2],'vocalBar'); updateMeter('treblePct',smooth[3],'trebleBar');

    var now = performance.now(), w = innerWidth, h = innerHeight, cx = w/2, cy = h/2;
    var energy = Math.max.apply(null, smooth), bass = smooth[0];
    var palette = ['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'];
    ctx.clearRect(0,0,w,h);
    // Frequency bars show the live microphone spectrum (not a synthetic demo animation).
    var count = 72, base = Math.min(w,h) * (.11 + bass*.035);
    for (var k=0;k<count;k++) {
      var start = Math.floor(Math.pow(k/count, 1.8) * (bins.length-1));
      var end = Math.max(start+1, Math.floor(Math.pow((k+1)/count,1.8)*(bins.length-1)));
      var sum=0; for(var q=start;q<=end;q++) sum += bins[q];
      var v = sum / Math.max(1,end-start+1) / 255;
      var angle = k * Math.PI*2/count - Math.PI/2;
      var len = 5 + v * Math.min(w,h) * .24;
      ctx.globalAlpha = .18 + v*.82; ctx.strokeStyle = palette[k%palette.length]; ctx.lineWidth = 2 + v*2;
      ctx.beginPath(); ctx.moveTo(cx+Math.cos(angle)*base,cy+Math.sin(angle)*base);
      ctx.lineTo(cx+Math.cos(angle)*(base+len),cy+Math.sin(angle)*(base+len)); ctx.stroke();
    }
    ctx.globalAlpha = .2 + energy*.6; ctx.strokeStyle = palette[0]; ctx.lineWidth=2+energy*4;
    ctx.beginPath(); ctx.arc(cx,cy,base + Math.sin(now*.012)*5 + bass*24,0,Math.PI*2); ctx.stroke();
    // Kick detection: bass transient relative to recent low-frequency level.
    if (bass > .12 && bass - lastKick > .025) {
      var kickRadius = base + 4;
      ctx.globalAlpha = .45; ctx.lineWidth = 2 + bass*5; ctx.strokeStyle = palette[1];
      ctx.beginPath(); ctx.arc(cx,cy,kickRadius,0,Math.PI*2); ctx.stroke();
    }
    lastKick = bass;
    ctx.globalAlpha = 1;
    var tempo = document.getElementById('tempoBadge');
    if (tempo && energy > .025) tempo.textContent = 'MICRO • ' + Math.round(energy*100) + '%';
    var status = document.getElementById('beatStatus');
    if (status) status.textContent = energy < .018 ? 'Micrófono activo • esperando sonido' :
      (bass > .18 ? 'Bombo / graves detectados' : smooth[2] > .16 ? 'Voz / melodía detectada' : smooth[3] > .12 ? 'Agudos detectados' : 'Audio en vivo');
    raf = requestAnimationFrame(tick);
  }
  window.startCosmosAudioAnalysis = async function() {
    if (active) { stop(); return 'stopped'; }
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('WebView no expone el micrófono');
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) throw new Error('Web Audio no está disponible');
      stream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:false,noiseSuppression:false,autoGainControl:false,channelCount:1}});
      audioContext = new AC();
      if (audioContext.state === 'suspended') await audioContext.resume();
      analyser = audioContext.createAnalyser(); analyser.fftSize=4096; analyser.smoothingTimeConstant=.68;
      analyser.minDecibels=-90; analyser.maxDecibels=-18;
      audioContext.createMediaStreamSource(stream).connect(analyser);
      bins = new Uint8Array(analyser.frequencyBinCount); active = true;
      showStatus('Micrófono activo • esperando sonido');
      tick(); return 'started';
    } catch (error) {
      stop(); showStatus('No se pudo acceder al micrófono: ' + (error.message || error));
      return 'error';
    }
  };
  var rhythm = document.getElementById('rhythmToggleBtn');
  if (rhythm && !rhythm.dataset.cosmosAudioHooked) {
    rhythm.dataset.cosmosAudioHooked='1';
    rhythm.addEventListener('click',function(e){e.preventDefault();e.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.enableAudioReaction()},true);
  }
  var install = document.getElementById('installWallpaperBtn');
  if (install && !install.dataset.cosmosWallpaperHooked) {
    install.dataset.cosmosWallpaperHooked='1';
    install.addEventListener('click',function(e){e.preventDefault();e.stopImmediatePropagation();if(window.CosmosApp)window.CosmosApp.setLiveWallpaper()},true);
  }
})();
"""

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var rhythmEnabled = false
    private var pendingMicAction: (() -> Unit)? = null
    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "Permite el micrófono en Ajustes para analizar la música.", Toast.LENGTH_LONG).show()
    }
    private val assetLoader by lazy { createAssetLoader(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.rgb(9,9,11))
        configureWebView(view)
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view,url)
                view?.evaluateJavascript(AUDIO_REACTIVE_SCRIPT,null)
            }
        }
        installPermissionHandler(view,this,Handler(Looper.getMainLooper())) { isFinishing || isDestroyed }
        setContentView(view)
        view.loadUrl(APP_ASSET_ORIGIN + STUDIO_PATH)
    }

    private fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) action()
        else { pendingMicAction=action; requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun enableAudioReaction() {
            runOnUiThread {
                if (rhythmEnabled) {
                    rhythmEnabled = false
                    webView?.evaluateJavascript("window.startCosmosAudioAnalysis && window.startCosmosAudioAnalysis()",null)
                } else {
                    withMicrophonePermission {
                        rhythmEnabled = true
                        webView?.evaluateJavascript("window.startCosmosAudioAnalysis && window.startCosmosAudioAnalysis()",null)
                    }
                }
            }
        }
        @android.webkit.JavascriptInterface
        fun setLiveWallpaper() { runOnUiThread { openWallpaperPicker() } }
    }

    private fun openWallpaperPicker() {
        val intent=Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this@MainActivity,CosmosWallpaperService::class.java))
        }
        try { startActivity(intent) } catch (_:Exception) {
            try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            catch (_:Exception) { Toast.makeText(this,"No se pudo abrir el selector de fondos.",Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume(){super.onResume();webView?.onResume()}
    override fun onPause(){webView?.onPause();super.onPause()}
    override fun onDestroy(){webView?.apply{stopLoading();removeJavascriptInterface("CosmosApp");removeAllViews();destroy()};webView=null;super.onDestroy()}
}

private fun installPermissionHandler(view:WebView,context:android.content.Context,handler:Handler,isDestroyed:()->Boolean){
    view.webChromeClient=object:WebChromeClient(){
        override fun onPermissionRequest(request:PermissionRequest){
            handler.post {
                val origin=request.origin
                val trusted=origin?.scheme=="https"&&origin.host=="appassets.androidplatform.net"
                val audioOnly=request.resources.isNotEmpty()&&request.resources.all{it==PermissionRequest.RESOURCE_AUDIO_CAPTURE}
                val granted=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
                if(trusted&&audioOnly&&granted&&!isDestroyed())request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
            }
        }
    }
}

class CosmosWallpaperService:WallpaperService(){
    override fun onCreateEngine():Engine=CosmosWebViewEngine()
    inner class CosmosWebViewEngine:Engine(){
        private val handler=Handler(Looper.getMainLooper())
        private val assetLoader by lazy{createAssetLoader(this@CosmosWallpaperService)}
        private var webView:WebView?=null
        private var framePending=false
        private var destroyed=false
        private val drawFrame=object:Runnable{
            override fun run(){
                framePending=false
                val view=webView?:return
                if(destroyed||!isVisible)return
                view.invalidate();var canvas:Canvas?=null
                try{canvas=surfaceHolder.lockCanvas();if(canvas!=null)view.draw(canvas)}catch(_:Exception){}
                finally{if(canvas!=null)try{surfaceHolder.unlockCanvasAndPost(canvas)}catch(_:Exception){}}
                scheduleFrame()
            }
        }
        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(surfaceHolder:SurfaceHolder?){
            super.onCreate(surfaceHolder);setTouchEventsEnabled(true)
            val view=WebView(this@CosmosWallpaperService);webView=view
            view.setBackgroundColor(Color.BLACK);view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);configureWebView(view)
            view.webViewClient=object:WebViewClientCompat(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=assetLoader.shouldInterceptRequest(request.url)
                override fun onPageFinished(view:WebView?,url:String?){
                    super.onPageFinished(view,url)
                    view?.evaluateJavascript("(function(){['controlPanel','togglePanelBtn','wallpaperModal','exitWallpaperBtn','toast'].forEach(function(id){var e=document.getElementById(id);if(e)e.style.display='none'});if(typeof resizeCanvas==='function')resizeCanvas()})();",null)
                    view?.evaluateJavascript(AUDIO_REACTIVE_SCRIPT,null)
                    view?.postDelayed({if(!destroyed)view.evaluateJavascript("window.startCosmosAudioAnalysis && window.startCosmosAudioAnalysis()",null)},500)
                }
            }
            installPermissionHandler(view,this@CosmosWallpaperService,handler){destroyed}
            view.loadUrl(APP_ASSET_ORIGIN+STUDIO_PATH+"?nativeWallpaper=1")
        }
        override fun onSurfaceChanged(holder:SurfaceHolder?,format:Int,width:Int,height:Int){super.onSurfaceChanged(holder,format,width,height);resizeWebView(width,height);scheduleFrame()}
        private fun resizeWebView(width:Int,height:Int){if(width<=0||height<=0)return;val view=webView?:return;view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);view.evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas()",null);view.invalidate()}
        override fun onVisibilityChanged(visible:Boolean){super.onVisibilityChanged(visible);if(visible){webView?.onResume();scheduleFrame()}else{handler.removeCallbacks(drawFrame);framePending=false;webView?.onPause()}}
        override fun onTouchEvent(event:MotionEvent){webView?.dispatchTouchEvent(event);super.onTouchEvent(event)}
        private fun scheduleFrame(){if(destroyed||!isVisible||framePending||webView==null)return;framePending=true;handler.postDelayed(drawFrame,16L)}
        override fun onDestroy(){destroyed=true;handler.removeCallbacks(drawFrame);framePending=false;webView?.apply{stopLoading();onPause();removeAllViews();destroy()};webView=null;super.onDestroy()}
    }
}
