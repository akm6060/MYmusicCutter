package com.mymusic.cutter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native player, registered for the page as window.MYmusicNativePlayer.
 * Video and sound are decoded by Android's media engine (ExoPlayer); the picture is drawn on a
 * TextureView placed exactly under the page's video area, and the sound passes through the
 * C++ effects engine before Android's audio output (correct lip-sync, also on Bluetooth).
 */
@UnstableApi
class NativePlayer(
    private val activity: MainActivity,
    private val webView: WebView,
    private val cutter: MYmusicNativeCutter,
    private val textureView: TextureView,
    private val surfaceView: SurfaceView
) {
    private val main = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density
    private val worker = Executors.newSingleThreadExecutor()
    private val fx = FxAudioProcessor()
    val player: ExoPlayer

    private var videoW = 0
    private var videoH = 0
    private var rotation = 0
    private var par = 1f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var fill = false
    private var cornerPx = 0f
    // The picture normally goes to a SurfaceView (shown directly by the display: true black level,
    // correct colour range and HDR). Zoom/pan and colour settings need the TextureView instead.
    private var useTexture = false
    private var videoShown = true
    private var stageX = 0f
    private var stageY = 0f
    private var stageW = 1f
    private var stageH = 1f
    private var currentUri: Uri? = null
    private var contentW = 1f
    private var contentH = 1f

    private fun updateFrameEffect() {
        PictureFx.zoom = zoom
        PictureFx.panX = if (contentW > 1f) panX * density / (contentW / 2f) else 0f
        PictureFx.panY = if (contentH > 1f) -panY * density / (contentH / 2f) else 0f
    }
    private var timeoutRetries = 0
    private val retryReset = Runnable { if (player.playbackState == Player.STATE_READY) timeoutRetries = 0 }
    private var effectsOn = false
    private var effectsBroken = false
    private var pendingSeek = false
    private var metaSent = false
    private var scrubbing = false
    private var released = false
    @Volatile private var thumbJob = -1

    // ------------------------------------------------------------------ events to the page

    private fun send(type: String, data: JSONObject = JSONObject()) {
        if (released) return
        val js = "window.__np&&window.__np(${JSONObject.quote(type)},$data)"
        main.post { if (!released) webView.evaluateJavascript(js, null) }
    }

    private fun posSec(): Double = max(0L, player.currentPosition) / 1000.0

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (player.isPlaying) {
                send("pos", JSONObject().put("t", posSec()).put("playing", true))
                main.postDelayed(this, 200)
            }
        }
    }

    private var redrawFrom = 0L
    private var redrawTries = 0
    private val redrawCheck: Runnable = object : Runnable {
        override fun run() {
            if (released) return
            if (player.playbackState == Player.STATE_BUFFERING && redrawTries++ < 8) { main.postDelayed(this, 300); return }
            if (player.playbackState == Player.STATE_READY && kotlin.math.abs(player.currentPosition - redrawFrom) < 60) {
                Log.i("MMP", "redraw at new size")
                redrawNow()
            }
        }
    }
    // a second look a little later: during a screen rotation the window settles in several steps
    private val redrawLate = Runnable { if (!released && !player.isPlaying) { redrawFrom = player.currentPosition; redrawTries = 0; redrawCheck.run() } }

    private val refreshFrame = Runnable {
        if (player.playbackState == Player.STATE_READY && !player.isPlaying) redrawNow()
    }

    /** A seek to exactly the current spot is skipped by the player, so go 1 ms on: same picture, drawn again. */
    private fun redrawNow() {
        val d = player.duration
        var t = player.currentPosition + 1
        if (d > 0 && t >= d) t = maxOf(0L, player.currentPosition - 1)
        player.seekTo(t)
    }

    private val seekFallback = Runnable {
        if (pendingSeek && player.playbackState == Player.STATE_READY) seekDone()
    }

    private fun seekDone() {
        pendingSeek = false
        main.removeCallbacks(seekFallback)
        send("seeked", JSONObject().put("t", posSec()))
    }

    private fun sendMeta() {
        val d = player.duration
        val o = JSONObject().put("duration", if (d == C.TIME_UNSET) 0.0 else d / 1000.0)
        val (w, h) = displaySize()
        o.put("w", w).put("h", h)
        send("meta", o)
    }

    /**
     * The picture size from the video track itself. With the picture effects on, the player only
     * reports the size once frames are being shown, so Fill had nothing to work with before.
     */
    private fun sizeFromFormat() {
        if (videoW > 0 && videoH > 0) return
        val f = player.videoFormat ?: return
        if (f.width <= 0 || f.height <= 0) return
        videoW = f.width; videoH = f.height
        rotation = f.rotationDegrees
        par = if (f.pixelWidthHeightRatio > 0) f.pixelWidthHeightRatio else 1f
        Log.i("MMP", "size from track ${f.width}x${f.height} rot=${f.rotationDegrees}")
        applyTransform()
        layoutSurface()
        val (w, h) = displaySize()
        if (w > 0) send("size", JSONObject().put("w", w).put("h", h))
    }

    private fun displaySize(): Pair<Int, Int> {
        if (videoW <= 0 || videoH <= 0) return Pair(0, 0)
        val w = (videoW * par).roundToInt()
        return if (rotation == 90 || rotation == 270) Pair(videoH, w) else Pair(w, videoH)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> send("waiting")
                Player.STATE_READY -> {
                    main.removeCallbacks(retryReset); main.postDelayed(retryReset, 4000)
                    sizeFromFormat()
                    if (!metaSent) { metaSent = true; sendMeta() }
                    if (pendingSeek) seekDone()
                }
                Player.STATE_ENDED -> {
                    main.removeCallbacks(ticker)
                    send("ended", JSONObject().put("t", posSec()))
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackService.update(activity, cachedTitle(), isPlaying)
            if (isPlaying) {
                activity.askNotificationsOnce()
                send("playing", JSONObject().put("t", posSec()))
                main.removeCallbacks(ticker)
                main.post(ticker)
            } else {
                main.removeCallbacks(ticker)
                send("pos", JSONObject().put("t", posSec()).put("playing", false))
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                send("paused", JSONObject().put("t", posSec()))   // headphones unplugged, call, other app
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                pendingSeek = true
                main.removeCallbacks(seekFallback)
                main.postDelayed(seekFallback, 600)
            }
        }

        override fun onRenderedFirstFrame() {
            if (pendingSeek) seekDone()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.i("MMP", "videoSize ${videoSize.width}x${videoSize.height} rot=${videoSize.unappliedRotationDegrees} par=${videoSize.pixelWidthHeightRatio}")
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            videoW = videoSize.width
            videoH = videoSize.height
            @Suppress("DEPRECATION")
            rotation = videoSize.unappliedRotationDegrees
            par = if (videoSize.pixelWidthHeightRatio > 0) videoSize.pixelWidthHeightRatio else 1f
            applyTransform()
            layoutSurface()
            val (w, h) = displaySize()
            if (w > 0) send("size", JSONObject().put("w", w).put("h", h))
            if (metaSent) sendMeta()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Was it the picture pipeline (brightness / sharpness / zoom)?
            val trace = try { Log.getStackTraceString(error) } catch (_: Throwable) { "" }
            val fromEffects = trace.contains("androidx.media3.effect") || trace.contains("VideoFrameProcessing")
            Log.i("MMP", "player error ${error.errorCodeName} effects=$effectsOn fromEffects=$fromEffects retries=$timeoutRetries")
            if ((error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
                 error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                 (fromEffects && timeoutRetries >= 1)) && !effectsBroken) {
                // Picture adjustments failed on this phone: play without them and use the simpler method.
                effectsOn = false; effectsBroken = true
                val pos = player.currentPosition
                val wasPlaying = player.playWhenReady
                player.setVideoEffects(emptyList())
                currentUri?.let { player.setMediaItem(MediaItem.fromUri(it), pos) }
                player.prepare(); player.playWhenReady = wasPlaying
                cutter.pageMessage("Sharpness is not supported on this phone; colour settings use a simpler method.")
                main.post { val pf = PictureFx; if (!pf.isNeutral()) { val p = Paint(); p.colorFilter = ColorMatrixColorFilter(colourMatrix(pf.brightness, pf.contrast, pf.saturation, pf.hue, 0f)); textureView.setLayerPaint(p); applyOutput(true) } }
                return
            }
            val recoverable = error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
            if (recoverable && timeoutRetries < 3) {
                // A slow window hand-over, or a rare internal hiccup of the picture pipeline during a
                // seek: reload the same file at the same spot instead of showing an error.
                timeoutRetries++
                Log.i("MMP", "recover ${error.errorCodeName}: retry $timeoutRetries")
                val pos = player.currentPosition
                val play = player.playWhenReady
                currentUri?.let { player.setMediaItem(MediaItem.fromUri(it), pos) }
                player.prepare(); player.playWhenReady = play
                return
            }
            val msg = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                    "This phone has no decoder for this video or sound format"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "Cannot read this file type"
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    "The file could not be opened (moved, deleted or no permission)"
                else -> "Cannot play this file (" + error.errorCodeName + ")"
            }
            send("error", JSONObject().put("message", msg))
        }
    }

    // Set up after the listener above exists (Kotlin runs initialisers top to bottom).
    init {
        val renderers = object : DefaultRenderersFactory(activity) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(fx))
                .build()
        }
        renderers.setEnableDecoderFallback(true)
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // Raw .aac (ADTS) files have no index: let the player estimate their length so they can be
        // sought, cut and searched for songs like MP3 / M4A files.
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        player = ExoPlayer.Builder(activity, renderers,
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(activity, extractors))
            .setDetachSurfaceTimeoutMs(8000)   // give a busy picture pipeline time to let go of the window
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)   // keeps the CPU awake for background sound
        player.setVideoSurfaceView(surfaceView)
        // When paused, nothing redraws the picture after Fit / Fill / full screen change the window
        // size, so the old-size picture stayed in a corner: draw the current frame again.
        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("MMP", "surfaceChanged ${width}x${height} playing=${player.isPlaying}")
                if (!effectsOn) return
                // Redraw unless frames are clearly flowing (playing and the position moving).
                val p0 = player.currentPosition
                main.removeCallbacks(redrawCheck)
                main.removeCallbacks(redrawLate)
                redrawFrom = p0
                redrawTries = 0
                main.postDelayed(redrawCheck, 250)
                main.postDelayed(redrawLate, 1100)
            }
        })
        textureView.visibility = View.INVISIBLE
        installEffects()
        player.addListener(listener)
        textureView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
        textureView.clipToOutline = true
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
    }

    // ------------------------------------------------------------------ picture

    private fun applyTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val r90 = rotation == 90 || rotation == 270
        val cw = videoW * par
        val ch = videoH.toFloat()
        val aspect = if (r90) ch / cw else cw / ch
        val heightLimited = (vw / vh > aspect) != fill
        val dw: Float
        val dh: Float
        if (heightLimited) { dh = vh; dw = vh * aspect } else { dw = vw; dh = vw / aspect }
        val m = Matrix()
        if (r90) {
            m.setScale(dh / vw, dw / vh, vw / 2, vh / 2)
            m.postRotate(rotation.toFloat(), vw / 2, vh / 2)
        } else {
            m.setScale(dw / vw, dh / vh, vw / 2, vh / 2)
            if (rotation == 180) m.postRotate(180f, vw / 2, vh / 2)
        }
        if (useTexture) {
            m.postScale(zoom, zoom, vw / 2, vh / 2)
            m.postTranslate(panX * density, panY * density)
        }
        textureView.setTransform(m)
        textureView.invalidate()
    }

    /** Places the SurfaceView on exactly the picture area (fit or fill) inside the page's video area. */
    private fun layoutSurface() {
        val lp = (surfaceView.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(1, 1)
        val (vw0, vh0) = displaySize()
        var x = stageX; var y = stageY; var w = stageW; var h = stageH
        if (vw0 > 0 && vh0 > 0 && stageW > 1 && stageH > 1) {
            val aspect = vw0.toFloat() / vh0
            val heightLimited = (stageW / stageH > aspect) != fill
            if (heightLimited) { h = stageH; w = stageH * aspect } else { w = stageW; h = stageW / aspect }
            x = stageX + (stageW - w) / 2
            y = stageY + (stageH - h) / 2
        }
        contentW = w; contentH = h
        Log.i("MMP", "layout stage=${stageX.toInt()},${stageY.toInt()} ${stageW.toInt()}x${stageH.toInt()} video=${vw0}x${vh0} fill=$fill -> ${x.toInt()},${y.toInt()} ${w.toInt()}x${h.toInt()} texture=$useTexture")
        updateFrameEffect()
        val nl = x.roundToInt(); val nt = y.roundToInt(); val nw = max(1, w.roundToInt()); val nh = max(1, h.roundToInt())
        if (lp.leftMargin != nl || lp.topMargin != nt || lp.width != nw || lp.height != nh) {
            lp.leftMargin = nl; lp.topMargin = nt; lp.width = nw; lp.height = nh
            surfaceView.layoutParams = lp
        }
    }

    /**
     * The picture settings (brightness, contrast, saturation, hue, sharpness) are processed by the
     * player on the graphics chip. They must be in place before a video starts; their values are
     * then read for every frame, so moving a slider changes the picture at once.
     */
    private fun installEffects() {
        if (effectsOn || effectsBroken) return
        try {
            player.setVideoEffects(listOf(PictureFx.colour, PictureFx.sharpen, PictureFx.frame))
            effectsOn = true
        } catch (_: Exception) {
            effectsBroken = true
        }
    }

    private fun applyOutput(wantTexture: Boolean) {
        if (wantTexture != useTexture) {
            useTexture = wantTexture
            if (wantTexture) {
                textureView.visibility = View.VISIBLE
                player.setVideoTextureView(textureView)
            } else {
                surfaceView.visibility = View.VISIBLE
                player.setVideoSurfaceView(surfaceView)
            }
        }
        surfaceView.visibility = if (!useTexture && videoShown) View.VISIBLE else View.GONE
        textureView.visibility = if (useTexture && videoShown) View.VISIBLE else View.INVISIBLE
    }

    private fun colourMatrix(b: Float, c: Float, s: Float, hue: Float, sepia: Float): ColorMatrix {
        val cm = ColorMatrix()
        val bb = b / 100f
        cm.postConcat(ColorMatrix(floatArrayOf(bb, 0f, 0f, 0f, 0f, 0f, bb, 0f, 0f, 0f, 0f, 0f, bb, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val cc = c / 100f
        val t = 128f * (1 - cc)
        cm.postConcat(ColorMatrix(floatArrayOf(cc, 0f, 0f, 0f, t, 0f, cc, 0f, 0f, t, 0f, 0f, cc, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val ss = s / 100f
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.213f + 0.787f * ss, 0.715f - 0.715f * ss, 0.072f - 0.072f * ss, 0f, 0f,
            0.213f - 0.213f * ss, 0.715f + 0.285f * ss, 0.072f - 0.072f * ss, 0f, 0f,
            0.213f - 0.213f * ss, 0.715f - 0.715f * ss, 0.072f + 0.928f * ss, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        val a = Math.toRadians(hue.toDouble())
        val co = cos(a).toFloat()
        val si = sin(a).toFloat()
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.213f + co * 0.787f - si * 0.213f, 0.715f - co * 0.715f - si * 0.715f, 0.072f - co * 0.072f + si * 0.928f, 0f, 0f,
            0.213f - co * 0.213f + si * 0.143f, 0.715f + co * 0.285f + si * 0.140f, 0.072f - co * 0.072f - si * 0.283f, 0f, 0f,
            0.213f - co * 0.213f - si * 0.787f, 0.715f - co * 0.715f + si * 0.715f, 0.072f + co * 0.928f + si * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        val p = 1 - sepia / 100f
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.393f + 0.607f * p, 0.769f - 0.769f * p, 0.189f - 0.189f * p, 0f, 0f,
            0.349f - 0.349f * p, 0.686f + 0.314f * p, 0.168f - 0.168f * p, 0f, 0f,
            0.272f - 0.272f * p, 0.534f - 0.534f * p, 0.131f + 0.869f * p, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        return cm
    }

    // ------------------------------------------------------------------ calls from the page

    private fun uriFor(url: String): Uri? {
        val u = Uri.parse(url)
        if (u.host == "appassets.androidplatform.net" && (u.path ?: "").startsWith("/mymusic-shared/")) {
            return cutter.playableUri(u.lastPathSegment ?: "")
        }
        return if (u.scheme == "content" || u.scheme == "file" || u.scheme == "http" || u.scheme == "https") u else null
    }

    @JavascriptInterface
    fun load(url: String) {
        val uri = uriFor(url)
        main.post {
            if (uri == null) {
                send("error", JSONObject().put("message", "The file could not be found"))
                return@post
            }
            metaSent = false
            pendingSeek = false
            timeoutRetries = 0
            currentUri = uri
            videoW = 0; videoH = 0; rotation = 0; par = 1f
            player.setSeekParameters(SeekParameters.EXACT)
            installEffects()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.playWhenReady = false
            player.prepare()
        }
    }

    @JavascriptInterface
    fun stop() {
        main.post { player.stop(); player.clearMediaItems(); metaSent = false }
    }

    @JavascriptInterface
    fun play() {
        main.post {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        }
    }

    @JavascriptInterface
    fun pause() {
        main.post { player.playWhenReady = false }
    }

    fun pauseFromActivity() {
        player.playWhenReady = false
    }

    /** Play / Pause from the notification. */
    fun remoteToggle() {
        if (player.isPlaying || player.playWhenReady) {
            player.playWhenReady = false
            send("paused", JSONObject().put("t", posSec()))
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        }
    }

    private var titleUri: Uri? = null
    private var titleText = ""
    private fun cachedTitle(): String {
        val u = currentUri
        if (u != titleUri) { titleUri = u; titleText = titleFor(u) }
        return titleText
    }

    private fun titleFor(u: Uri?): String {
        if (u == null) return "MYmusic Cutter"
        var name: String? = null
        if (u.scheme == "content") {
            try {
                activity.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            } catch (_: Exception) {
            }
        }
        if (name.isNullOrBlank()) name = u.lastPathSegment?.substringAfterLast('/')
        return (name ?: "MYmusic Cutter").substringBeforeLast('.')
    }

    @JavascriptInterface
    fun seek(ms: Double) {
        main.post { player.seekTo(max(0.0, ms).toLong()) }
    }

    /** While a caliper or the cursor is dragged, seek to the nearest key frame (fast); exact on release. */
    @JavascriptInterface
    fun setScrubbing(on: Boolean) {
        main.post {
            scrubbing = on
            player.setSeekParameters(if (on) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        }
    }

    @JavascriptInterface
    fun setSpeed(rate: Double) {
        main.post { player.playbackParameters = PlaybackParameters(rate.toFloat().coerceIn(0.25f, 6f)) }
    }

    @JavascriptInterface
    fun setVideoRect(x: Double, y: Double, w: Double, h: Double, radius: Double) {
        main.post {
            stageX = (x * density).toFloat(); stageY = (y * density).toFloat()
            stageW = max(1f, (w * density).toFloat()); stageH = max(1f, (h * density).toFloat())
            layoutSurface()
            val lp = (textureView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(0, 0)
            lp.leftMargin = (x * density).roundToInt()
            lp.topMargin = (y * density).roundToInt()
            lp.width = max(1, (w * density).roundToInt())
            lp.height = max(1, (h * density).roundToInt())
            cornerPx = (radius * density).toFloat()
            textureView.layoutParams = lp
            textureView.invalidateOutline()
        }
    }

    @JavascriptInterface
    fun setVideoVisible(on: Boolean) {
        main.post { videoShown = on; textureView.alpha = if (on) 1f else 0f; applyOutput(useTexture) }
    }

    @JavascriptInterface
    fun setPicture(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        main.post {
            zoom = o.optDouble("z", 1.0).toFloat().coerceIn(1f, 5f)
            panX = o.optDouble("panX", 0.0).toFloat()
            panY = o.optDouble("panY", 0.0).toFloat()
            fill = o.optBoolean("fill", false)
            PictureFx.brightness = o.optDouble("b", 100.0).toFloat()
            PictureFx.contrast = o.optDouble("c", 100.0).toFloat()
            PictureFx.saturation = o.optDouble("s", 100.0).toFloat()
            PictureFx.hue = o.optDouble("h", 0.0).toFloat()
            PictureFx.sharpness = o.optDouble("sharp", 0.0).toFloat()
            if (effectsOn && !player.isPlaying) {
                // Paused: redraw the current frame so the change is visible at once.
                main.removeCallbacks(refreshFrame)
                main.postDelayed(refreshFrame, 120)
            }
            // Colour settings that this phone cannot do on the graphics chip fall back to the TextureView.
            val needPaint = effectsBroken && !PictureFx.isNeutral()
            if (needPaint) {
                val p = Paint()
                p.colorFilter = ColorMatrixColorFilter(colourMatrix(PictureFx.brightness, PictureFx.contrast, PictureFx.saturation, PictureFx.hue, 0f))
                textureView.setLayerPaint(p)
            } else textureView.setLayerPaint(null)
            // Zoom and pan are done by the player's picture processing; only phones where that is
            // not available fall back to the TextureView for them.
            applyOutput(effectsBroken && (zoom > 1.001f || needPaint))
            layoutSurface()
            updateFrameEffect()
            if (useTexture) applyTransform() else textureView.setTransform(null)
        }
    }

    /** Sound settings from the equalizer page, sent straight to the C++ engine. */
    @JavascriptInterface
    fun setSound(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val p = FloatArray(56)
        val bands = o.optJSONArray("bands")
        for (i in 0 until 8) p[i] = (bands?.optDouble(i, 0.0) ?: 0.0).toFloat()
        val keys = arrayOf("bass", "clarity", "virtual", "hiss", "click", "hum", "rumble", "tube")
        for (i in keys.indices) p[8 + i] = o.optDouble(keys[i], 0.0).toFloat()
        p[16] = o.optDouble("warmth", 50.0).toFloat()
        p[17] = o.optDouble("dialog", 0.0).toFloat()
        p[18] = o.optDouble("width", 100.0).toFloat()
        p[19] = o.optDouble("bassx", 0.0).toFloat()
        p[20] = o.optDouble("level", 0.0).toFloat()
        p[21] = if (o.optBoolean("limiter", true)) 1f else 0f
        p[22] = if (o.optBoolean("neutral", true)) 1f else 0f
        p[23] = o.optDouble("tapenr", 0.0).toFloat()
        p[24] = o.optDouble("bdec", 0.0).toFloat()
        p[25] = o.optDouble("bbe", 0.0).toFloat()
        p[26] = 0f                                   // wide 3D: removed in 4.9
        p[27] = o.optDouble("hfr", 0.0).toFloat()    // MP3 enhance
        p[28] = o.optDouble("mach3", 0.0).toFloat()  // Mach3 bass
        p[29] = o.optDouble("vmGain", 0.0).toFloat().coerceIn(-15f, 12f)   // equal volume (dB)
        p[30] = o.optDouble("xfeed", 0.0).toFloat()  // headphone crossfeed
        p[31] = o.optDouble("exciter", 0.0).toFloat() // exciter
        p[32] = o.optDouble("preamp", 0.0).toFloat().coerceIn(-20f, 20f)
        val mode = o.optInt("eqMode", 8)
        p[33] = (if (mode == 10 || mode == 20) mode else 8).toFloat()
        p[34] = o.optDouble("bassHz", 100.0).toFloat()
        p[55] = if (o.optBoolean("headroom", false)) 1f else 0f   // auto headroom
        val xb = if (mode == 10) o.optJSONArray("b10") else if (mode == 20) o.optJSONArray("b20") else null
        if (xb != null) for (i in 0 until minOf(20, xb.length())) p[35 + i] = xb.optDouble(i, 0.0).toFloat().coerceIn(-20f, 20f)
        p[15] = 0f; p[16] = 50f                      // tube amplifier: removed in 4.9
        FxNative.setParams(p)
    }

    @JavascriptInterface
    fun analyse(on: Boolean) {
        FxNative.analyse(on)
    }

    /** Limiter meter: deepest cut since the last call (dB, 0 = none) and auto headroom in use (dB). */
    @JavascriptInterface
    fun limiterMeter(): String {
        val r = try { FxNative.meter() } catch (_: Throwable) { floatArrayOf(0f, 0f, -90f) }
        return JSONObject().put("gr", r[0].toDouble()).put("hr", r[1].toDouble()).put("pk", (if (r.size > 2) r[2] else -90f).toDouble()).toString()
    }

    @JavascriptInterface
    fun analysisResult(): String {
        val r = FxNative.analysisResult()
        val acc = org.json.JSONArray()
        for (i in 0 until 8) acc.put(r[5 + i].toDouble())
        val b32 = org.json.JSONArray()
        if (r.size >= 52) for (i in 0 until 32) b32.put(r[13 + i].toDouble())
        return JSONObject()
            .put("frames", r[0].toDouble()).put("rmsAcc", r[1].toDouble())
            .put("humOn", r[2].toDouble()).put("humOff", r[3].toDouble()).put("sub", r[4].toDouble())
            .put("acc", acc).put("b32", b32)
            .put("ll", r[45].toDouble()).put("rr", r[46].toDouble()).put("lr", r[47].toDouble())
            .put("peak", r[48].toDouble()).put("minBlk", r[49].toDouble()).put("maxBlk", r[50].toDouble()).put("pow", r[51].toDouble())
            .toString()
    }

    /** Thumbnails at given times (seconds) for the zoomed timeline; exact frames when zoomed far in. */
    @JavascriptInterface
    fun thumbnailsAt(url: String, job: Int, timesJson: String, w: Int, h: Int, exact: Boolean) {
        val uri = uriFor(url) ?: return
        val times = try { org.json.JSONArray(timesJson) } catch (_: Exception) { return }
        thumbJob = job
        worker.execute {
            val mmr = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") mmr.setDataSource(uri.path) else mmr.setDataSource(activity, uri)
                val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                for (i in 0 until times.length()) {
                    if (thumbJob != job || released) break
                    val tUs = (times.optDouble(i, 0.0) * 1_000_000).toLong()
                    val bmp: Bitmap? = try {
                        if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(tUs, opt, w, h)
                        else mmr.getFrameAtTime(tUs, opt)?.let { Bitmap.createScaledBitmap(it, w, h, true) }
                    } catch (_: Exception) { null }
                    if (bmp == null) continue
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                    val data = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    send("thumb", JSONObject().put("job", job).put("i", i).put("data", data))
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
    }

    /** Light or dark colours for the window behind the page and for the status bar icons. */
    @JavascriptInterface
    fun setTheme(light: Boolean) {
        main.post { activity.applyTheme(light) }
    }

    /** Thumbnails for the cutter strip, made natively and sent one by one. */
    @JavascriptInterface
    fun thumbnails(url: String, job: Int, count: Int, w: Int, h: Int) {
        val uri = uriFor(url) ?: return
        thumbJob = job
        worker.execute {
            val mmr = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") mmr.setDataSource(uri.path) else mmr.setDataSource(activity, uri)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                for (i in 0 until count) {
                    if (thumbJob != job || released) break
                    val tUs = if (count <= 1) 0L else (durMs * 1000L * i / (count - 1)).coerceAtMost(max(0L, durMs * 1000L - 80_000L))
                    val bmp: Bitmap? = try {
                        if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
                        else mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, w, h, true) }
                    } catch (_: Exception) { null }
                    if (bmp == null) continue
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 55, bos)
                    val data = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    send("thumb", JSONObject().put("job", job).put("i", i).put("data", data))
                }
            } catch (_: Exception) {
                send("thumb", JSONObject().put("job", job).put("i", -1).put("data", ""))
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
    }

    /** Screen brightness for this app's window, 0..1 (starts from the phone's own setting). */
    @JavascriptInterface
    fun getBrightness(): Double {
        val own = activity.window.attributes.screenBrightness
        if (own >= 0f) return own.toDouble()
        return try {
            (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255.0).coerceIn(0.0, 1.0)
        } catch (_: Exception) { 0.5 }
    }

    @JavascriptInterface
    fun setBrightness(v: Double) {
        main.post {
            val a = activity.window.attributes
            a.screenBrightness = v.toFloat().coerceIn(0.01f, 1f)
            activity.window.attributes = a
        }
    }

    /** Media volume, the same one the phone's side keys change. */
    @JavascriptInterface
    fun getVolume(): String {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return JSONObject().put("v", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toString()
    }

    @JavascriptInterface
    fun setVolume(index: Int) {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max), 0) } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun pickMedia() {
        main.post { activity.pickMedia() }
    }

    /** Lets the user choose a folder; its audio and video files are added to the page's list. */
    @JavascriptInterface
    fun pickFolder() {
        main.post { activity.pickFolder() }
    }

    /** Opens a file from the list by its saved address (content:// or file://). */
    @JavascriptInterface
    fun openUri(uri: String) {
        main.post { activity.openUriFromList(uri) }
    }

    fun release() {
        released = true
        thumbJob = -1
        main.removeCallbacksAndMessages(null)
        try { player.release() } catch (_: Exception) {}
        worker.shutdown()
    }
}
