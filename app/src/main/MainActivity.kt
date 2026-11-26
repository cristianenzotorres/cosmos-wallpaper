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

private const val STUDIO_URL = "https://appassets.androidplatform.net/assets/Cosmos%20Generativo.html"

private val STUDIO_FIX_SCRIPT = """
(function() {
  if (window.__cosmosFixInstalled) return;
  window.__cosmosFixInstalled = true;

  // A touch on the artwork starts a quiet, continuous Web Audio voice. Moving vertically
  // glides its pitch; releasing the finger fades it out. No remote audio library required.
  var audioButton = document.getElementById('audioToggleBtn');
  var audioOff = document.getElementById('audioIconOff');
  var audioOn = document.getElementById('audioIconOn');
  var audioContext = null, voice = null, voiceGain = null, voiceFilter = null;
  var audioActive = false, pointerDown = false;
  function ensureAudio() {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return false;
    if (!audioContext) {
      audioContext = new AC();
      voice = audioContext.createOscillator();
      voice.type = 'sine';
      voiceGain = audioContext.createGain();
      voiceGain.gain.value = 0;
      voiceFilter = audioContext.createBiquadFilter();
      voiceFilter.type = 'lowpass';
      voiceFilter.frequency.value = 1800;
      voice.connect(voiceFilter); voiceFilter.connect(voiceGain); voiceGain.connect(audioContext.destination);
      voice.start();
    }
    if (audioContext.state === 'suspended') audioContext.resume();
    return true;
  }
  function setVoice(x, y, on) {
    if (!ensureAudio()) return;
    var now = audioContext.currentTime;
    var h = Math.max(1, window.innerHeight || 640);
    var w = Math.max(1, window.innerWidth || 360);
    var pitch = 220 * Math.pow(2, 2 * (1 - Math.max(0, Math.min(1, y / h))));
    voice.frequency.setTargetAtTime(pitch, now, .045);
    voiceFilter.frequency.setTargetAtTime(600 + 4200 * Math.max(0, Math.min(1, x / w)), now, .08);
    voiceGain.gain.setTargetAtTime(on && audioActive ? .14 : 0, now, on ? .04 : .18);
  }
  var art = document.getElementById('artCanvas');
  if (art) {
    art.addEventListener('pointerdown', function(e) {
      pointerDown = true;
      if (audioActive) setVoice(e.clientX, e.clientY, true);
    }, {passive:true});
    art.addEventListener('pointermove', function(e) {
      if (pointerDown && audioActive) setVoice(e.clientX, e.clientY, true);
    }, {passive:true});
    ['pointerup','pointercancel','pointerleave'].forEach(function(type) {
      art.addEventListener(type, function() { pointerDown = false; if (audioActive) setVoice(0,0,false); }, {passive:true});
    });
  }
  if (audioButton && !audioButton.dataset.cosmosAudioHooked) {
    audioButton.dataset.cosmosAudioHooked = '1';
    audioButton.addEventListener('click', function(e) {
      e.preventDefault(); e.stopImmediatePropagation();
      audioActive = !audioActive;
      if (audioActive) {
        if (ensureAudio()) {
          if (audioOff) audioOff.classList.add('hidden');
          if (audioOn) audioOn.classList.remove('hidden');
          if (pointerDown) setVoice(window.innerWidth/2, window.innerHeight/2, true);
        } else audioActive = false;
      } else {
        if (voiceGain && audioContext) voiceGain.gain.setTargetAtTime(0, audioContext.currentTime, .15);
        if (audioOff) audioOff.classList.remove('hidden');
        if (audioOn) audioOn.classList.add('hidden');
      }
    }, true);
  }

  // Add selectable audio visualizers because the supplied HTML only rendered one style.
  var panel = document.getElementById('controlPanel');
  var overlay = document.createElement('canvas');
  overlay.id = 'cosmosAudioViz';
  overlay.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:5';
  document.body.appendChild(overlay);
  var octx = overlay.getContext('2d');
  var visualizer = 'radar', analyser = null, analyserData = null;
  var originalGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia;
  if (originalGetUserMedia && !navigator.mediaDevices.__cosmosWrapped) {
    navigator.mediaDevices.__cosmosWrapped = true;
    navigator.mediaDevices.getUserMedia = function(constraints) {
      return originalGetUserMedia.call(navigator.mediaDevices, constraints).then(function(stream) {
        try {
          var AC = window.AudioContext || window.webkitAudioContext;
          if (AC) {
            var ac = new AC();
            var source = ac.createMediaStreamSource(stream);
            analyser = ac.createAnalyser(); analyser.fftSize = 1024; analyser.smoothingTimeConstant = .72;
            source.connect(analyser); analyserData = new Uint8Array(analyser.frequencyBinCount);
            stream.getTracks().forEach(function(track) { track.addEventListener('ended', function(){ analyser = null; }); });
          }
        } catch (_) {}
        return stream;
      });
    };
  }
  if (panel) {
    var vizBox = document.createElement('div');
    vizBox.style.cssText = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:5px;margin:8px 0';
    vizBox.innerHTML = ['Radar','Barras','Olas','Flor','Túnel','ADN'].map(function(name, i) {
      return '<button type="button" data-cosmos-viz="'+['radar','bars','waves','bloom','tunnel','dna'][i]+'" style="padding:7px 3px;border:1px solid #52525b;border-radius:9px;background:#27272a;color:#e4e4e7;font-size:10px">'+name+'</button>';
    }).join('');
    var heading = document.createElement('div');
    heading.textContent = 'Visualización audiorítmica';
    heading.style.cssText = 'font-size:11px;color:#d4d4d8;margin-top:8px';
    panel.appendChild(heading); panel.appendChild(vizBox);
    vizBox.addEventListener('click', function(e) {
      var btn = e.target.closest('[data-cosmos-viz]'); if (!btn) return;
      visualizer = btn.dataset.cosmosViz;
      vizBox.querySelectorAll('button').forEach(function(b) { b.style.borderColor = b === btn ? '#d946ef' : '#52525b'; b.style.color = b === btn ? '#f5d0fe' : '#e4e4e7'; });
    });
  }

  function drawViz() {
    var w = window.innerWidth || 360, h = window.innerHeight || 640;
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    if (overlay.width !== Math.round(w*dpr) || overlay.height !== Math.round(h*dpr)) {
      overlay.width = Math.round(w*dpr); overlay.height = Math.round(h*dpr);
    }
    octx.setTransform(dpr,0,0,dpr,0,0); octx.clearRect(0,0,w,h);
    if (analyser && analyserData) analyser.getByteFrequencyData(analyserData);
    var t = performance.now()/1000, cx=w/2, cy=h/2, count=72;
    var colors=['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'];
    function value(i) {
      return analyserData ? analyserData[Math.floor(i/count*(analyserData.length-1))]/255 : .12+.12*(1+Math.sin(t*3+i*.42));
    }
    octx.save(); octx.lineWidth=2; octx.lineCap='round';
    if (visualizer==='radar') {
      var base=Math.min(w,h)*.15;
      for(var i=0;i<count;i++){var v=value(i),a=i*Math.PI*2/count-Math.PI/2;
        octx.globalAlpha=.3+v*.7;octx.strokeStyle=colors[i%5];octx.beginPath();octx.moveTo(cx+Math.cos(a)*base,cy+Math.sin(a)*base);octx.lineTo(cx+Math.cos(a)*(base+8+v*Math.min(w,h)*.2),cy+Math.sin(a)*(base+8+v*Math.min(w,h)*.2));octx.stroke();}
    } else if (visualizer==='bars') {
      var bw=w/count;
      for(var j=0;j<count;j++){var bv=value(j),bh=bv*h*.28;octx.globalAlpha=.35+bv*.65;octx.fillStyle=colors[j%5];octx.fillRect(j*bw,cy-bh,Math.max(1,bw-2),bh*2);}
    } else if (visualizer==='waves') {
      for(var row=0;row<4;row++){octx.globalAlpha=.45;octx.strokeStyle=colors[row];octx.beginPath();for(var x=0;x<=w;x+=6){var px=x/w,energy=value(Math.floor(px*(count-1)));var y=cy+(row-1.5)*35+Math.sin(px*12+t*(1+row*.2))* (12+energy*65)*Math.sin(px*Math.PI);if(x===0)octx.moveTo(x,y);else octx.lineTo(x,y);}octx.stroke();}
    } else if (visualizer==='bloom') {
      for(var p=0;p<36;p++){var bv2=value(p*2),aa=p*Math.PI*2/36,rr=20+bv2*Math.min(w,h)*.28;octx.globalAlpha=.3+bv2*.7;octx.strokeStyle=colors[p%5];octx.beginPath();octx.moveTo(cx+Math.cos(aa)*14,cy+Math.sin(aa)*14);octx.quadraticCurveTo(cx+Math.cos(aa-.12)*rr*.7,cy+Math.sin(aa-.12)*rr*.7,cx+Math.cos(aa)*rr,cy+Math.sin(aa)*rr);octx.stroke();}
    } else if (visualizer==='tunnel') {
      for(var ring=0;ring<9;ring++){var phase=(ring/9+t*.25)%1,rad=18+phase*Math.min(w,h)*.48;octx.globalAlpha=.15+phase*.55;octx.strokeStyle=colors[ring%5];octx.beginPath();for(var side=0;side<=6;side++){var ang=side*Math.PI/3+t*.4+phase;var xx=cx+Math.cos(ang)*rad,yy=cy+Math.sin(ang)*rad;if(side===0)octx.moveTo(xx,yy);else octx.lineTo(xx,yy);}octx.stroke();}
    } else {
      for(var n=0;n<38;n++){var ratio=n/37,xx=w*.08+ratio*w*.84,vv=value(n*2),amp=20+vv*Math.min(w,h)*.16,ang=ratio*Math.PI*4+t*2;var y1=cy+Math.sin(ang)*amp,y2=cy-Math.sin(ang)*amp;octx.globalAlpha=.35+vv*.6;octx.strokeStyle=colors[n%5];octx.beginPath();octx.moveTo(xx,y1);octx.lineTo(xx,y2);octx.stroke();octx.beginPath();octx.arc(xx,y1,2+vv*3,0,Math.PI*2);octx.fillStyle=colors[0];octx.fill();octx.beginPath();octx.arc(xx,y2,2+vv*3,0,Math.PI*2);octx.fillStyle=colors[1];octx.fill();}
    }
    octx.restore(); requestAnimationFrame(drawViz);
  }
  requestAnimationFrame(drawViz);
})();
""".trimIndent()

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingMicAction: (() -> Unit)? = null
    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction; pendingMicAction = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "El modo audiorítmico requiere permiso de micrófono.", Toast.LENGTH_LONG).show()
    }
    private val assetLoader by lazy { createAssetLoader() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this); webView = view
        configureWebView(view, assetLoader)
        view.addJavascriptInterface(AppBridge(), "CosmosApp")
        view.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(STUDIO_FIX_SCRIPT, null)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val trustedOrigin = request.origin?.host == "appassets.androidplatform.net"
                    val audioOnly = request.resources.isNotEmpty() && request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (trustedOrigin && audioOnly && granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                }
            }
        }
        setContentView(view)
        view.loadUrl(STUDIO_URL)
    }

    private fun requireMicrophone(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingMicAction = action; requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun openWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, CosmosWallpaperService::class.java))
        }
        try { startActivity(intent) } catch (_: Exception) {
            try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            catch (_: Exception) { Toast.makeText(this, "No se pudo abrir el selector de fondos.", Toast.LENGTH_LONG).show() }
        }
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface fun enableAudioReaction() {
            runOnUiThread { requireMicrophone { webView?.evaluateJavascript("window.toggleRhythmMode && window.toggleRhythmMode();", null) } }
        }
        @android.webkit.JavascriptInterface fun setLiveWallpaper() {
            runOnUiThread { requireMicrophone { openWallpaperPicker() } }
        }
    }

    override fun onResume() { super.onResume(); webView?.onResume() }
    override fun onPause() { webView?.onPause(); super.onPause() }
    override fun onDestroy() {
        webView?.apply { stopLoading(); removeJavascriptInterface("CosmosApp"); removeAllViews(); destroy() }
        webView = null; super.onDestroy()
    }
}

class CosmosWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = CosmosWebViewEngine()

    inner class CosmosWebViewEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val assetLoader by lazy { createAssetLoader() }
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
            super.onCreate(surfaceHolder); setTouchEventsEnabled(true)
            val view = WebView(this@CosmosWallpaperService); webView = view
            view.setBackgroundColor(Color.BLACK); view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            configureWebView(view, assetLoader)
            view.webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript("""(function(){['controlPanel','togglePanelBtn','wallpaperModal','exitWallpaperBtn','toast'].forEach(function(id){var e=document.getElementById(id);if(e)e.style.display='none'});if(typeof resizeCanvas==='function')resizeCanvas();})();""", null)
                    view?.evaluateJavascript(STUDIO_FIX_SCRIPT, null)
                }
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    handler.post {
                        val trustedOrigin = request.origin?.host == "appassets.androidplatform.net"
                        val audioOnly = request.resources.isNotEmpty() && request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                        val micGranted = ContextCompat.checkSelfPermission(this@CosmosWallpaperService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (trustedOrigin && audioOnly && micGranted && !destroyed) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                    }
                }
            }
            view.loadUrl(STUDIO_URL)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height); resizeWebView(width, height); scheduleFrame()
        }
        private fun resizeWebView(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val view = webView ?: return
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            view.evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas();", null); view.invalidate()
        }
        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) { webView?.onResume(); scheduleFrame() }
            else { handler.removeCallbacks(drawFrame); framePending = false; webView?.onPause() }
        }
        override fun onTouchEvent(event: MotionEvent) { webView?.dispatchTouchEvent(event); super.onTouchEvent(event) }
        private fun scheduleFrame() {
            if (destroyed || !isVisible || framePending || webView == null) return
            framePending = true; handler.postDelayed(drawFrame, 16L)
        }
        override fun onDestroy() {
            destroyed = true; handler.removeCallbacks(drawFrame); framePending = false
            webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }; webView = null
            super.onDestroy()
        }
    }
}

private fun createAssetLoader(): WebViewAssetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(CosmosApplicationContextHolder.context))
    .build()

private object CosmosApplicationContextHolder {
    lateinit var context: android.content.Context
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(view: WebView, assetLoader: WebViewAssetLoader) {
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
