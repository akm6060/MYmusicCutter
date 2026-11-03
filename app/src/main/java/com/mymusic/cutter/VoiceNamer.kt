package com.mymusic.cutter

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Names cut parts from the first words that are sung or spoken, fully offline (Vosk).
 * Hindi and Indian-English voice packs (about 80 MB) are downloaded once, on first use.
 * Hindi words are written in English letters (e.g. "Tujhe Dekha To").
 */
class VoiceNamer(
    private val activity: Activity,
    private val webView: WebView,
    private val cutter: MYmusicNativeCutter
) {
    private class Pack(val dir: String, val urls: List<String>)

    private val packs = mapOf(
        "hi" to Pack("hi", listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            "https://huggingface.co/rhasspy/vosk-models/resolve/main/hi/vosk-model-small-hi-0.22.zip"
        )),
        "en" to Pack("en", listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            "https://huggingface.co/grimso/vosk-models/resolve/main/vosk-model-small-en-us-0.15.zip"
        ))
    )

    @Volatile private var job = 0
    private var models = HashMap<String, Model>()

    private fun root() = File(activity.filesDir, "voice")
    private fun packDir(lang: String) = File(root(), lang)
    private fun ready(lang: String) = File(packDir(lang), ".ready").exists()

    @JavascriptInterface
    fun hasPacks(): Boolean = packs.keys.all { ready(it) }

    @JavascriptInterface
    fun cancel() { job++ }

    /** parts: [[startSec, endSec], ...]. Sends __mmVoice(job, event) for each step. */
    @JavascriptInterface
    fun nameParts(url: String, jobId: Int, partsJson: String) {
        job = jobId
        val parts = try { JSONArray(partsJson) } catch (_: Exception) { return }
        val uri = resolve(url)
        Thread {
            try {
                if (uri == null) throw IllegalStateException("The file could not be opened")
                ensurePacks(jobId)
                if (job != jobId) return@Thread
                send(jobId, JSONObject().put("type", "loading"))
                for (lang in packs.keys) if (models[lang] == null) models[lang] = Model(packDir(lang).absolutePath)
                for (i in 0 until parts.length()) {
                    if (job != jobId) return@Thread
                    val p = parts.optJSONArray(i) ?: continue
                    val s = p.optDouble(0, 0.0)
                    val e = p.optDouble(1, s + 60)
                    send(jobId, JSONObject().put("type", "part").put("i", i))
                    val name = try { listen(uri, (s * 1e6).toLong(), (minOf(e, s + 90.0) * 1e6).toLong(), jobId) } catch (_: Exception) { "" }
                    send(jobId, JSONObject().put("type", "name").put("i", i).put("text", name))
                }
                send(jobId, JSONObject().put("type", "done"))
            } catch (ex: Throwable) {
                send(jobId, JSONObject().put("type", "error").put("message", ex.message ?: ex.javaClass.simpleName))
            }
        }.start()
    }

    private fun resolve(url: String): Uri? {
        val u = Uri.parse(url)
        return if (u.host == "appassets.androidplatform.net") cutter.playableUri(u.lastPathSegment ?: "") else u
    }

    private fun send(jobId: Int, o: JSONObject) {
        val js = "window.__mmVoice&&window.__mmVoice($jobId,$o)"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // ------------------------------------------------------------ voice packs

    private fun ensurePacks(jobId: Int) {
        val missing = packs.keys.filter { !ready(it) }
        if (missing.isEmpty()) return
        for ((k, lang) in missing.withIndex()) {
            val pack = packs.getValue(lang)
            var lastErr: Exception? = null
            for (u in pack.urls) {
                try {
                    download(u, packDir(lang)) { pct ->
                        val total = ((k * 100 + pct) / missing.size)
                        send(jobId, JSONObject().put("type", "download").put("pct", total))
                    }
                    lastErr = null
                    break
                } catch (e: Exception) {
                    lastErr = e
                    if (job != jobId) throw IllegalStateException("Stopped")
                }
            }
            if (lastErr != null) throw IllegalStateException("Could not download the voice pack (internet needed once): ${lastErr.message}")
        }
    }

    private fun download(url: String, dest: File, progress: (Int) -> Unit) {
        val zip = File(activity.cacheDir, dest.name + ".zip")
        var conn = URL(url).openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), loc).openConnection() as HttpURLConnection
                hops++
                continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        val len = conn.contentLengthLong
        conn.inputStream.use { inp ->
            FileOutputStream(zip).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (len > 0) {
                        val pct = (got * 100 / len).toInt()
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
        conn.disconnect()
        // unpack, dropping the zip's top folder
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val f = File(tmp, rel)
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { zin.copyTo(it) }
            }
        }
        zip.delete()
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IllegalStateException("Could not unpack the voice pack")
        File(dest, ".ready").writeText("ok")
    }

    // ------------------------------------------------------------ listening

    private class Heard(var words: ArrayList<Pair<String, Double>> = ArrayList())

    private fun parse(json: String, into: Heard) {
        try {
            val arr = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                val word = w.optString("word")
                if (word.isNotBlank() && word != "[unk]" && word != "<unk>") into.words.add(word to w.optDouble("conf", 0.0))
            }
        } catch (_: Exception) {
        }
    }

    private fun good(h: Heard) = h.words.count { it.second >= 0.6 }

    private fun listen(uri: Uri, fromUs: Long, toUs: Long, jobId: Int): String {
        val recs = models.mapValues { (_, m) -> Recognizer(m, 16000f).apply { setWords(true) } }
        val heard = recs.mapValues { Heard() }
        try {
            decodeMono16k(uri, fromUs, toUs, jobId) { buf, n ->
                for ((lang, r) in recs) if (r.acceptWaveForm(buf, n)) parse(r.result, heard.getValue(lang))
                heard.values.none { good(it) >= 4 }   // keep going until one language has 4 clear words
            }
            for ((lang, r) in recs) parse(r.finalResult, heard.getValue(lang))
        } finally {
            recs.values.forEach { try { it.close() } catch (_: Exception) {} }
        }
        // pick the language that heard more, and more clearly
        fun score(h: Heard): Double {
            val w = h.words.take(6)
            if (w.isEmpty()) return 0.0
            return w.sumOf { it.second } / w.size * minOf(w.size, 4)
        }
        val best = heard.maxByOrNull { score(it.value) } ?: return ""
        if (score(best.value) < 0.9) return ""
        val words = best.value.words.filter { it.second >= 0.35 }.take(5).map { it.first }
        val text = if (best.key == "hi") words.joinToString(" ") { Translit.toLatin(it) } else words.joinToString(" ")
        return text.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    /** Decodes the sound between fromUs and toUs to 16 kHz mono, in half-second pieces. */
    private fun decodeMono16k(uri: Uri, fromUs: Long, toUs: Long, jobId: Int, sink: (ShortArray, Int) -> Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") ex.setDataSource(uri.path ?: "") else ex.setDataSource(activity, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
            require(track >= 0) { "No sound" }
            ex.selectTrack(track)
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val fmt = ex.getTrackFormat(track)
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var chans = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var isFloat = false
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            val out = ShortArray(8000)
            var outN = 0
            var acc = 0.0; var accN = 0; var pos = 0.0; var next = rate / 16000.0
            var hpY = 0.0; var hpX = 0.0
            val hpA = 1.0 / (1.0 + 2 * Math.PI * 150.0 / 16000.0)   // removes deep bass / drum thump
            var shorts = ShortArray(0); var floats = FloatArray(0)
            var inDone = false; var outDone = false; var keep = true
            fun push(x: Double): Boolean {
                acc += x; accN++; pos += 1.0
                if (pos >= next) {
                    next += rate / 16000.0
                    val v = acc / accN; acc = 0.0; accN = 0
                    hpY = hpA * (hpY + v - hpX); hpX = v
                    out[outN++] = (hpY * 32767.0 * 1.5).coerceIn(-32767.0, 32767.0).toInt().toShort()
                    if (outN == out.size) { keep = sink(out, outN); outN = 0 }
                }
                return keep
            }
            while (keep && !outDone && job == jobId) {
                while (!inDone) {
                    val ii = c.dequeueInputBuffer(0)
                    if (ii < 0) break
                    val n = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                    if (n < 0 || ex.sampleTime > toUs) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
                var oi = c.dequeueOutputBuffer(info, 2000)
                while (oi != MediaCodec.INFO_TRY_AGAIN_LATER && keep) {
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = c.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) chans = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4
                        next = pos + rate / 16000.0
                    } else if (oi >= 0) {
                        val ob = c.getOutputBuffer(oi)
                        val inRange = info.presentationTimeUs >= fromUs - 30_000
                        if (ob != null && info.size > 0 && inRange) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val bb = ob.slice().order(java.nio.ByteOrder.nativeOrder())
                            val ch = maxOf(1, chans)
                            if (isFloat) {
                                val fb = bb.asFloatBuffer(); val total = fb.remaining()
                                if (floats.size < total) floats = FloatArray(total)
                                fb.get(floats, 0, total)
                                var i = 0
                                while (i + ch <= total && keep) { var m = 0.0; for (k in 0 until ch) m += floats[i + k]; push(m / ch); i += ch }
                            } else {
                                val sb = bb.asShortBuffer(); val total = sb.remaining()
                                if (shorts.size < total) shorts = ShortArray(total)
                                sb.get(shorts, 0, total)
                                var i = 0
                                while (i + ch <= total && keep) { var m = 0; for (k in 0 until ch) m += shorts[i + k]; push(m / (32768.0 * ch)); i += ch }
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                        c.releaseOutputBuffer(oi, false)
                        if (outDone) break
                    }
                    oi = c.dequeueOutputBuffer(info, 0)
                }
            }
            if (keep && outN > 0) sink(out, outN)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}

/** Devanagari to everyday English spelling (Hinglish), e.g. "तुझे देखा तो" → "tujhe dekha to". */
object Translit {
    private val vowels = mapOf('अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e")
    private val matras = mapOf('ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri", 'े' to "e",
        'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e")
    private val cons = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh",
        'ज' to "j", 'झ' to "jh", 'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
        'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y")
    private val nuktaOf = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")

    fun toLatin(word: String): String {
        val s = word
        val sb = StringBuilder()
        var i = 0
        var pendingA = false
        while (i < s.length) {
            val ch = s[i]
            val c = cons[ch]
            if (c != null) {
                if (pendingA) sb.append('a')
                var latin = c
                if (i + 1 < s.length && s[i + 1] == '़') { latin = nuktaOf[ch] ?: c; i++ }
                sb.append(latin)
                pendingA = true
                i++
                continue
            }
            val m = matras[ch]
            when {
                m != null -> { sb.append(m); pendingA = false }
                ch == '्' -> pendingA = false
                ch == 'ं' || ch == 'ँ' -> { if (pendingA) sb.append('a'); pendingA = false; sb.append('n') }
                ch == 'ः' -> { if (pendingA) sb.append('a'); pendingA = false; sb.append('h') }
                vowels[ch] != null -> { if (pendingA) sb.append('a'); pendingA = false; sb.append(vowels[ch]) }
                ch == '़' -> {}
                else -> { if (pendingA) sb.append('a'); pendingA = false; sb.append(ch) }
            }
            i++
        }
        // final inherent "a" is silent in Hindi (dil, pyar); long endings read short (mera, teri)
        var r = sb.toString()
        if (r.endsWith("aa") && r.length > 3) r = r.dropLast(1)
        else if (r.endsWith("ee") && r.length > 3) r = r.dropLast(2) + "i"
        else if (r.endsWith("oo") && r.length > 3) r = r.dropLast(1)
        return r
    }
}
