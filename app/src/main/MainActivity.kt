package com.cosmos.audiorhythmwallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
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
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

private const val STUDIO_ASSET = "file:///android_asset/Cosmos%20Generativo.html"

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
(function() {
  if (window.__cosmosRhythmBridge) return;
  window.__cosmosRhythmBridge = true;

  window.__cosmosNativeBands = {bass:0, low:0, vocal:0, treble:0, energy:0, kick:0};
  window.setCosmosNativeBands = function(bands) {
    if (!bands) return;
    window.__cosmosNativeBands = bands;
    if (window.state) {
      window.state.rhythmEnabled = true;
      window.state.rhythmSimulated = false;
      window.state.bassEnergy = bands.bass || 0;
      window.state.lowMidEnergy = bands.low || 0;
      window.state.vocalEnergy = bands.vocal || 0;
      window.state.trebleEnergy = bands.treble || 0;
      for (var i = 0; i < 72; i++) {
        var group = i < 18 ? bands.bass : i < 36 ? bands.low : i < 54 ? bands.vocal : bands.treble;
        window.state.spectrum[i] = Math.round(Math.max(0, Math.min(1, group || 0)) * 255);
      }
    }
  };

  var panel = document.getElementById('controlPanel');
  var meter = document.getElementById('rhythmMeterBox');
  if (meter) meter.classList.remove('hidden');

  var overlay = document.getElementById('cosmosRhythmVisualizer');
  if (!overlay) {
    overlay = document.createElement('canvas');
    overlay.id = 'cosmosRhythmVisualizer';
    overlay.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:5';
    document.body.appendChild(overlay);
  }
  var ctx = overlay.getContext('2d');
  var visualizer = 'radar';

  function resize() {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    overlay.width = Math.round(innerWidth * dpr);
    overlay.height = Math.round(innerHeight * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  addEventListener('resize', resize);
  resize();

  if (panel) {
    var box = document.createElement('div');
    box.id = 'cosmosVisualizerSelector';
    box.style.cssText = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:5px;margin:8px 0';
    var names = ['Radar','Barras','Olas','Flor','Túnel','ADN'];
    var modes = ['radar','bars','waves','bloom','tunnel','dna'];
    box.innerHTML = names.map(function(name, i) {
      return '<button type="button" data-rhythm-viz="' + modes[i] + '" style="padding:7px 3px;border:1px solid #52525b;border-radius:9px;background:#27272a;color:#e4e4e7;font-size:11px">' + name + '</button>';
    }).join('');
    var heading = document.createElement('div');
    heading.textContent = 'Visualización audiorítmica';
    heading.style.cssText = 'font-size:11px;color:#d4d4d8;margin-top:8px';
    panel.appendChild(heading);
    panel.appendChild(box);
    box.addEventListener('click', function(event) {
      var button = event.target.closest('[data-rhythm-viz]');
      if (!button) return;
      visualizer = button.dataset.rhythmViz;
      box.querySelectorAll('button').forEach(function(item) {
        item.style.borderColor = item === button ? '#d946ef' : '#52525b';
        item.style.color = item === button ? '#f5d0fe' : '#e4e4e7';
      });
    });
  }

  function draw() {
    var b = window.__cosmosNativeBands;
    var values = [b.bass || 0, b.low || 0, b.vocal || 0, b.treble || 0];
    var labels = [['bassPct','bassBar'],['lowMidPct','lowMidBar'],['vocalPct','vocalBar'],['treblePct','trebleBar']];
    labels.forEach(function(pair, i) {
      var amount = Math.round(Math.max(0, Math.min(1, values[i])) * 100);
      var text = document.getElementById(pair[0]);
      var bar = document.getElementById(pair[1]);
      if (text) text.textContent = amount + '%';
      if (bar) bar.style.width = amount + '%';
    });

    var status = document.getElementById('beatStatus');
    if (status) status.textContent = (b.kick || 0) > .3 ? 'Bombo detectado' : values[2] > .2 ? 'Voz / melodía' : values[1] > .2 ? 'Bajo / acordes' : values[3] > .18 ? 'Agudos' : (b.energy || 0) > .025 ? 'AUDIO INTERNO EN VIVO' : 'Esperando reproducción';

    var w = innerWidth, h = innerHeight;
    var cx = w / 2, cy = h / 2;
    var count = 72;
    var colors = ['#a855f7','#ec4899','#3b82f6','#10b981','#8b5cf6'];
    var spectrum = [];
    for (var i = 0; i < count; i++) {
      var group = i < 18 ? values[0] : i < 36 ? values[1] : i < 54 ? values[2] : values[3];
      spectrum.push(group);
    }

    ctx.clearRect(0, 0, w, h);
    ctx.save();
    ctx.lineCap = 'round';

    if (visualizer === 'radar') {
      var base = Math.min(w,h) * (.12 + values[0]*.08);
      for (var r=0; r<count; r++) {
        var v=spectrum[r], a=r*Math.PI*2/count-Math.PI/2;
        ctx.globalAlpha=.25+v*.75; ctx.strokeStyle=colors[r%5]; ctx.lineWidth=2+v*3;
        ctx.beginPath(); ctx.moveTo(cx+Math.cos(a)*base,cy+Math.sin(a)*base);
        ctx.lineTo(cx+Math.cos(a)*(base+8+v*Math.min(w,h)*.22),cy+Math.sin(a)*(base+8+v*Math.min(w,h)*.22)); ctx.stroke();
      }
    } else if (visualizer === 'bars') {
      var bw=w/count;
      for (var j=0;j<count;j++) {
        var bh=spectrum[j]*h*.32;
        ctx.globalAlpha=.35+spectrum[j]*.65; ctx.fillStyle=colors[j%5];
        ctx.fillRect(j*bw, cy-bh/2, Math.max(1,bw-2), bh);
      }
    } else if (visualizer === 'waves') {
      for (var row=0;row<4;row++) {
        ctx.globalAlpha=.45; ctx.strokeStyle=colors[row]; ctx.lineWidth=2;
        ctx.beginPath();
        for (var x=0;x<=w;x+=6) {
          var energy=spectrum[Math.floor((x/w)*(count-1))];
          var y=cy+(row-1.5)*35+Math.sin(x*.025+performance.now*.003+row)*energy*h*.12;
          if (x===0) ctx.moveTo(x,y); else ctx.lineTo(x,y);
        }
        ctx.stroke();
      }
    } else if (visualizer === 'bloom') {
      for (var petal=0;petal<36;petal++) {
        var pv=spectrum[(petal*2)%count], angle=petal*Math.PI*2/36, radius=20+pv*Math.min(w,h)*.28;
        ctx.globalAlpha=.3+pv*.7; ctx.strokeStyle=colors[petal%5]; ctx.lineWidth=2+pv*3;
        ctx.beginPath(); ctx.moveTo(cx+Math.cos(angle)*12,cy+Math.sin(angle)*12);
        ctx.quadraticCurveTo(cx+Math.cos(angle+0.55)*radius*.8,cy+Math.sin(angle+0.55)*radius*.8,cx+Math.cos(angle)*radius,cy+Math.sin(angle)*radius);ctx.stroke();
      }
    } else if (visualizer === 'tunnel') {
      var time=performance.now()/1000;
      for (var ring=0;ring<10;ring++) {
        var phase=(ring/10+time*.25)%1, radius2=18+phase*Math.min(w,h)*.48;
        ctx.globalAlpha=.15+phase*.55; ctx.strokeStyle=colors[ring%5]; ctx.lineWidth=2+values[ring%4]*3;
        ctx.beginPath();
        for (var side=0;side<72;side++) {
          var sa=side*Math.PI*2/72, deformation=1+spectrum[side]*.12;
          var px=cx+Math.cos(sa)*radius2*deformation, py=cy+Math.sin(sa)*radius2*deformation;
          if(side===0)ctx.moveTo(px,py);else ctx.lineTo(px,py);
        }
        ctx.closePath();ctx.stroke();
      }
    } else {
      for (var dna=0;dna<38;dna++) {
        var ratio=dna/37, xx=w*.08+ratio*w*.84, v=spectrum[dna*2%count];
        var amp=20+v*Math.min(w,h)*.16, angle2=ratio*Math.PI*4+performance.now()/500;
        ctx.globalAlpha=.3+v*.7;ctx.fillStyle=colors[dna%5];ctx.strokeStyle=colors[dna%5];
        var y1=cy+Math.sin(angle2)*amp,y2=cy-Math.sin(angle2)*amp;
        ctx.beginPath();ctx.arc(xx,y1,2+v*5,0,Math.PI*2);ctx.fill();
        ctx.beginPath();ctx.arc(xx,y2,2+v*5,0,Math.PI*2);ctx.fill();
        if(dna>0){};
      }
      ctx.globalAlpha=.6;ctx.strokeStyle='#d946ef';ctx.beginPath();
      for(var dnaLine=0;dnaLine<=38;dnaLine++){var rr=dnaLine/38,xx=w*.08+rr*w*.84,angle3=rr*Math.PI*4+performance.now()/500,yy=cy+Math.sin(angle3)*(20+values[2]*Math.min(w,h)*.16);if(dnaLine===0)ctx.moveTo(xx,yy);else ctx.lineTo(xx,yy)}ctx.stroke();
    }

    ctx.restore();
    requestAnimationFrame(draw);
  }

  requestAnimationFrame(draw);

  var rhythmButton = document.getElementById('rhythmToggleBtn');
  if (rhythmButton && !rhythmButton.dataset.cosmosNativeHooked) {
    rhythmButton.dataset.cosmosNativeHooked = '1';
    rhythmButton.addEventListener('click', function(event) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (window.CosmosApp) window.CosmosApp.enableAudioReaction();
    }, true);
  }
})();
"""

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var mediaProjection: MediaProjection? = null
    private val playbackAnalyzer = PlaybackCaptureAnalyzer()

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(result.resultCode, result.data!!)
            playbackAnalyzer.start(mediaProjection!!) { json ->
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.setCosmosNativeBands && window.setCosmosNativeBands($json)",
                        null
                    )
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
        view.setBackgroundColor(Color.rgb(9, 9, 11))
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
                    webView?.evaluateJavascript(
                        "window.setCosmosNativeBands && window.setCosmosNativeBands({bass:0,low:0,vocal:0,treble:0,energy:0,kick:0})",
                        null
                    )
                } else {
                    requestPlaybackCapture()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun setLiveWallpaper() {
            runOnUiThread {
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
                        Toast.makeText(this@MainActivity, "No se pudo abrir el selector.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        playbackAnalyzer.stop()
        mediaProjection?.stop()
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("CosmosApp")
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

private class PlaybackCaptureAnalyzer {
    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    fun isRunning(): Boolean = running

    @Synchronized
    fun stop() {
        running = false
        try { recorder?.stop() } catch (_: IllegalStateException) {}
        recorder?.release()
        recorder = null
        worker?.interrupt()
        worker = null
    }

    @Synchronized
    fun start(projection: MediaProjection, onBands: (String) -> Unit) {
        if (running || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minimumBuffer = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) return

        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimumBuffer, 4096))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (_: SecurityException) {
            return
        } catch (_: UnsupportedOperationException) {
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return
        }

        recorder = audioRecord
        running = true

        worker = thread(name = "cosmos-playback-analyzer", start = true) {
            val samples = ShortArray(1024)
            var bassEnvelope = 0f
            try {
                audioRecord.startRecording()
                while (running && !Thread.currentThread().isInterrupted) {
                    val count = audioRecord.read(samples, 0, samples.size)
                    if (count <= 0) continue

                    var squareSum = 0.0
                    for (i in 0 until count) {
                        val value = samples[i].toDouble()
                        squareSum += value * value
                    }
                    val rms = sqrt(squareSum / count)
                    val bass = bandLevel(samples, count, intArrayOf(45,65,85,110,135))
                    val low = bandLevel(samples, count, intArrayOf(160,210,270,340,420))
                    val vocal = bandLevel(samples, count, intArrayOf(500,700,950,1300,1800,2400))
                    val treble = bandLevel(samples, count, intArrayOf(3000,4000,5200,6500))
                    val energy = (rms / 10000.0).toFloat().coerceIn(0f, 1f)
                    val kick = (bass - bassEnvelope - 0.04f).coerceIn(0f, 1f)
                    bassEnvelope = bassEnvelope * 0.82f + bass * 0.18f

                    onBands("{\"bass\":$bass,\"low\":$low,\"vocal\":$vocal,\"treble\":$treble,\"energy\":$energy,\"kick\":$kick}")
                    Thread.sleep(24)
                }
            } catch (_: IllegalStateException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running = false
                try { audioRecord.stop() } catch (_: IllegalStateException) {}
                audioRecord.release()
                if (recorder === audioRecord) recorder = null
            }
        }
    }

    private fun bandLevel(samples: ShortArray, count: Int, frequencies: IntArray): Float {
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
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) view.draw(canvas)
                } catch (_: Exception) {
                } finally {
                    if (canvas != null) {
                        try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }
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
            if (width > 0 && height > 0) {
                webView?.apply {
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, width, height)
                    evaluateJavascript("if(typeof resizeCanvas==='function')resizeCanvas()", null)
                }
            }
            scheduleFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                webView?.onResume()
                scheduleFrame()
            } else {
                handler.removeCallbacks(drawFrame)
                framePending = false
                webView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            webView?.dispatchTouchEvent(event)
            super.onTouchEvent(event)
        }

        private fun scheduleFrame() {
            if (destroyed || !isVisible || framePending || webView == null) return
            framePending = true
            handler.postDelayed(drawFrame, 16L)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacks(drawFrame)
            framePending = false
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
