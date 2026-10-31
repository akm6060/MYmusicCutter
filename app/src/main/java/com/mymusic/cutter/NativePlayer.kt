package com.mymusic.cutter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
    private val textureView: TextureView
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
            if (isPlaying) {
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
            videoW = videoSize.width
            videoH = videoSize.height
            @Suppress("DEPRECATION")
            rotation = videoSize.unappliedRotationDegrees
            par = if (videoSize.pixelWidthHeightRatio > 0) videoSize.pixelWidthHeightRatio else 1f
            applyTransform()
            val (w, h) = displaySize()
            if (w > 0) send("size", JSONObject().put("w", w).put("h", h))
            if (metaSent) sendMeta()
        }

        override fun onPlayerError(error: PlaybackException) {
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
        player = ExoPlayer.Builder(activity, renderers).build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        player.setHandleAudioBecomingNoisy(true)
        player.setVideoTextureView(textureView)
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
        m.postScale(zoom, zoom, vw / 2, vh / 2)
        m.postTranslate(panX * density, panY * density)
        textureView.setTransform(m)
        textureView.invalidate()
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
        return if (u.scheme == "content" || u.scheme == "file") u else null
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
            videoW = 0; videoH = 0; rotation = 0; par = 1f
            player.setSeekParameters(SeekParameters.EXACT)
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
        main.post { textureView.alpha = if (on) 1f else 0f }
    }

    @JavascriptInterface
    fun setPicture(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        main.post {
            zoom = o.optDouble("z", 1.0).toFloat().coerceIn(1f, 5f)
            panX = o.optDouble("panX", 0.0).toFloat()
            panY = o.optDouble("panY", 0.0).toFloat()
            fill = o.optBoolean("fill", false)
            val b = o.optDouble("b", 100.0).toFloat()
            val c = o.optDouble("c", 100.0).toFloat()
            val s = o.optDouble("s", 100.0).toFloat()
            val h = o.optDouble("h", 0.0).toFloat()
            val sep = o.optDouble("sepia", 0.0).toFloat()
            if (b == 100f && c == 100f && s == 100f && h == 0f && sep == 0f) {
                textureView.setLayerPaint(null)
            } else {
                val p = Paint()
                p.colorFilter = ColorMatrixColorFilter(colourMatrix(b, c, s, h, sep))
                textureView.setLayerPaint(p)
            }
            applyTransform()
        }
    }

    /** Sound settings from the equalizer page, sent straight to the C++ engine. */
    @JavascriptInterface
    fun setSound(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val p = FloatArray(24)
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
        FxNative.setParams(p)
    }

    @JavascriptInterface
    fun analyse(on: Boolean) {
        FxNative.analyse(on)
    }

    @JavascriptInterface
    fun analysisResult(): String {
        val r = FxNative.analysisResult()
        val acc = org.json.JSONArray()
        for (i in 0 until 8) acc.put(r[5 + i].toDouble())
        return JSONObject()
            .put("frames", r[0].toDouble()).put("rmsAcc", r[1].toDouble())
            .put("humOn", r[2].toDouble()).put("humOff", r[3].toDouble()).put("sub", r[4].toDouble())
            .put("acc", acc).toString()
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

    /** Snapshot of the current frame at half size, JPEG quality 50. */
    @JavascriptInterface
    fun snapshot(name: String): Boolean {
        main.post {
            val (dw, dh) = displaySize()
            if (dw <= 0 || dh <= 0) { cutter.pageMessage("Play the video first, then take the snapshot."); return@post }
            val r90 = rotation == 90 || rotation == 270
            val bw = max(1, (if (r90) videoH else (videoW * par).roundToInt()) / 2)
            val bh = max(1, (if (r90) (videoW * par).roundToInt() else videoH) / 2)
            var bmp = textureView.getBitmap(bw, bh)
            if (bmp == null) { cutter.pageMessage("Snapshot could not be taken."); return@post }
            if (rotation != 0) {
                val m = Matrix(); m.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val shot = bmp
            worker.execute {
                val bos = ByteArrayOutputStream()
                shot.compress(Bitmap.CompressFormat.JPEG, 50, bos)
                cutter.saveSnapshotBytes(bos.toByteArray(), name)
            }
        }
        return true
    }

    @JavascriptInterface
    fun pickMedia() {
        main.post { activity.pickMedia() }
    }

    fun release() {
        released = true
        thumbJob = -1
        main.removeCallbacksAndMessages(null)
        try { player.release() } catch (_: Exception) {}
        worker.shutdown()
    }
}
