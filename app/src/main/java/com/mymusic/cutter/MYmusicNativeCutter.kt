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
    /** Runs after any cut or copy that is still being written has finished (at once when idle). */
    fun whenIdle(r: Runnable) { try { worker.execute { r.run() } } catch (_: Exception) { r.run() } }
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
        if (uri.scheme == "http" || uri.scheme == "https") {
            try {
                val c = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode in 200..299) size = c.contentLengthLong
                val cd = c.getHeaderField("Content-Disposition")
                Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(cd ?: "")?.groupValues?.get(1)?.let { name = Uri.decode(it) }
                c.disconnect()
            } catch (_: Exception) {}
            return Pair(name, size)
        }
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
        // Network storage plays at once, streamed (like ARC / Tiny player); copied only when cutting.
        if (uri.scheme == "http" || uri.scheme == "https") return false
        val text = Uri.decode(uri.toString()).lowercase()
        if (text.contains(".sec/") || text.endsWith(".sec")) return true
        if (!isSeekable(uri)) return true
        if (uri.scheme == "file") return false
        // The speed check is only for Solid Explorer's encrypted files (decrypted on the fly);
        // anything else - including network storage - plays straight away.
        if (!(uri.authority ?: "").lowercase().contains("solidexplorer")) return false
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
        val input = if (uri.scheme == "http" || uri.scheme == "https") {
            val c = java.net.URL(uri.toString()).openConnection()
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.getInputStream()
        } else context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("The other app did not hand over the file.")
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
                    .put("uri", (file?.let { Uri.fromFile(it) } ?: uri).toString())
                    .put("orig", uri.toString())
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
            val direct = parsed.optString("uri", "")
            val uri = (if (direct.isNotBlank()) Uri.parse(direct) else null)
                ?: findPicked(parsed.optString("fileName", ""), parsed.optLong("fileSizeBytes", -1L))
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

    /** Where the native player should read a shared or saved file from (private copy if one was made). */
    fun playableUri(id: String): Uri? {
        val item = shared[id] ?: return null
        return item.file?.let { Uri.fromFile(it) } ?: item.uri
    }

    fun pageMessage(message: String) = notifyPage(message)

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

    private var partLabel = ""

    private fun rangesFrom(jobMeta: JSONObject): List<LongArray>? {
        val arr = jobMeta.optJSONArray("ranges") ?: return null
        val list = ArrayList<LongArray>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            val a = (r.optDouble(0, 0.0) * 1_000_000).toLong()
            val e = r.optDouble(1, -1.0)
            val b = if (e < 0) Long.MAX_VALUE else (e * 1_000_000).toLong()
            if (b > a) list.add(longArrayOf(maxOf(0L, a), b))
        }
        return list
    }

    /** Writes one output file holding [ranges] of the source, in the source's own format. */
    private fun saveRanges(source: Source, name: String, format: String, isAudio: Boolean, ranges: List<LongArray>, durUs: Long): Saved {
        var target: OutputTarget? = null
        try {
            var totalUs = 0L
            for (r in ranges) totalUs += (if (r[1] == Long.MAX_VALUE) maxOf(durUs, r[0]) else r[1]) - r[0]
            lastProgressAt = 0L
            progressSent = -1
            val saved: Saved = when (format) {
                "mp3" -> {
                    val t = createOutput(Kind.AUDIO, name, ".mp3", "audio/mpeg"); target = t
                    cutMp3(source, t, ranges, sourceSize(source)); finishOutput(t)
                }
                "adts" -> {
                    val t = createOutput(Kind.AUDIO, name, ".aac", "audio/aac"); target = t
                    cutAdts(source, t, ranges, sourceSize(source)); finishOutput(t)
                }
                "wav" -> {
                    val t = createOutput(Kind.AUDIO, name, ".wav", "audio/wav"); target = t
                    cutWav(source, t, ranges, sourceSize(source)); finishOutput(t)
                }
                else -> {
                    val plan = probeContainer(source, isAudio)
                    val ext = if (plan.webm) ".webm" else if (isAudio) extFor(name, ".m4a") else extFor(name, ".mp4")
                    val mime = when {
                        plan.webm && isAudio -> "audio/webm"
                        plan.webm -> "video/webm"
                        isAudio -> "audio/mp4"
                        else -> "video/mp4"
                    }
                    val t = createOutput(if (isAudio) Kind.AUDIO else Kind.VIDEO, name, ext, mime); target = t
                    muxCut(source, t, plan, ranges, maxOf(totalUs, 1L)); finishOutput(t)
                }
            }
            target = null
            return saved
        } catch (e: Exception) {
            target?.let { abortOutput(it) }
            throw e
        }
    }

    /** Keeps the original extension for MP4-family files (m4a, m4b, 3gp, mov...) so the result looks like the original. */
    private fun extFor(name: String, fallback: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m4a", "m4b" -> ".$ext"
            "mp4", "m4v", "3gp", "mov" -> if (fallback == ".m4a") ".m4a" else ".$ext"
            else -> fallback
        }
    }

    private fun startJob(jobMeta: JSONObject, given: Source): Boolean {
        if (!busy.compareAndSet(false, true)) {
            notifyPage("Please wait: the previous cut is still saving.")
            return true
        }
        worker.execute {
            var netCopy: File? = null
            try {
                // Network files play straight from the network; only for cutting are they copied
                // to the phone for a moment (deleted again when the cut is saved).
                val gu = given.uri
                val source = if (given.file == null && gu != null && (gu.scheme == "http" || gu.scheme == "https")) {
                    notifyPage("Copying from network storage for cutting…")
                    val nm = jobMeta.optString("fileName", "media")
                    val f = copyToCache(gu, nm, -1L) { pct -> if (pct < 0) notifyPage("Copying from network storage… ${-pct - 1} MB") }
                    netCopy = f
                    Source(null, f)
                } else given
                val isAudio = jobMeta.optString("mediaType", "video") == "audio"
                val mode = jobMeta.optString("mode", "keep")
                val durUs = (jobMeta.optDouble("durationSeconds", 0.0) * 1_000_000).toLong()
                val name = jobMeta.optString("fileName", "media")
                val marked = rangesFrom(jobMeta)
                // Which files to write: each entry is the list of ranges for one output file.
                val outputs: List<List<LongArray>> = if (marked != null) {
                    val parts = Ranges.normalize(marked)
                    require(parts.isNotEmpty()) { "Mark at least one part first (Start, then End)." }
                    when (mode) {
                        "parts" -> parts.map { listOf(it) }
                        "remove" -> listOf(Ranges.complement(parts).filter { it[1] == Long.MAX_VALUE && it[0] < durUs || it[1] - it[0] > 50_000 })
                        else -> listOf(parts)
                    }
                } else {
                    val startUs = (jobMeta.optDouble("startSeconds", 0.0) * 1_000_000).toLong()
                    val endUs = (jobMeta.optDouble("endSeconds", 0.0) * 1_000_000).toLong()
                    require(startUs >= 0 && endUs > startUs) { "The selected cutting range is invalid." }
                    if (mode == "remove-middle")
                        listOf(listOf(longArrayOf(0L, startUs), longArrayOf(endUs, Long.MAX_VALUE)).filter { it[1] > it[0] })
                    else listOf(listOf(longArrayOf(startUs, endUs)))
                }
                require(outputs.all { it.isNotEmpty() }) { "Nothing would be left to save." }
                val format = detectFormat(source, name)
                val kind = if (isAudio || format == "mp3" || format == "wav" || format == "adts") "audio" else "video"
                beginJob(if (kind == "audio") "Audio" else "Video", categoryFor(if (marked == null && mode == "keep") "join" else mode), shortStem(name))
                val names = jobMeta.optJSONArray("names")
                forceExtractor = jobMeta.optBoolean("forceExtractor", false)
                val skipped = ArrayList<Int>()
                var firstError: String? = null
                for ((i, ranges) in outputs.withIndex()) {
                    partLabel = if (outputs.size > 1) "part ${i + 1} of ${outputs.size}" else ""
                    val given = if (mode == "parts") names?.optString(i, "")?.trim().orEmpty() else ""
                    outLabel = if (given.isNotEmpty()) "${i + 1} " + given else null
                    if (outputs.size > 1) {
                        // several parts: a part that cannot be read is skipped, the others are still saved
                        try {
                            val saved = try { saveRanges(source, name, format, isAudio, ranges, durUs) } finally { outLabel = null }
                            notifySaved(saved, kind)
                        } catch (e: Exception) {
                            skipped.add(i + 1)
                            if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                        }
                    } else {
                        val saved = try { saveRanges(source, name, format, isAudio, ranges, durUs) } finally { outLabel = null }
                        notifySaved(saved, kind)
                    }
                }
                partLabel = ""
                if (outputs.size > 1) {
                    val ok = outputs.size - skipped.size
                    require(ok > 0) { firstError ?: "None of the parts could be read from this file." }
                    notifyPage(if (skipped.isEmpty()) "✓ Saved ${outputs.size} parts"
                               else "✓ Saved $ok of ${outputs.size} parts (part ${skipped.joinToString(", ")} could not be read and was skipped)")
                }
            } catch (e: Exception) {
                notifyPage("Cut failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                partLabel = ""
                given.file?.delete()
                netCopy?.delete()
                busy.set(false)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- joining files

    /**
     * Joins whole files end to end without re-encoding. All files must be the same kind
     * (MP3, AAC, WAV, MP4/M4A or WebM) with the same audio/video settings.
     */
    @JavascriptInterface
    fun joinFiles(json: String): Boolean {
        val items = try { JSONObject(json).getJSONArray("items") } catch (_: Exception) { return false }
        if (items.length() < 2) { notifyPage("Tick at least two files to join."); return true }
        if (!busy.compareAndSet(false, true)) { notifyPage("Please wait: the previous job is still saving."); return true }
        worker.execute {
            var target: OutputTarget? = null
            try {
                val sources = ArrayList<Source>()
                val names = ArrayList<String>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val u = o.optString("uri", "")
                    require(u.isNotBlank()) { "File ${i + 1} cannot be read any more; remove it from the list and add it again." }
                    sources.add(Source(Uri.parse(u), null))
                    names.add(o.optString("name", "media"))
                }
                val formats = sources.indices.map { detectFormat(sources[it], names[it]) }
                require(formats.distinct().size == 1) {
                    "These files are different kinds (" + formats.distinct().joinToString(", ") { it.uppercase() } +
                        "). Only files of the same kind can be joined without re-encoding."
                }
                val total = sources.sumOf { maxOf(0L, sourceSize(it)) }
                lastProgressAt = 0L
                progressSent = -1
                val name = names[0]
                val saved: Saved
                var kind = "audio"
                val allAudio = formats[0] != "container" || sources.all { s ->
                    try { probeContainer(s, false).formats.values.all { (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") } } catch (_: Exception) { false }
                }
                beginJob(if (allAudio) "Audio" else "Video", "Save as one", shortStem(name) + " joined")
                when (formats[0]) {
                    "mp3" -> {
                        val t = createOutput(Kind.AUDIO, name, ".mp3", "audio/mpeg"); target = t
                        val out = java.io.BufferedOutputStream(FileOutputStream(t.pfd.fileDescriptor), 1 shl 16)
                        val r = Mp3Tool.copy(sources.map { s -> { openInput(s) } }, out, null, total) { progress(it) }
                        out.flush(); writeXing(t, r); saved = finishOutput(t)
                    }
                    "adts" -> {
                        val t = createOutput(Kind.AUDIO, name, ".aac", "audio/aac"); target = t
                        val out = java.io.BufferedOutputStream(FileOutputStream(t.pfd.fileDescriptor), 1 shl 16)
                        AdtsTool.copy(sources.map { s -> { openInput(s) } }, out, null, total) { progress(it) }
                        out.flush(); saved = finishOutput(t)
                    }
                    "wav" -> {
                        val t = createOutput(Kind.AUDIO, name, ".wav", "audio/wav"); target = t
                        WavTool.join(sources.map { s -> { openInput(s) } }, FileOutputStream(t.pfd.fileDescriptor).channel, total) { progress(it) }
                        saved = finishOutput(t)
                    }
                    else -> {
                        val plans = sources.map { probeContainer(it, false) }
                        val isAudio = plans.all { p -> p.formats.values.all { (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") } }
                        kind = if (isAudio) "audio" else "video"
                        val first = plans[0]
                        require(plans.all { it.webm == first.webm }) { "MP4 and WebM files cannot be joined together." }
                        val ext = if (first.webm) ".webm" else if (isAudio) extFor(name, ".m4a") else extFor(name, ".mp4")
                        val mime = when { first.webm && isAudio -> "audio/webm"; first.webm -> "video/webm"; isAudio -> "audio/mp4"; else -> "video/mp4" }
                        val t = createOutput(if (isAudio) Kind.AUDIO else Kind.VIDEO, name, ext, mime); target = t
                        joinContainers(sources, plans, t)
                        saved = finishOutput(t)
                    }
                }
                target = null
                notifySaved(saved, kind)
                notifyPage("✓ Joined ${sources.size} files")
            } catch (e: Exception) {
                target?.let { abortOutput(it) }
                notifyPage("Join failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    private fun trackKind(f: MediaFormat) = if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) "video" else "audio"

    private fun sameSettings(a: MediaFormat, b: MediaFormat): Boolean {
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        fun int(f: MediaFormat, k: String) = if (f.containsKey(k)) f.getInteger(k) else -1
        return if (trackKind(a) == "video")
            int(a, MediaFormat.KEY_WIDTH) == int(b, MediaFormat.KEY_WIDTH) && int(a, MediaFormat.KEY_HEIGHT) == int(b, MediaFormat.KEY_HEIGHT)
        else
            int(a, MediaFormat.KEY_SAMPLE_RATE) == int(b, MediaFormat.KEY_SAMPLE_RATE) && int(a, MediaFormat.KEY_CHANNEL_COUNT) == int(b, MediaFormat.KEY_CHANNEL_COUNT)
    }

    private fun joinContainers(sources: List<Source>, plans: List<ContainerPlan>, t: OutputTarget) {
        val first = plans[0]
        val muxer = MediaMuxer(t.pfd.fileDescriptor,
            if (first.webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            // one output track per kind (video, audio), taken from the first file
            val outByKind = LinkedHashMap<String, Int>()
            val firstFormatByKind = HashMap<String, MediaFormat>()
            for ((_, f) in first.formats) {
                val k = trackKind(f)
                if (k !in outByKind) { outByKind[k] = muxer.addTrack(f); firstFormatByKind[k] = f }
            }
            if (!first.webm && first.rotation != 0) muxer.setOrientationHint(first.rotation)
            val maps = plans.mapIndexed { i, plan ->
                val m = HashMap<Int, Int>()
                for ((idx, f) in plan.formats) {
                    val k = trackKind(f)
                    val out = outByKind[k] ?: continue
                    if (m.values.contains(out)) continue
                    require(sameSettings(firstFormatByKind[k]!!, f)) {
                        "File ${i + 1} has different " + (if (k == "video") "video size or codec" else "audio settings") +
                            ", so it cannot be joined without re-encoding."
                    }
                    m[idx] = out
                }
                require(m.isNotEmpty()) { "File ${i + 1} has no matching audio or video." }
                m
            }
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(plans.maxOf { it.maxInput }.coerceAtMost(32 * 1024 * 1024))
            var totalUs = 0L
            for (s in sources) totalUs += durationOf(s)
            var offsetUs = 0L
            var doneUs = 0L
            for ((i, s) in sources.withIndex()) {
                partLabel = "file ${i + 1} of ${sources.size}"
                val lastUs = writeSegment(s, muxer, maps[i], buffer, 0L, Long.MAX_VALUE, offsetUs, doneUs, maxOf(totalUs, 1L))
                if (lastUs >= 0) {
                    doneUs += lastUs - offsetUs
                    offsetUs = lastUs + 23_000L
                }
            }
            partLabel = ""
        } finally {
            if (started) try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun durationOf(source: Source): Long {
        val ex = try { newExtractor(source) } catch (_: Exception) { return 0L }
        try {
            var d = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) d = maxOf(d, f.getLong(MediaFormat.KEY_DURATION))
            }
            return d
        } finally { ex.release() }
    }

    // ---------------------------------------------------------------- playlist autosave

    @Volatile private var playlistText = ""
    private val playlistWriter = Executors.newSingleThreadExecutor()

    /** Saves the current playlist as "MYmusic Cutter/Playlists/Current playlist.m3u8" (replaced each time; removed when empty). */
    @JavascriptInterface
    fun savePlaylist(text: String) {
        playlistText = text
        playlistWriter.execute {
            val t = playlistText
            if (t != text) return@execute   // a newer version is queued
            try { writePlaylist(t) } catch (_: Exception) {}
        }
    }

    private fun writePlaylist(text: String) {
        val name = "Current playlist.m3u8"
        val empty = text.lines().none { it.isNotBlank() && !it.startsWith("#") }
        if (Build.VERSION.SDK_INT >= 29) {
            val rel = "${Environment.DIRECTORY_DOWNLOADS}/$rootName/Playlists/"
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var existing: Uri? = null
            context.contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(rel, name), null)?.use { c ->
                if (c.moveToFirst()) existing = android.content.ContentUris.withAppendedId(col, c.getLong(0))
            }
            if (empty) { existing?.let { context.contentResolver.delete(it, null, null) }; return }
            val uri = existing ?: context.contentResolver.insert(col, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }) ?: return
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } else {
            val f = File(legacyRoot(), "Playlists/$name")
            if (empty) { f.delete(); return }
            f.parentFile?.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }
    }

    // ---------------------------------------------------------------- song finder (silence detection)

    @Volatile private var levelsJob = -1

    /**
     * Decodes the sound once and reports its loudness every 50 ms; the page then finds the quiet
     * gaps between songs (and can re-run that instantly with other settings).
     */
    @JavascriptInterface
    fun analyseLevels(url: String, job: Int): Boolean {
        val u = Uri.parse(url)
        val uri = if (u.host == "appassets.androidplatform.net") playableUri(u.lastPathSegment ?: "") else u
        if (uri == null) return false
        levelsJob = job
        Thread {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                if (uri.scheme == "file") ex.setDataSource(uri.path ?: "") else ex.setDataSource(context, uri, null)
                var track = -1
                for (i in 0 until ex.trackCount) {
                    if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
                }
                require(track >= 0) { "This file has no sound." }
                ex.selectTrack(track)
                val fmt = ex.getTrackFormat(track)
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                var chans = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                var isFloat = false
                val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec = c
                c.configure(fmt, null, null, 0)
                c.start()
                val levels = java.io.ByteArrayOutputStream(1 shl 16)   // one byte per 10 ms: dB + 100
                var winFrames = maxOf(1, rate / 100)
                var acc = 0.0
                var accN = 0
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                var lastPct = -1
                var shorts = ShortArray(0)
                var floats = FloatArray(0)
                while (levelsJob == job && !outDone) {
                    // feed every free input buffer at once
                    while (!inDone) {
                        val ii = c.dequeueInputBuffer(0)
                        if (ii < 0) break
                        val ib = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                    // take every finished output buffer; wait only briefly when there is none
                    var oi = c.dequeueOutputBuffer(info, 2000)
                    while (oi != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = c.outputFormat
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) chans = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4
                            winFrames = maxOf(1, rate / 100)
                        } else if (oi >= 0) {
                            val ob = c.getOutputBuffer(oi)
                            if (ob != null && info.size > 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                val bb = ob.slice().order(java.nio.ByteOrder.nativeOrder())
                                val ch = maxOf(1, chans)
                                if (isFloat) {
                                    val fb = bb.asFloatBuffer()
                                    val total = fb.remaining()
                                    if (floats.size < total) floats = FloatArray(total)
                                    fb.get(floats, 0, total)
                                    var i = 0
                                    while (i + ch <= total) {
                                        var m = 0f
                                        for (k in 0 until ch) m += floats[i + k]
                                        m /= ch
                                        acc += (m * m).toDouble(); accN++
                                        if (accN >= winFrames) { levels.write(dbByte(acc / accN)); acc = 0.0; accN = 0 }
                                        i += ch
                                    }
                                } else {
                                    val sb = bb.asShortBuffer()
                                    val total = sb.remaining()
                                    if (shorts.size < total) shorts = ShortArray(total)
                                    sb.get(shorts, 0, total)
                                    val scale = 1.0 / (32768.0 * ch)
                                    var i = 0
                                    while (i + ch <= total) {
                                        var m = 0
                                        for (k in 0 until ch) m += shorts[i + k]
                                        val v = m * scale
                                        acc += v * v; accN++
                                        if (accN >= winFrames) { levels.write(dbByte(acc / accN)); acc = 0.0; accN = 0 }
                                        i += ch
                                    }
                                }
                            }
                            c.releaseOutputBuffer(oi, false)
                            if (durUs > 0) {
                                val pct = (info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; sendJs("window.__mmLevelsProgress&&window.__mmLevelsProgress($job,$pct)") }
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { outDone = true; break }
                        }
                        oi = c.dequeueOutputBuffer(info, 0)
                    }
                }
                if (levelsJob != job) return@Thread
                val b64 = Base64.encodeToString(levels.toByteArray(), Base64.NO_WRAP)
                sendJs("window.__mmLevels&&window.__mmLevels($job,{win:0.01,b64:${JSONObject.quote(b64)}})")
            } catch (e: Exception) {
                sendJs("window.__mmLevelsFailed&&window.__mmLevelsFailed($job,${JSONObject.quote(e.message ?: e.javaClass.simpleName)})")
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
            }
        }.start()
        return true
    }

    private fun dbByte(meanSquare: Double): Int {
        val db = 10 * Math.log10(meanSquare + 1e-12)
        return (db.coerceIn(-100.0, 0.0) + 100).toInt()
    }

    private fun sendJs(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
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
            webView.evaluateJavascript("window.onNativeCutterProgress&&window.onNativeCutterProgress($pct," + JSONObject.quote(partLabel) + ")", null)
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
            .put("uri", s.playUri.toString())
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
        val head = ByteArray(64)
        val n = try {
            openInput(source).use { readFully(it, head, 0, 64) }
        } catch (_: Exception) {
            0
        }
        // The content decides, not the name: downloads called ".mp3" are often really M4A / AAC / WebM.
        sniffFormat(source, head, n)?.let { return it }
        if (ext == "mp3") return "mp3"
        if (ext == "wav" || ext == "wave") return "wav"
        if (ext == "aac" || ext == "adts") {
            val isMp4 = n >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            if (!isMp4 && (n < 7 || AdtsTool.looksLikeAdts(head, n))) return "adts"
            if (!isMp4) return "adts"
        }
        if (ext in setOf("mp4", "m4a", "m4v", "m4b", "mov", "3gp", "aac", "webm", "mkv")) return "container"
        if (n >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") return "wav"
        if (n >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") return "mp3"
        if (n >= 4 && Mp3Header.parse(head, 0) != null) return "mp3"
        return "container"
    }

    /** What the bytes say the file is, or null if they do not say clearly. */
    private fun sniffFormat(source: Source, head: ByteArray, n: Int): String? {
        if (n < 12) return null
        fun s(o: Int, len: Int) = String(head, o, len, Charsets.ISO_8859_1)
        if (s(4, 4) == "ftyp") return "container"                                      // MP4 / M4A
        if ((head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
            (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3) return "container"   // WebM / MKV
        if (s(0, 4) == "OggS") return "container"
        if (s(0, 4) == "RIFF" && s(8, 4) == "WAVE") return "wav"
        // after an ID3 tag: look at what follows it
        var b = head; var m = n
        if (s(0, 3) == "ID3") {
            val tag = id3Size(head)
            if (tag <= 10) return null
            val after = ByteArray(16)
            m = try {
                openInput(source).use { inp -> if (skipIn(inp, tag.toLong()) < tag) 0 else readFully(inp, after, 0, 16) }
            } catch (_: Exception) { 0 }
            if (m < 4) return "mp3"
            b = after
            if (String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp") return "container"
        }
        val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xF0) == 0xF0 && (b1 and 0x06) == 0) return "adts"   // AAC (ADTS): layer bits 00
        if (Mp3Header.parse(b, 0) != null) return "mp3"
        return null
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

    /**
     * Copies samples from the video keyframe at or before fromUs up to toUs, without re-coding.
     * Video is kept in decode order with every frame (B-frames have lower times than the frame
     * before them, which is normal), so the picture stays sharp. At the end a few frames past toUs
     * are kept so the last pictures still have the frames they are built from.
     * Returns the last output time, or -1.
     */
    private fun writeSegment(
        source: Source, muxer: MediaMuxer, trackMap: Map<Int, Int>, buffer: ByteBuffer,
        fromUs: Long, toUs: Long, outputOffsetUs: Long, doneUs: Long, totalUs: Long
    ): Long {
        val ex = newExtractor(source)
        var lastOut = -1L
        try {
            var videoTrack = -1
            for (t in trackMap.keys) {
                val mime = try { ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: "" } catch (_: Exception) { "" }
                if (mime.startsWith("video/")) { videoTrack = t; break }
            }
            // where the picture can really start: the keyframe at or before fromUs
            var startUs = fromUs
            if (videoTrack >= 0) {
                val probe = newExtractor(source)
                try {
                    probe.selectTrack(videoTrack)
                    probe.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val t = probe.sampleTime
                    if (t >= 0 && t <= fromUs + 1_000L) startUs = t
                } finally { probe.release() }
            }
            trackMap.keys.forEach { ex.selectTrack(it) }
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val lastPts = HashMap<Int, Long>()
            val done = HashSet<Int>()
            val tailUs = 200_000L
            var videoStarted = videoTrack < 0
            var videoWrote = false
            while (true) {
                val track = ex.sampleTrackIndex
                val timeUs = ex.sampleTime
                if (track < 0 || timeUs < 0) break
                val out = trackMap[track]
                if (out != null && track !in done) {
                    val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    var write: Boolean
                    if (track == videoTrack) {
                        if (!videoStarted && sync && timeUs >= startUs - 1_000L) videoStarted = true
                        write = videoStarted && timeUs >= startUs
                        if (toUs != Long.MAX_VALUE && (timeUs >= toUs + tailUs || (sync && videoWrote && timeUs >= toUs))) {
                            done.add(track); write = false
                        }
                    } else {
                        write = timeUs >= startUs
                        if (toUs != Long.MAX_VALUE && timeUs >= toUs) { done.add(track); write = false }
                    }
                    if (write) {
                        try {
                            buffer.clear()
                            val size = ex.readSampleData(buffer, 0)
                            val pts = outputOffsetUs + (timeUs - startUs)
                            val prev = lastPts[out] ?: -1L
                            // audio must move forward; video may go back a little (B-frames)
                            if (size > 0 && (track == videoTrack || pts > prev)) {
                                var flags = 0
                                if (sync) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                                info.set(0, size, pts, flags)
                                muxer.writeSampleData(out, buffer, info)
                                if (track == videoTrack) videoWrote = true
                                if (pts > prev) lastPts[out] = pts
                                if (pts > lastOut) lastOut = pts
                                progress((doneUs + (timeUs - startUs)).toDouble() / totalUs)
                            }
                        } catch (_: Exception) {
                            // a damaged frame: skip it and carry on
                        }
                    }
                    if (done.size == trackMap.size) break
                }
                if (!ex.advance()) break
            }
        } finally {
            ex.release()
        }
        return lastOut
    }

    // ---------------------------------------------------------------- MP3

    @Volatile private var forceExtractor = false   // test switch only (emulator test)

    private fun cutMp3(source: Source, t: OutputTarget, ranges: List<LongArray>, totalBytes: Long) {
        var wantUs = 0L
        for (r0 in ranges) if (r0[1] != Long.MAX_VALUE) wantUs += r0[1] - r0[0]
        val r: Mp3Tool.Result? = try {
            val raw = FileOutputStream(t.pfd.fileDescriptor)
            val out = java.io.BufferedOutputStream(raw, 1 shl 16)
            val res = Mp3Tool.copy(listOf({ openInput(source) }), out, ranges, totalBytes) { progress(it) }
            out.flush()
            res
        } catch (_: Exception) { null }
        if (r != null && !forceExtractor && (wantUs <= 0 || r.keptUs >= wantUs * 0.5)) { writeXing(t, r); return }
        // The frame-by-frame reader lost its way in this file (joined albums, damaged stretches...).
        // Read it the way Android's own player and the song finder do, copying the original frames.
        val ch = FileOutputStream(t.pfd.fileDescriptor).channel
        ch.truncate(0); ch.position(0)
        cutMp3ByExtractor(source, t, ranges)
    }

    /** MP3 frames copied unchanged, located by Android's media reader (same timeline as the song finder). */
    private fun cutMp3ByExtractor(source: Source, t: OutputTarget, ranges: List<LongArray>) {
        val ex = newExtractor(source)
        try {
            var track = -1
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
            }
            require(track >= 0) { "This file has no sound." }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val lastEnd = Ranges.lastEnd(ranges)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val total = maxOf(1L, if (lastEnd != Long.MAX_VALUE) lastEnd else durUs)
            val buf = ByteBuffer.allocate(1 shl 16)
            val arr = ByteArray(1 shl 16)
            val out = java.io.BufferedOutputStream(FileOutputStream(t.pfd.fileDescriptor), 1 shl 16)
            var wrote = 0
            while (true) {
                val ts = ex.sampleTime
                if (ts < 0) break
                if (lastEnd != Long.MAX_VALUE && ts >= lastEnd) break
                if (Ranges.keeps(ranges, ts.toDouble(), ts + 1.0)) {
                    buf.clear()
                    val n = try { ex.readSampleData(buf, 0) } catch (_: Exception) { -1 }   // damaged: skip
                    if (n > 4) {
                        buf.position(0); buf.get(arr, 0, n)
                        // only whole MP3 frames (they start with the frame sync)
                        if ((arr[0].toInt() and 0xFF) == 0xFF && (arr[1].toInt() and 0xE0) == 0xE0) { out.write(arr, 0, n); wrote++ }
                    }
                }
                progress(ts.toDouble() / total)
                if (!ex.advance()) break
            }
            out.flush()
            require(wrote > 0) { "The selected range contained no audio to save." }
        } finally {
            ex.release()
        }
    }

    private fun writeXing(t: OutputTarget, r: Mp3Tool.Result) {
        val xf = r.xing ?: return
        if (r.xingPos < 0) return
        Mp3Tool.patchXing(xf, r.tagOffset, r.kept, r.audioBytes, r.offsets)
        val ch = FileOutputStream(t.pfd.fileDescriptor).channel
        ch.position(r.xingPos)
        writeAll(ch, xf)
    }

    private fun cutAdts(source: Source, t: OutputTarget, ranges: List<LongArray>, totalBytes: Long) {
        val out = java.io.BufferedOutputStream(FileOutputStream(t.pfd.fileDescriptor), 1 shl 16)
        AdtsTool.copy(listOf({ openInput(source) }), out, ranges, totalBytes) { progress(it) }
        out.flush()
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
    // All results go into one folder, "MYmusic Cutter" (inside Download on Android 10 and newer,
    // where apps may only create folders inside the standard ones; on the main storage before that):
    //   Audio | Video  ›  Save parts | Save as one | Erase parts  ›  "<name> <job number>"  ›  files
    //   Playlists  ›  Current playlist.m3u8
    private val rootName = "MYmusic Cutter"
    @Volatile private var jobDir = "Audio/Save as one/media 1"

    private fun categoryFor(mode: String) = when (mode) {
        "parts" -> "Save parts"
        "remove", "remove-middle" -> "Erase parts"
        else -> "Save as one"
    }

    @Suppress("DEPRECATION")
    private fun legacyRoot(): File {
        val canWritePublic = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return if (canWritePublic) File(Environment.getExternalStorageDirectory(), rootName)
               else File(context.getExternalFilesDir(null) ?: context.filesDir, rootName)
    }

    /** Picks a fresh sub-folder for this job: "<name> 1", "<name> 2", ... */
    private fun beginJob(kindFolder: String, category: String, originalName: String) {
        val stem = outputStem(originalName)
        val base = "$kindFolder/$category"
        val pat = Regex("^" + Regex.escape(stem) + " (\\d{1,6})$", RegexOption.IGNORE_CASE)
        var max = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$rootName/$base/"
            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$prefix%"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val rp = c.getString(0) ?: continue
                        if (!rp.startsWith(prefix)) continue
                        val folder = rp.substring(prefix.length).trimEnd('/').substringBefore('/')
                        val n = pat.matchEntire(folder)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                        if (n > max) max = n
                    }
                }
            } catch (_: Exception) {}
        } else {
            File(legacyRoot(), base).list()?.forEach { n ->
                val v = pat.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                if (v > max) max = v
            }
        }
        jobDir = "$base/$stem ${max + 1}"
    }

    /** A name given to the part being saved (e.g. from the first words sung), or null. */
    @Volatile private var outLabel: String? = null

    /** Short file names: the first words of the original name, at most about 16 letters. */
    private fun shortStem(originalName: String): String {
        val full = outputStem(originalName)
        val words = full.split(' ').filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty() && sb.length + 1 + w.length > 16) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
            if (sb.length >= 16) break
        }
        var r = sb.toString()
        if (r.length > 16) r = r.substring(0, 16).trim()
        return r.ifBlank { "cut" }
    }

    private fun createOutput(kind: Kind, originalName: String, ext: String, mime: String): OutputTarget {
        val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$rootName/$jobDir"
        val label = outLabel?.let { outputStem(it + ".x").take(48).trim() }?.takeIf { it.isNotBlank() }
        val stem = label ?: shortStem(originalName)
        val pattern = numberPattern(stem)
        fun nameFor(n: Int) = if (label != null && n <= 1) "$stem$ext" else "$stem $n$ext"

        if (Build.VERSION.SDK_INT >= 29) {
            val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var number = if (label != null) 1 else highestUsedNumber(collection, relPath, pattern) + 1
            for (attempt in 0 until 100) {
                val displayName = nameFor(number)
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
        val dir = File(legacyRoot(), jobDir).apply { mkdirs() }
        var number = if (label != null) 1 else 1 + (dir.list()?.mapNotNull { n ->
            pattern.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()
        }?.maxOrNull() ?: 0)
        var dest = File(dir, nameFor(number))
        while (dest.exists()) {
            number++
            dest = File(dir, nameFor(number))
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
