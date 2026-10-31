package com.mymusic.cutter

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JavaScript bridge registered as window.MYmusicNativeCutter.
 *
 * Cutting is lossless (no re-encoding):
 *  - MP3: whole MPEG audio frames are copied, output .mp3
 *  - WAV: PCM bytes are copied, output .wav
 *  - MP4 / M4A / 3GP / AAC (H.264, H.265, AAC...): MediaExtractor + MediaMuxer, output .mp4 / .m4a
 *  - WebM (VP8/VP9 with Vorbis/Opus): MediaExtractor + MediaMuxer, output .webm
 * Video cuts start at the keyframe at or before the chosen start, so the selection is never shortened.
 */
class MYmusicNativeCutter(
    private val activity: Activity,
    private val webView: WebView
) {
    private val context: Context = activity.applicationContext
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private class Picked(val uri: Uri, val name: String, val size: Long)
    private val picked = ArrayList<Picked>()

    private class Source(val uri: Uri?, val file: File?)

    // Fallback transfer (only used when the picked file cannot be matched).
    private var meta: JSONObject? = null
    private var inputFile: File? = null
    private var inputStream: FileOutputStream? = null

    fun shutdown() {
        worker.shutdown()
        shared.values.forEach { it.close() }
    }

    // ---------------------------------------------------------------- orientation / fullscreen

    @JavascriptInterface
    fun allowAutoRotation(): Boolean {
        activity.runOnUiThread {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        return true
    }

    @JavascriptInterface
    fun lockLandscape(): Boolean {
        activity.runOnUiThread {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            (activity as? MainActivity)?.setFullscreenBars(true)
        }
        return true
    }

    @JavascriptInterface
    fun unlockOrientation(): Boolean {
        activity.runOnUiThread {
            (activity as? MainActivity)?.setFullscreenBars(false)
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        return true
    }

    /** Rectangles in CSS pixels: [[left, top, right, bottom], ...]. */
    @JavascriptInterface
    fun setGestureExclusion(rectsJson: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val density = context.resources.displayMetrics.density
        val rects = ArrayList<Rect>()
        try {
            val arr = JSONArray(rectsJson)
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                rects.add(
                    Rect(
                        (r.optDouble(0) * density).toInt(), (r.optDouble(1) * density).toInt(),
                        (r.optDouble(2) * density).toInt(), (r.optDouble(3) * density).toInt()
                    )
                )
            }
        } catch (_: Exception) {
            return false
        }
        activity.runOnUiThread { (activity as? MainActivity)?.setGestureExclusion(rects) }
        return true
    }

    // ---------------------------------------------------------------- picked files

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = -1L
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return Pair(f.name.ifBlank { name }, if (f.isFile) f.length() else -1L)
        }
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (_: Exception) {
        }
        return Pair(name, size)
    }

    /** Called by MainActivity with the files the user just chose or shared to the app. */
    fun rememberPicked(uris: Array<Uri>) {
        val found = uris.map { uri ->
            val (name, size) = nameAndSize(uri)
            Picked(uri, name, size)
        }
        synchronized(lock) {
            picked.addAll(found)
            while (picked.size > 200) picked.removeAt(0)
        }
    }

    private fun rememberPickedAs(uri: Uri, name: String, size: Long) {
        synchronized(lock) {
            picked.add(Picked(uri, name, size))
            while (picked.size > 200) picked.removeAt(0)
        }
    }

    // ---------------------------------------------------------------- "Open with" / "Share" streaming
    //
    // Files from other apps are played straight from where they are, with real seeking (HTTP Range).
    // Some apps hand over a one-way stream instead of a file: Solid Explorer's encrypted (.sec) folders,
    // cloud apps, some messengers. Those cannot seek, so they are first copied once into this app's
    // private cache (with a progress display) and played from there. The original is never changed.

    private class SharedItem(val uri: Uri, val name: String, val mime: String, val file: File?) {
        // Opened once and read by position for every request; reopening the file on each seek
        // made protected / slow sources freeze.
        var pfd: ParcelFileDescriptor? = null
        var fis: FileInputStream? = null
        var channel: FileChannel? = null
        var size: Long = -1L
        fun close() {
            try { channel?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            channel = null; fis = null; pfd = null
        }
    }
    private val shared = ConcurrentHashMap<String, SharedItem>()
    private val sharedDir: File by lazy { File(context.cacheDir, "shared").apply { mkdirs() } }

    /** Removes copies left from earlier sessions (called at start-up). */
    fun clearSharedCache(keep: Set<File> = emptySet()) {
        if (keep.isEmpty()) { shared.values.forEach { it.close() } }
        try {
            sharedDir.listFiles()?.forEach { if (it !in keep) it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun mimeFor(uri: Uri, name: String): String {
        val given = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        if (given != null && given != "application/octet-stream" && given.isNotBlank()) return given
        val ext = name.substringAfterLast('.', "").lowercase()
        val guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return guess ?: when (ext) {
            "mkv" -> "video/x-matroska"; "mka" -> "audio/x-matroska"; "opus" -> "audio/ogg"
            "flac" -> "audio/flac"; "m4a", "m4b" -> "audio/mp4"; "ts", "m2ts", "mts" -> "video/mp2t"
            else -> given ?: ""
        }
    }

    /**
     * Files that must be copied once before playing: one-way streams, Solid Explorer's encrypted
     * (.sec) folders and any source too slow for smooth seeking (decrypting on the fly).
     */
    private fun needsLocalCopy(uri: Uri): Boolean {
        val text = Uri.decode(uri.toString()).lowercase()
        if (text.contains(".sec/") || text.endsWith(".sec")) return true
        if (!isSeekable(uri)) return true
        if (uri.scheme == "file") return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val buf = ByteBuffer.allocate(1 shl 20)
                val t0 = SystemClock.uptimeMillis()
                var pos = maxOf(0L, size / 2 - (1L shl 20))
                var got = 0
                while (got < (2 shl 20)) {
                    buf.clear()
                    val n = ch.read(buf, pos)
                    if (n <= 0) break
                    got += n; pos += n
                }
                val ms = SystemClock.uptimeMillis() - t0
                ms > 150 // under ~13 MB/s (decrypting on the fly): too slow to seek smoothly
            } ?: true
        } catch (_: Exception) {
            true
        }
    }

    /** True when the file can be read at any position (a normal file); false for one-way streams. */
    private fun isSeekable(uri: Uri): Boolean {
        return try {
            val pfd = if (uri.scheme == "file") ParcelFileDescriptor.open(File(uri.path ?: ""), ParcelFileDescriptor.MODE_READ_ONLY)
                      else context.contentResolver.openFileDescriptor(uri, "r")
            pfd?.use {
                if (it.statSize <= 0) return false
                Os.lseek(it.fileDescriptor, 1L, OsConstants.SEEK_SET)
                Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_SET)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun copyToCache(uri: Uri, name: String, knownSize: Long, onProgress: (Int) -> Unit): File {
        val out = File(sharedDir, UUID.randomUUID().toString().take(8) + "_" + safeName(name))
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("The other app did not hand over the file.")
        try {
            input.use { inp ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(1 shl 18)
                    var done = 0L
                    var lastPct = -1
                    var lastAt = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        done += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastAt > 300) {
                            lastAt = now
                            // 0..99 = percent; below zero = megabytes copied so far (size unknown)
                            val pct = if (knownSize > 0) (done * 100 / knownSize).toInt().coerceIn(0, 99) else -((done shr 20).toInt()) - 1
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw IllegalStateException(
                if ((e.message ?: "").contains("ENOSPC") || (e.message ?: "").contains("No space"))
                    "Not enough free space to prepare this protected file."
                else "The file could not be read: " + (e.message ?: e.javaClass.simpleName)
            )
        }
        return out
    }

    /**
     * Prepares files from "Open with"/"Share" and returns the JSON list the page plays.
     * Runs on a background thread; onProgress(name, percent) reports copying of protected files.
     */
    fun registerShared(uris: Array<Uri>, onProgress: (String, Int) -> Unit = { _, _ -> }): String {
        val arr = JSONArray()
        val keep = HashSet<File>()
        shared.values.forEach { it.file?.let { f -> keep.add(f) } }
        val fresh = ArrayList<File>()
        for (uri in uris) {
            val (name, size) = nameAndSize(uri)
            val mime = mimeFor(uri, name)
            var file: File? = null
            if (needsLocalCopy(uri)) {
                try {
                    file = copyToCache(uri, name, size) { pct -> onProgress(name, pct) }
                    fresh.add(file)
                } catch (e: Exception) {
                    notifyOpenFailed(name, e.message ?: "")
                    continue
                }
            }
            val id = UUID.randomUUID().toString()
            shared[id] = SharedItem(uri, name, mime, file)
            val realSize = file?.length() ?: size
            if (file != null) rememberPickedAs(Uri.fromFile(file), name, realSize)
            else rememberPickedAs(uri, name, realSize)
            arr.put(
                JSONObject()
                    .put("url", "https://appassets.androidplatform.net/mymusic-shared/$id")
                    .put("name", name)
                    .put("size", realSize)
                    .put("type", mime)
            )
        }
        // Copies from the previous "Open with" are no longer needed.
        if (fresh.isNotEmpty() || uris.isNotEmpty()) {
            val stale = keep.filter { k -> fresh.none { it == k } }
            shared.values.filter { v -> v.file?.let { f -> stale.contains(f) } == true }.forEach { it.close() }
            shared.entries.removeIf { e -> e.value.file?.let { f -> stale.contains(f) } == true }
            stale.forEach { it.delete() }
        }
        return arr.toString()
    }

    private fun notifyOpenFailed(name: String, why: String) {
        val n = JSONObject.quote(name)
        val w = JSONObject.quote(why)
        webView.post { webView.evaluateJavascript("window.__mymusicOpenFailed&&window.__mymusicOpenFailed($n,$w)", null) }
    }

    /** Serves a shared file to the page, honouring HTTP Range so the player can seek. */
    fun sharedResponse(id: String, range: String?): WebResourceResponse? {
        val item = shared[id] ?: return null
        val headers = HashMap<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Cache-Control"] = "no-store"
        headers["Accept-Ranges"] = "bytes"
        val mime = item.mime.ifBlank { "application/octet-stream" }
        return try {
            val opened = synchronized(item) {
                if (item.channel == null) {
                    val pfd = when {
                        item.file != null -> ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
                        item.uri.scheme == "file" -> ParcelFileDescriptor.open(File(item.uri.path ?: ""), ParcelFileDescriptor.MODE_READ_ONLY)
                        else -> context.contentResolver.openFileDescriptor(item.uri, "r")
                    }
                    if (pfd != null) {
                        item.pfd = pfd
                        item.size = pfd.statSize
                        val fis = FileInputStream(pfd.fileDescriptor)
                        item.fis = fis
                        item.channel = fis.channel
                    }
                }
                val ch = item.channel
                if (ch == null) null else Pair(ch, item.size)
            } ?: return null
            val ch = opened.first
            val total = opened.second
            if (total <= 0) {
                // Length unknown: send it as one stream (playback works, seeking may not).
                val pfd2 = context.contentResolver.openFileDescriptor(item.uri, "r") ?: return null
                return WebResourceResponse(mime, null, 200, "OK", headers, ParcelFileDescriptor.AutoCloseInputStream(pfd2))
            }
            var start = 0L
            var end = total - 1
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it.trim()) }
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) {
                    start = maxOf(0L, total - b.toLong())
                } else {
                    if (a.isNotEmpty()) start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(total - 1, b.toLong())
                }
            }
            if (start >= total || start > end) {
                headers["Content-Range"] = "bytes */$total"
                return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, java.io.ByteArrayInputStream(ByteArray(0)))
            }
            val length = end - start + 1
            headers["Content-Length"] = length.toString()
            val body = ChannelRangeStream(ch, start, length)
            if (m != null) {
                headers["Content-Range"] = "bytes $start-$end/$total"
                WebResourceResponse(mime, null, 206, "Partial Content", headers, body)
            } else {
                WebResourceResponse(mime, null, 200, "OK", headers, body)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Reads one byte range by position from a shared, already-open file (safe for parallel requests). */
    private class ChannelRangeStream(private val ch: FileChannel, private var pos: Long, private var left: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val want = minOf(len.toLong(), left).toInt()
            if (want <= 0) return 0
            val n = ch.read(ByteBuffer.wrap(b, off, want), pos)
            if (n <= 0) return -1
            pos += n
            left -= n
            return n
        }
        override fun available(): Int = minOf(left, 1L shl 16).toInt()
    }

    private class LimitedInputStream(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val v = inner.read()
            if (v >= 0) left--
            return v
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun available(): Int = minOf(inner.available().toLong(), left).toInt()
        override fun close() = inner.close()
    }

    // ---------------------------------------------------------------- default player

    private fun handlerFor(kind: String): Pair<String, Pair<String, String>> {
        val pm = context.packageManager
        val mime = if (kind == "audio") "audio/mpeg" else "video/mp4"
        val sample = if (kind == "audio") "probe.mp3" else "probe.mp4"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.mymusic.cutter.probe/$sample"), mime)
        val ri = try { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) } catch (_: Exception) { null }
            ?: return Pair("none", Pair("", ""))
        val ai = ri.activityInfo ?: return Pair("none", Pair("", ""))
        val pkg = ai.packageName ?: ""
        if (pkg == context.packageName) return Pair("self", Pair("", pkg))
        if (pkg == "android" || pkg.contains("intentresolver") || ai.name.contains("Resolver") || ai.name.contains("Chooser"))
            return Pair("none", Pair("", ""))
        val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
        return Pair("other", Pair(label, pkg))
    }

    /** Which app opens videos and music without asking: self, other (with its name) or none. */
    @JavascriptInterface
    fun defaultPlayerStatus(): String {
        val v = handlerFor("video")
        val a = handlerFor("audio")
        return JSONObject()
            .put("video", v.first).put("videoName", v.second.first)
            .put("audio", a.first).put("audioName", a.second.first)
            .toString()
    }

    /** Opens the settings page of the app that currently opens these files, so its default can be cleared. */
    @JavascriptInterface
    fun openDefaultAppSettings(kind: String): Boolean {
        val pkg = handlerFor(if (kind == "audio") "audio" else "video").second.second
        activity.runOnUiThread {
            try {
                val i = if (pkg.isNotBlank() && pkg != context.packageName)
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                else Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    private fun findPicked(name: String, size: Long): Uri? = synchronized(lock) {
        picked.lastOrNull { it.name == name && (size <= 0 || it.size <= 0 || it.size == size) }?.uri
    }

    /** Fast path: cut the file Android already gave us, without copying it through JavaScript. */
    @JavascriptInterface
    fun cutPickedFile(metaJson: String): Boolean {
        return try {
            val parsed = JSONObject(metaJson)
            val uri = findPicked(parsed.optString("fileName", ""), parsed.optLong("fileSizeBytes", -1L))
                ?: return false
            startJob(parsed, Source(uri, null))
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- fallback chunk transfer

    @JavascriptInterface
    fun beginTransfer(metaJson: String): Boolean = synchronized(lock) {
        try {
            inputStream?.close()
            inputFile?.delete()
            val parsed = JSONObject(metaJson)
            val name = safeName(parsed.optString("fileName", "media"))
            val temp = File(context.cacheDir, "mymusic_${UUID.randomUUID()}_$name")
            meta = parsed
            inputFile = temp
            inputStream = FileOutputStream(temp)
            true
        } catch (_: Exception) {
            meta = null
            inputFile = null
            inputStream = null
            false
        }
    }

    @JavascriptInterface
    fun appendChunk(base64Chunk: String): Boolean = synchronized(lock) {
        try {
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            val out = inputStream ?: return false
            out.write(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun finishTransfer(): Boolean {
        val transfer: Pair<JSONObject, File> = synchronized(lock) {
            val stream = inputStream
            inputStream = null
            try {
                stream?.flush()
                stream?.close()
            } catch (_: Exception) {
                return false
            }
            val m = meta ?: return false
            val f = inputFile ?: return false
            meta = null
            inputFile = null
            Pair(m, f)
        }
        val jobMeta = transfer.first
        val jobFile = transfer.second
        val started = startJob(jobMeta, Source(null, jobFile))
        if (!started) jobFile.delete()
        return started
    }

    // ---------------------------------------------------------------- snapshot

    @JavascriptInterface
    fun saveSnapshot(base64Jpeg: String, fileName: String): Boolean {
        val bytes = try {
            Base64.decode(base64Jpeg, Base64.DEFAULT)
        } catch (_: Exception) {
            return false
        }
        worker.execute {
            var target: OutputTarget? = null
            try {
                val t = createOutput(Kind.IMAGE, fileName, ".jpg", "image/jpeg")
                target = t
                writeAll(FileOutputStream(t.pfd.fileDescriptor).channel, bytes)
                val saved = finishOutput(t)
                target = null
                notifySaved(saved, "image")
            } catch (e: Exception) {
                target?.let { abortOutput(it) }
                notifyPage("Snapshot failed: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return true
    }

    /** Opens a saved picture in the gallery (or another viewer). */
    @JavascriptInterface
    fun viewSaved(id: String): Boolean {
        val target = viewable[id] ?: return false
        activity.runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(target.first, target.second)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(intent)
            } catch (_: Exception) {
                notifyPage("No app was found to open this picture.")
            }
        }
        return true
    }

    // ---------------------------------------------------------------- job

    private fun startJob(jobMeta: JSONObject, source: Source): Boolean {
        if (!busy.compareAndSet(false, true)) {
            notifyPage("Please wait: the previous cut is still saving.")
            return true
        }
        worker.execute {
            var target: OutputTarget? = null
            try {
                val isAudio = jobMeta.optString("mediaType", "video") == "audio"
                val mode = jobMeta.optString("mode", "keep")
                val startUs = (jobMeta.optDouble("startSeconds", 0.0) * 1_000_000).toLong()
                val endUs = (jobMeta.optDouble("endSeconds", 0.0) * 1_000_000).toLong()
                val durUs = (jobMeta.optDouble("durationSeconds", 0.0) * 1_000_000).toLong()
                val name = jobMeta.optString("fileName", "media")
                require(startUs >= 0 && endUs > startUs) { "The selected cutting range is invalid." }

                val ranges: List<LongArray> = if (mode == "remove-middle") {
                    listOf(longArrayOf(0L, startUs), longArrayOf(endUs, Long.MAX_VALUE)).filter { it[1] > it[0] }
                } else {
                    listOf(longArrayOf(startUs, endUs))
                }
                var totalUs = 0L
                for (r in ranges) totalUs += (if (r[1] == Long.MAX_VALUE) maxOf(durUs, r[0]) else r[1]) - r[0]
                lastProgressAt = 0L
                progressSent = -1

                val format = detectFormat(source, name)
                val saved: Saved = when (format) {
                    "mp3" -> {
                        val t = createOutput(Kind.AUDIO, name, ".mp3", "audio/mpeg")
                        target = t
                        cutMp3(source, t, ranges, sourceSize(source))
                        finishOutput(t)
                    }
                    "wav" -> {
                        val t = createOutput(Kind.AUDIO, name, ".wav", "audio/wav")
                        target = t
                        cutWav(source, t, ranges, sourceSize(source))
                        finishOutput(t)
                    }
                    else -> {
                        val plan = probeContainer(source, isAudio)
                        val ext = if (plan.webm) ".webm" else if (isAudio) ".m4a" else ".mp4"
                        val mime = when {
                            plan.webm && isAudio -> "audio/webm"
                            plan.webm -> "video/webm"
                            isAudio -> "audio/mp4"
                            else -> "video/mp4"
                        }
                        val t = createOutput(if (isAudio) Kind.AUDIO else Kind.VIDEO, name, ext, mime)
                        target = t
                        muxCut(source, t, plan, ranges, maxOf(totalUs, 1L))
                        finishOutput(t)
                    }
                }
                target = null
                notifySaved(saved, if (isAudio || format == "mp3" || format == "wav") "audio" else "video")
            } catch (e: Exception) {
                target?.let { abortOutput(it) }
                notifyPage("Cut failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                source.file?.delete()
                busy.set(false)
            }
        }
        return true
    }

    private fun sourceSize(source: Source): Long =
        source.file?.length() ?: source.uri?.let { nameAndSize(it).second } ?: -1L

    private var lastProgressAt = 0L
    private var progressSent = -1

    /** Sends "Saving NN%" to the page, at most about four times a second. */
    private fun progress(fraction: Double) {
        val pct = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
        val now = SystemClock.uptimeMillis()
        if (pct == progressSent || now - lastProgressAt < 250) return
        lastProgressAt = now
        progressSent = pct
        webView.post {
            webView.evaluateJavascript("window.onNativeCutterProgress&&window.onNativeCutterProgress($pct)", null)
        }
    }

    /** Tells the page what was saved, so it can offer "Play cut" or "View". */
    private fun notifySaved(s: Saved, kind: String) {
        val id = UUID.randomUUID().toString()
        shared[id] = SharedItem(s.playUri, s.name, s.mime, null)
        s.viewUri?.let { viewable[id] = Pair(it, s.mime) }
        if (kind != "image") rememberPicked(arrayOf(s.playUri))
        val json = JSONObject()
            .put("id", id).put("kind", kind).put("name", s.name).put("mime", s.mime)
            .put("url", "https://appassets.androidplatform.net/mymusic-shared/$id")
            .toString()
        webView.post {
            webView.evaluateJavascript(
                "if(window.onNativeCutterSaved){window.onNativeCutterSaved($json);}" +
                    "else if(window.onNativeCutterResult){window.onNativeCutterResult('Saved');}", null
            )
        }
    }

    private fun openInput(source: Source): InputStream =
        source.file?.inputStream()
            ?: context.contentResolver.openInputStream(source.uri!!)
            ?: throw IllegalStateException("Android could not open the selected file.")

    private fun newExtractor(source: Source): MediaExtractor {
        val ex = MediaExtractor()
        try {
            if (source.file != null) ex.setDataSource(source.file.absolutePath)
            else ex.setDataSource(context, source.uri!!, null)
        } catch (e: Exception) {
            ex.release()
            throw IllegalStateException("Android could not read this media file.")
        }
        return ex
    }

    private fun detectFormat(source: Source, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "mp3") return "mp3"
        if (ext == "wav" || ext == "wave") return "wav"
        if (ext in setOf("mp4", "m4a", "m4v", "m4b", "mov", "3gp", "aac", "webm", "mkv")) return "container"
        val head = ByteArray(12)
        val n = try {
            openInput(source).use { readFully(it, head, 0, 12) }
        } catch (_: Exception) {
            0
        }
        if (n >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") return "wav"
        if (n >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") return "mp3"
        if (n >= 4 && Mp3Header.parse(head, 0) != null) return "mp3"
        return "container"
    }

    // ---------------------------------------------------------------- MP4 / M4A / WebM

    private class ContainerPlan(
        val formats: LinkedHashMap<Int, MediaFormat>, val rotation: Int, val maxInput: Int, val webm: Boolean
    )

    /** Checks the tracks before anything is created, so unsupported files fail without leaving a file. */
    private fun probeContainer(source: Source, audioOnly: Boolean): ContainerPlan {
        val probe = newExtractor(source)
        val trackFormats = LinkedHashMap<Int, MediaFormat>()
        var rotation = 0
        var maxInput = 1024 * 1024
        try {
            for (i in 0 until probe.trackCount) {
                val format = probe.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val include = if (audioOnly) mime.startsWith("audio/")
                              else mime.startsWith("audio/") || mime.startsWith("video/")
                if (!include) continue
                trackFormats[i] = format
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
        } finally {
            probe.release()
        }
        require(trackFormats.isNotEmpty()) { "No audio or video track was found in this file." }

        val mimes = trackFormats.values.map { it.getString(MediaFormat.KEY_MIME) ?: "" }
        val webmMimes = setOf(
            MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_AUDIO_VORBIS, MediaFormat.MIMETYPE_AUDIO_OPUS
        )
        val webm = mimes.all { it in webmMimes }
        val mp4Ok = setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_MPEG4,
            MediaFormat.MIMETYPE_VIDEO_H263, "video/av01",
            MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB
        )
        if (!webm) {
            val bad = mimes.filter { it !in mp4Ok }
            require(bad.isEmpty()) {
                "This file uses " + bad.joinToString { it.substringAfter('/') } +
                    ", which Android cannot save without re-encoding. MP3, WAV, AAC/M4A, MP4 (H.264/H.265) and WebM work."
            }
        }
        return ContainerPlan(trackFormats, rotation, maxInput, webm)
    }

    /** Copies the selected parts straight into the final output file. */
    private fun muxCut(source: Source, t: OutputTarget, plan: ContainerPlan, ranges: List<LongArray>, totalUs: Long) {
        val muxer = MediaMuxer(
            t.pfd.fileDescriptor,
            if (plan.webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        var started = false
        var wroteAny = false
        try {
            val trackMap = HashMap<Int, Int>()
            for ((index, format) in plan.formats) trackMap[index] = muxer.addTrack(format)
            if (!plan.webm && plan.rotation != 0) muxer.setOrientationHint(plan.rotation)
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(plan.maxInput.coerceAtMost(32 * 1024 * 1024))
            var offsetUs = 0L
            var doneUs = 0L
            for (range in ranges) {
                val lastUs = writeSegment(source, muxer, trackMap, buffer, range[0], range[1], offsetUs, doneUs, totalUs)
                if (lastUs >= 0) {
                    wroteAny = true
                    doneUs += lastUs - offsetUs
                    offsetUs = lastUs + 20_000L // small gap keeps timestamps increasing between parts
                }
            }
        } finally {
            if (started) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
        require(wroteAny) { "The selected range contained no media to save." }
    }

    /** Copies samples from the keyframe at or before fromUs up to toUs. Returns the last output time, or -1. */
    private fun writeSegment(
        source: Source, muxer: MediaMuxer, trackMap: Map<Int, Int>, buffer: ByteBuffer,
        fromUs: Long, toUs: Long, outputOffsetUs: Long, doneUs: Long, totalUs: Long
    ): Long {
        val ex = newExtractor(source)
        var lastOut = -1L
        try {
            trackMap.keys.forEach { ex.selectTrack(it) }
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var baseUs = -1L
            while (true) {
                val track = ex.sampleTrackIndex
                val timeUs = ex.sampleTime
                if (track < 0 || timeUs < 0) break
                if (toUs != Long.MAX_VALUE && timeUs >= toUs) break
                val out = trackMap[track]
                if (out != null) {
                    if (baseUs < 0) baseUs = timeUs
                    if (timeUs >= baseUs) { // skips leading frames that belong to the previous group
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size > 0) {
                            val pts = outputOffsetUs + (timeUs - baseUs)
                            info.set(0, size, pts, ex.sampleFlags)
                            muxer.writeSampleData(out, buffer, info)
                            if (pts > lastOut) lastOut = pts
                            progress((doneUs + (timeUs - baseUs)).toDouble() / totalUs)
                        }
                    }
                }
                if (!ex.advance()) break
            }
        } finally {
            ex.release()
        }
        return lastOut
    }

    // ---------------------------------------------------------------- MP3

    private class Mp3Header(
        val version: Int, val layer: Int, val sampleRate: Int,
        val frameLength: Int, val samples: Int, val mono: Boolean
    ) {
        companion object {
            private val BR_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            private val BR_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            private val BR_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            private val BR_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            private val BR_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

            /** version: 1 = MPEG-1, 2 = MPEG-2, 25 = MPEG-2.5. layer: 1, 2 or 3. */
            fun parse(b: ByteArray, off: Int): Mp3Header? {
                val b0 = b[off].toInt() and 0xFF
                val b1 = b[off + 1].toInt() and 0xFF
                val b2 = b[off + 2].toInt() and 0xFF
                val b3 = b[off + 3].toInt() and 0xFF
                if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
                val version = when ((b1 shr 3) and 3) { 0 -> 25; 2 -> 2; 3 -> 1; else -> return null }
                val layer = when ((b1 shr 1) and 3) { 1 -> 3; 2 -> 2; 3 -> 1; else -> return null }
                val brIdx = (b2 shr 4) and 0xF
                if (brIdx == 0 || brIdx == 15) return null
                val srIdx = (b2 shr 2) and 3
                if (srIdx == 3) return null
                val padding = (b2 shr 1) and 1
                val mono = ((b3 shr 6) and 3) == 3
                val sampleRate = when (version) {
                    1 -> intArrayOf(44100, 48000, 32000)[srIdx]
                    2 -> intArrayOf(22050, 24000, 16000)[srIdx]
                    else -> intArrayOf(11025, 12000, 8000)[srIdx]
                }
                val kbps = if (version == 1) {
                    when (layer) {
                        1 -> BR_V1_L1[brIdx]
                        2 -> BR_V1_L2[brIdx]
                        else -> BR_V1_L3[brIdx]
                    }
                } else {
                    if (layer == 1) BR_V2_L1[brIdx] else BR_V2_L23[brIdx]
                }
                val bps = kbps * 1000
                val frameLength = when {
                    layer == 1 -> (12 * bps / sampleRate + padding) * 4
                    layer == 3 && version != 1 -> 72 * bps / sampleRate + padding
                    else -> 144 * bps / sampleRate + padding
                }
                val samples = when {
                    layer == 1 -> 384
                    layer == 3 && version != 1 -> 576
                    else -> 1152
                }
                if (frameLength < 21) return null
                return Mp3Header(version, layer, sampleRate, frameLength, samples, mono)
            }
        }
    }

    private fun cutMp3(source: Source, t: OutputTarget, ranges: List<LongArray>, totalBytes: Long) {
        var frames = 0
        var kept = 0
        var written = 0L
        var xingFrame: ByteArray? = null
        var xingTagOffset = 0
        var xingPos = -1L
        var audioBytes = 0L
        var offsets = LongArray(4096) // byte offset of each kept frame, for the seek table
        var consumed = 0L
        openInput(source).use { raw ->
            val input = BufferedInputStream(raw, 1 shl 16)
            FileOutputStream(t.pfd.fileDescriptor).let { fileOut ->
                val out = fileOut.buffered(1 shl 16)
                // ID3v2 tag (title, artist, cover) is copied once at the start.
                val head = ByteArray(10)
                input.mark(16)
                val n = readFully(input, head, 0, 10)
                if (n == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                    val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                        ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
                    val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
                    out.write(head)
                    written += 10
                    written += copyBytes(input, out, (size + footer).toLong())
                    consumed = written
                } else {
                    input.reset()
                }

                val hdr = ByteArray(4)
                val frame = ByteArray(8192)
                var timeUs = 0.0
                var lockVersion = -1
                var lockLayer = -1
                var lockRate = -1
                var junk = 0L
                while (true) {
                    input.mark(8)
                    if (readFully(input, hdr, 0, 4) < 4) break
                    val h = Mp3Header.parse(hdr, 0)
                    val matches = h != null && (lockVersion < 0 ||
                        (h.version == lockVersion && h.layer == lockLayer && h.sampleRate == lockRate))
                    if (h == null || !matches) {
                        input.reset()
                        if (input.skip(1) < 1) break
                        junk++
                        consumed++
                        if (frames > 0 && junk > 1_000_000) break // trailing tags/garbage
                        continue
                    }
                    junk = 0
                    System.arraycopy(hdr, 0, frame, 0, 4)
                    if (readFully(input, frame, 4, h.frameLength - 4) < h.frameLength - 4) break
                    consumed += h.frameLength
                    if (totalBytes > 0) progress(consumed.toDouble() / totalBytes)
                    if (lockVersion < 0) {
                        lockVersion = h.version
                        lockLayer = h.layer
                        lockRate = h.sampleRate
                        // The Xing/Info header frame holds length and seek data; keep it and rewrite it below.
                        val side = if (h.version == 1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
                        val tag = if (4 + side + 8 <= h.frameLength) String(frame, 4 + side, 4, Charsets.ISO_8859_1) else ""
                        if (tag == "Xing" || tag == "Info") {
                            xingFrame = frame.copyOf(h.frameLength)
                            xingTagOffset = 4 + side
                            xingPos = written
                            out.write(frame, 0, h.frameLength)
                            written += h.frameLength
                            continue
                        }
                        if (h.frameLength >= 40 && String(frame, 36, 4, Charsets.ISO_8859_1) == "VBRI") continue
                    }
                    frames++
                    val durUs = h.samples * 1_000_000.0 / h.sampleRate
                    val start = timeUs
                    val end = timeUs + durUs
                    timeUs = end
                    // Keep any frame that overlaps a kept range, so kept audio is never trimmed.
                    val keep = ranges.any { r -> end > r[0] && (r[1] == Long.MAX_VALUE || start < r[1]) }
                    if (keep) {
                        if (kept == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                        offsets[kept] = audioBytes
                        out.write(frame, 0, h.frameLength)
                        written += h.frameLength
                        audioBytes += h.frameLength
                        kept++
                    }
                }
                out.flush()
            }
        }
        require(frames > 0) { "No MP3 audio frames were found in this file." }
        require(kept > 0) { "The selected range contained no audio to save." }

        val xf = xingFrame
        if (xf != null && xingPos >= 0) {
            patchXing(xf, xingTagOffset, kept, audioBytes, offsets)
            val ch = FileOutputStream(t.pfd.fileDescriptor).channel
            ch.position(xingPos)
            writeAll(ch, xf)
        }
    }

    /** Updates frame count, byte count, seek table and LAME CRC so players show the new length. */
    private fun patchXing(fr: ByteArray, tagOff: Int, kept: Int, audioBytes: Long, offsets: LongArray) {
        fun putBE32(pos: Int, v: Long) {
            if (pos + 4 > fr.size) return
            fr[pos] = (v shr 24).toByte()
            fr[pos + 1] = (v shr 16).toByte()
            fr[pos + 2] = (v shr 8).toByte()
            fr[pos + 3] = v.toByte()
        }
        if (tagOff + 8 > fr.size) return
        val flags = ((fr[tagOff + 4].toInt() and 0xFF) shl 24) or ((fr[tagOff + 5].toInt() and 0xFF) shl 16) or
            ((fr[tagOff + 6].toInt() and 0xFF) shl 8) or (fr[tagOff + 7].toInt() and 0xFF)
        val total = fr.size.toLong() + audioBytes
        var q = tagOff + 8
        if ((flags and 1) != 0) {
            putBE32(q, kept.toLong())
            q += 4
        }
        if ((flags and 2) != 0) {
            putBE32(q, total)
            q += 4
        }
        if ((flags and 4) != 0 && q + 100 <= fr.size) {
            for (i in 0 until 100) {
                val k = minOf(kept - 1, (i.toLong() * kept / 100).toInt())
                val pos = fr.size.toLong() + offsets[k]
                fr[q + i] = minOf(255L, pos * 256 / total).toByte()
            }
        }
        if (fr.size >= 192 && tagOff + 124 <= fr.size) {
            val enc = String(fr, tagOff + 120, 4, Charsets.ISO_8859_1)
            if (enc == "LAME" || enc == "Lavc" || enc == "Lavf" || enc == "GOGO") {
                var crc = 0
                for (i in 0 until 190) {
                    crc = crc xor (fr[i].toInt() and 0xFF)
                    for (bit in 0 until 8) {
                        crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                    }
                }
                fr[190] = (crc shr 8).toByte()
                fr[191] = crc.toByte()
            }
        }
    }

    // ---------------------------------------------------------------- WAV

    private fun cutWav(source: Source, t: OutputTarget, ranges: List<LongArray>, totalBytes: Long) {
        openInput(source).use { raw ->
            val input = BufferedInputStream(raw, 1 shl 16)
            val riff = ByteArray(12)
            require(readFully(input, riff, 0, 12) == 12 &&
                String(riff, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(riff, 8, 4, Charsets.ISO_8859_1) == "WAVE") { "This WAV file is not readable." }
            var fmt: ByteArray? = null
            val chunk = ByteArray(8)
            while (true) {
                require(readFully(input, chunk, 0, 8) == 8) { "No audio data was found in this WAV file." }
                val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
                val size = le32(chunk, 4)
                if (id == "fmt ") {
                    val f = ByteArray(size.toInt())
                    require(readFully(input, f, 0, f.size) == f.size) { "This WAV file is damaged." }
                    if (size % 2 == 1L) input.skip(1)
                    fmt = f
                } else if (id == "data") {
                    val f = fmt ?: throw IllegalStateException("This WAV file has no format information.")
                    require(f.size >= 16) { "This WAV file is damaged." }
                    val byteRate = le32(f, 8)
                    val blockAlign = ((f[12].toInt() and 0xFF) or ((f[13].toInt() and 0xFF) shl 8)).toLong()
                    require(byteRate > 0 && blockAlign > 0) { "This WAV file is damaged." }
                    val dataSize = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                    fun byteAt(us: Long): Long =
                        if (us == Long.MAX_VALUE) Long.MAX_VALUE
                        else (us.toDouble() * byteRate / 1_000_000.0 / blockAlign).toLong() * blockAlign

                    val ch = FileOutputStream(t.pfd.fileDescriptor).channel
                    writeAll(ch, ByteArray(12 + 8 + f.size + 8)) // header is written at the end
                    var pos = 0L
                    var written = 0L
                    val buf = ByteArray(1 shl 16)
                    for (r in ranges) {
                        val a = minOf(byteAt(r[0]), dataSize)
                        val b = minOf(byteAt(r[1]), dataSize)
                        if (a > pos) {
                            pos += skipFully(input, a - pos)
                            if (pos < a) break
                        }
                        var remaining = b - pos
                        while (remaining > 0) {
                            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (read <= 0) break
                            writeAll(ch, buf, read)
                            remaining -= read
                            pos += read
                            written += read
                            if (totalBytes > 0) progress(pos.toDouble() / totalBytes)
                        }
                    }
                    require(written > 0) { "The selected range contained no audio to save." }
                    if (written % 2 == 1L) writeAll(ch, byteArrayOf(0))
                    val header = ByteArrayOutputStream()
                    header.write("RIFF".toByteArray(Charsets.ISO_8859_1))
                    header.write(le32Bytes(4 + 8 + f.size + 8 + written + (written % 2)))
                    header.write("WAVEfmt ".toByteArray(Charsets.ISO_8859_1))
                    header.write(le32Bytes(f.size.toLong()))
                    header.write(f)
                    header.write("data".toByteArray(Charsets.ISO_8859_1))
                    header.write(le32Bytes(written))
                    ch.position(0)
                    writeAll(ch, header.toByteArray())
                    return
                } else {
                    val skip = size + (size % 2)
                    require(skipFully(input, skip) == skip) { "No audio data was found in this WAV file." }
                }
            }
        }
    }

    // ---------------------------------------------------------------- saving

    private enum class Kind { VIDEO, AUDIO, IMAGE }

    /** A new, empty file in its final folder, open for writing. */
    private class OutputTarget(
        val uri: Uri?, val file: File?, val pfd: ParcelFileDescriptor, val name: String, val mime: String
    )

    private class Saved(val playUri: Uri, val viewUri: Uri?, val name: String, val mime: String)

    // Saved pictures that "View" may open: id -> (uri, mime).
    private val viewable = ConcurrentHashMap<String, Pair<Uri, String>>()

    private fun folderFor(kind: Kind): String = when (kind) {
        Kind.VIDEO -> Environment.DIRECTORY_MOVIES
        Kind.AUDIO -> Environment.DIRECTORY_MUSIC
        Kind.IMAGE -> Environment.DIRECTORY_PICTURES
    }

    /**
     * Creates "<original name> <n><ext>", e.g. "my song 1.mp3", "my song 2.mp3", directly in
     * Movies/Music/Pictures › MYmusicCuts. n is one higher than the highest number already used.
     * The result is written straight into this file: no temporary copy.
     */
    private fun createOutput(kind: Kind, originalName: String, ext: String, mime: String): OutputTarget {
        val folder = folderFor(kind)
        val relPath = "$folder/MYmusicCuts"
        val stem = outputStem(originalName)
        val pattern = numberPattern(stem)

        if (Build.VERSION.SDK_INT >= 29) {
            val collection: Uri = when (kind) {
                Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            var number = highestUsedNumber(collection, relPath, pattern) + 1
            for (attempt in 0 until 100) {
                val displayName = "$stem $number$ext"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = try {
                    context.contentResolver.insert(collection, values)
                } catch (_: Exception) {
                    null
                }
                if (uri == null) {
                    number++
                    continue
                }
                // If that name was already taken by a file this app cannot see, Android renames
                // the new file (e.g. "my song 3 (1).mp3"). Skip to the next number instead.
                val actual = displayNameOf(uri)
                if (actual != null && !actual.equals(displayName, ignoreCase = true)) {
                    context.contentResolver.delete(uri, null, null)
                    number++
                    continue
                }
                val pfd = try {
                    context.contentResolver.openFileDescriptor(uri, "rw")
                } catch (_: Exception) {
                    null
                }
                if (pfd == null) {
                    context.contentResolver.delete(uri, null, null)
                    throw IllegalStateException("Android could not write the output file.")
                }
                return OutputTarget(uri, null, pfd, displayName, mime)
            }
            throw IllegalStateException("Android could not create the output file.")
        }

        // Android 9 and older.
        val canWritePublic = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val root = if (canWritePublic) {
            publicDirectory(folder)
        } else {
            context.getExternalFilesDir(folder) ?: context.filesDir
        }
        val dir = File(root, "MYmusicCuts").apply { mkdirs() }
        var number = 1 + (dir.list()?.mapNotNull { n ->
            pattern.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()
        }?.maxOrNull() ?: 0)
        var dest = File(dir, "$stem $number$ext")
        while (dest.exists()) {
            number++
            dest = File(dir, "$stem $number$ext")
        }
        val pfd = ParcelFileDescriptor.open(
            dest,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        return OutputTarget(null, dest, pfd, dest.name, mime)
    }

    /** Closes the file and makes it visible to other apps. */
    private fun finishOutput(t: OutputTarget): Saved {
        try {
            t.pfd.close()
        } catch (_: Exception) {
        }
        if (t.uri != null) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(t.uri, done, null, null)
            return Saved(t.uri, t.uri, t.name, t.mime)
        }
        val file = t.file ?: throw IllegalStateException("The output file is missing.")
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(t.mime)) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return Saved(Uri.fromFile(file), scanned, t.name, t.mime)
    }

    /** Removes a half-written file after an error. */
    private fun abortOutput(t: OutputTarget) {
        try {
            t.pfd.close()
        } catch (_: Exception) {
        }
        try {
            if (t.uri != null) context.contentResolver.delete(t.uri, null, null) else t.file?.delete()
        } catch (_: Exception) {
        }
    }

    /** Original name without its extension; keeps spaces and Hindi or other scripts. */
    private fun outputStem(originalName: String): String {
        val raw = originalName.substringBeforeLast('.', originalName)
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
        // Android allows 255 bytes per file name; leave room for " 123.webm".
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            val piece = String(Character.toChars(cp))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 200) break
            sb.append(piece)
            bytes += size
            i += Character.charCount(cp)
        }
        return sb.toString().trim(' ', '.').ifEmpty { "media" }
    }

    /** Matches "<stem> <number>.<any extension>". */
    private fun numberPattern(stem: String): Regex =
        Regex("^" + Regex.escape(stem) + " (\\d{1,9})\\.[^.]+$", RegexOption.IGNORE_CASE)

    private fun highestUsedNumber(collection: Uri, relPath: String, pattern: Regex): Int {
        var max = 0
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("$relPath/", relPath),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val n = pattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (n > max) max = n
                }
            }
        } catch (_: Exception) {
        }
        return max
    }

    private fun displayNameOf(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("DEPRECATION")
    private fun publicDirectory(folder: String): File = Environment.getExternalStoragePublicDirectory(folder)

    private fun writeAll(ch: FileChannel, bytes: ByteArray, length: Int = bytes.size) {
        val bb = ByteBuffer.wrap(bytes, 0, length)
        while (bb.hasRemaining()) ch.write(bb)
    }

    private fun notifyPage(message: String) {
        val quoted = JSONObject.quote(message)
        webView.post {
            webView.evaluateJavascript(
                "if(window.onNativeCutterResult){window.onNativeCutterResult($quoted);}", null
            )
        }
    }

    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return cleaned.ifBlank { "media" }
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun skipFully(input: InputStream, count: Long): Long {
        var done = 0L
        val scratch = ByteArray(8192)
        while (done < count) {
            val skipped = input.skip(count - done)
            if (skipped > 0) {
                done += skipped
                continue
            }
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), count - done).toInt())
            if (n <= 0) break
            done += n
        }
        return done
    }

    private fun copyBytes(input: InputStream, out: OutputStream, count: Long): Long {
        val buf = ByteArray(1 shl 16)
        var remaining = count
        var done = 0L
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
            done += n
        }
        return done
    }

    private fun le32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24))

    private fun le32Bytes(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
