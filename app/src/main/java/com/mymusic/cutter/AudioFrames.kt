package com.mymusic.cutter

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/*
 * Byte-level copying for MP3, AAC (ADTS) and WAV: parts are cut and files are joined by copying
 * whole audio frames, never re-encoding, so the result is the same format and saving takes only
 * as long as copying. Damaged bytes between frames are skipped. No Android code here, so this
 * file is tested on its own before it goes into the app.
 */

/** Time ranges in microseconds: [start, end), end may be Long.MAX_VALUE (= to the end). */
object Ranges {
    /** Sorts and merges overlapping ranges. */
    fun normalize(input: List<LongArray>): List<LongArray> {
        val r = input.filter { it[1] > it[0] }.sortedBy { it[0] }
        val out = ArrayList<LongArray>()
        for (x in r) {
            val last = out.lastOrNull()
            if (last != null && x[0] <= last[1]) last[1] = maxOf(last[1], x[1]) else out.add(longArrayOf(x[0], x[1]))
        }
        return out
    }

    /** Everything that is NOT in the given ranges (used by "Erase parts"). */
    fun complement(input: List<LongArray>): List<LongArray> {
        val r = normalize(input)
        val out = ArrayList<LongArray>()
        var pos = 0L
        for (x in r) {
            if (x[0] > pos) out.add(longArrayOf(pos, x[0]))
            pos = maxOf(pos, x[1])
        }
        if (pos != Long.MAX_VALUE) out.add(longArrayOf(pos, Long.MAX_VALUE))
        return out
    }

    fun keeps(ranges: List<LongArray>?, startUs: Double, endUs: Double): Boolean =
        ranges == null || ranges.any { r -> endUs > r[0] && (r[1] == Long.MAX_VALUE || startUs < r[1]) }

    fun lastEnd(ranges: List<LongArray>?): Long =
        if (ranges == null || ranges.isEmpty()) Long.MAX_VALUE else ranges.maxOf { it[1] }
}

internal fun readFullyIn(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
        val n = input.read(buf, off + total, len - total)
        if (n <= 0) break
        total += n
    }
    return total
}

internal fun skipIn(input: InputStream, count: Long): Long {
    var done = 0L
    val scratch = ByteArray(8192)
    while (done < count) {
        val s = input.skip(count - done)
        if (s > 0) { done += s; continue }
        val n = input.read(scratch, 0, minOf(scratch.size.toLong(), count - done).toInt())
        if (n <= 0) break
        done += n
    }
    return done
}

/** Size of an ID3v2 tag starting at head (10 bytes), or -1 if there is none. */
internal fun id3Size(head: ByteArray): Int {
    if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return -1
    val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
        ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
    val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
    return 10 + size + footer
}

// ------------------------------------------------------------------------------------- MP3

class Mp3Header(
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
                when (layer) { 1 -> BR_V1_L1[brIdx]; 2 -> BR_V1_L2[brIdx]; else -> BR_V1_L3[brIdx] }
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

object Mp3Tool {
    class Result {
        var frames = 0
        var kept = 0
        var xing: ByteArray? = null
        var xingPos = -1L
        var tagOffset = 0
        var audioBytes = 0L
        var offsets = LongArray(4096)
    }

    /**
     * Copies MP3 frames from one or more inputs into [out].
     * With [ranges] (one input): keeps only frames overlapping the ranges (cutting).
     * Without ranges: keeps everything (joining); every input must have the same MPEG version,
     * layer and sample rate. The ID3 tag and the Xing/Info header of the first input are kept.
     */
    fun copy(
        inputs: List<() -> InputStream>, out: OutputStream, ranges: List<LongArray>?,
        totalBytes: Long, progress: (Double) -> Unit
    ): Result {
        val res = Result()
        var written = 0L
        var consumed = 0L
        var lockVersion = -1
        var lockLayer = -1
        var lockRate = -1
        val stopAt = Ranges.lastEnd(ranges)
        val hdr = ByteArray(4)
        val frame = ByteArray(8192)
        for ((fileIndex, open) in inputs.withIndex()) {
            var framesThisFile = 0
            open().use { raw ->
                val input = BufferedInputStream(raw, 1 shl 16)
                val head = ByteArray(10)
                input.mark(16)
                val n = readFullyIn(input, head, 0, 10)
                val tag = if (n == 10) id3Size(head) else -1
                if (tag > 0) {
                    if (fileIndex == 0) {
                        out.write(head)
                        val rest = ByteArray(8192)
                        var left = (tag - 10).toLong()
                        while (left > 0) {
                            val r = input.read(rest, 0, minOf(rest.size.toLong(), left).toInt())
                            if (r <= 0) break
                            out.write(rest, 0, r)
                            left -= r
                        }
                        written += tag
                    } else {
                        skipIn(input, (tag - 10).toLong())
                    }
                    consumed += tag
                } else {
                    input.reset()
                }
                var timeUs = 0.0
                var junk = 0L
                var firstFrame = true
                while (true) {
                    input.mark(8)
                    if (readFullyIn(input, hdr, 0, 4) < 4) break
                    val h = Mp3Header.parse(hdr, 0)
                    val ok = h != null && (lockVersion < 0 ||
                        (h.version == lockVersion && h.layer == lockLayer && h.sampleRate == lockRate))
                    if (h == null || !ok) {
                        input.reset()
                        if (input.skip(1) < 1) break
                        junk++
                        consumed++
                        if (framesThisFile > 0 && junk > 1_000_000) break
                        if (framesThisFile == 0 && junk > 4_000_000) break
                        continue
                    }
                    junk = 0
                    System.arraycopy(hdr, 0, frame, 0, 4)
                    if (readFullyIn(input, frame, 4, h.frameLength - 4) < h.frameLength - 4) break
                    consumed += h.frameLength
                    if (totalBytes > 0) progress(consumed.toDouble() / totalBytes)
                    if (firstFrame) {
                        firstFrame = false
                        if (lockVersion < 0) { lockVersion = h.version; lockLayer = h.layer; lockRate = h.sampleRate }
                        val side = if (h.version == 1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
                        val tagName = if (4 + side + 8 <= h.frameLength) String(frame, 4 + side, 4, Charsets.ISO_8859_1) else ""
                        if (tagName == "Xing" || tagName == "Info") {
                            if (fileIndex == 0) {
                                res.xing = frame.copyOf(h.frameLength)
                                res.tagOffset = 4 + side
                                res.xingPos = written
                                out.write(frame, 0, h.frameLength)
                                written += h.frameLength
                            }
                            continue
                        }
                        if (h.frameLength >= 40 && String(frame, 36, 4, Charsets.ISO_8859_1) == "VBRI") continue
                    }
                    res.frames++
                    framesThisFile++
                    val durUs = h.samples * 1_000_000.0 / h.sampleRate
                    val start = timeUs
                    val end = timeUs + durUs
                    timeUs = end
                    if (ranges != null && start >= stopAt) break
                    if (Ranges.keeps(ranges, start, end)) {
                        if (res.kept == res.offsets.size) res.offsets = res.offsets.copyOf(res.offsets.size * 2)
                        res.offsets[res.kept] = res.audioBytes
                        out.write(frame, 0, h.frameLength)
                        written += h.frameLength
                        res.audioBytes += h.frameLength
                        res.kept++
                    }
                }
            }
            require(framesThisFile > 0) {
                if (fileIndex == 0) "No MP3 audio frames were found in this file."
                else "File ${fileIndex + 1} is a different kind of MP3 (sample rate or version), so it cannot be joined without re-encoding."
            }
        }
        out.flush()
        require(res.kept > 0) { "The selected range contained no audio to save." }
        return res
    }

    /** Updates frame count, byte count, seek table and LAME CRC so players show the new length. */
    fun patchXing(fr: ByteArray, tagOff: Int, kept: Int, audioBytes: Long, offsets: LongArray) {
        fun putBE32(pos: Int, v: Long) {
            if (pos + 4 > fr.size) return
            fr[pos] = (v shr 24).toByte(); fr[pos + 1] = (v shr 16).toByte(); fr[pos + 2] = (v shr 8).toByte(); fr[pos + 3] = v.toByte()
        }
        if (tagOff + 8 > fr.size) return
        val flags = ((fr[tagOff + 4].toInt() and 0xFF) shl 24) or ((fr[tagOff + 5].toInt() and 0xFF) shl 16) or
            ((fr[tagOff + 6].toInt() and 0xFF) shl 8) or (fr[tagOff + 7].toInt() and 0xFF)
        val total = fr.size.toLong() + audioBytes
        var q = tagOff + 8
        if ((flags and 1) != 0) { putBE32(q, kept.toLong()); q += 4 }
        if ((flags and 2) != 0) { putBE32(q, total); q += 4 }
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
                    for (bit in 0 until 8) crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
                fr[190] = (crc shr 8).toByte()
                fr[191] = crc.toByte()
            }
        }
    }
}

// ------------------------------------------------------------------------------------- AAC (ADTS)

object AdtsTool {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** True when the bytes start (after an optional ID3 tag) with an ADTS frame. */
    fun looksLikeAdts(head: ByteArray, n: Int): Boolean {
        var off = 0
        if (n >= 10) { val t = id3Size(head); if (t > 0) off = t }
        if (off + 7 > n) return off > 0   // tag longer than the sample: decide by the file name
        return parse(head, off) != null
    }

    class Frame(val length: Int, val sampleRate: Int, val srIdx: Int, val profile: Int, val channels: Int, val samples: Int)

    fun parse(b: ByteArray, off: Int): Frame? {
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        val b4 = b[off + 4].toInt() and 0xFF
        val b5 = b[off + 5].toInt() and 0xFF
        val b6 = b[off + 6].toInt() and 0xFF
        val srIdx = (b2 shr 2) and 0xF
        if (srIdx >= RATES.size) return null
        val profile = (b2 shr 6) and 3
        val channels = ((b2 and 1) shl 2) or ((b3 shr 6) and 3)
        val len = ((b3 and 3) shl 11) or (b4 shl 3) or ((b5 shr 5) and 7)
        val headerLen = if ((b1 and 1) == 1) 7 else 9
        if (len < headerLen) return null
        val blocks = (b6 and 3) + 1
        return Frame(len, RATES[srIdx], srIdx, profile, channels, 1024 * blocks)
    }

    /** Same idea as Mp3Tool.copy: cut with ranges, or join several inputs. Returns frames kept. */
    fun copy(
        inputs: List<() -> InputStream>, out: OutputStream, ranges: List<LongArray>?,
        totalBytes: Long, progress: (Double) -> Unit
    ): Int {
        var kept = 0
        var consumed = 0L
        var lockSr = -1
        var lockCh = -1
        var lockProfile = -1
        val stopAt = Ranges.lastEnd(ranges)
        val buf = ByteArray(8192)
        for ((fileIndex, open) in inputs.withIndex()) {
            var framesThisFile = 0
            open().use { raw ->
                val input = BufferedInputStream(raw, 1 shl 16)
                val head = ByteArray(10)
                input.mark(16)
                val n = readFullyIn(input, head, 0, 10)
                val tag = if (n == 10) id3Size(head) else -1
                if (tag > 0) {
                    if (fileIndex == 0) {
                        out.write(head)
                        var left = (tag - 10).toLong()
                        while (left > 0) {
                            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            left -= r
                        }
                    } else skipIn(input, (tag - 10).toLong())
                    consumed += tag
                } else input.reset()
                var timeUs = 0.0
                var junk = 0L
                while (true) {
                    input.mark(16)
                    if (readFullyIn(input, buf, 0, 7) < 7) break
                    val f = parse(buf, 0)
                    val ok = f != null && f.length <= buf.size && (lockSr < 0 ||
                        (f.srIdx == lockSr && f.channels == lockCh && f.profile == lockProfile))
                    if (f == null || !ok) {
                        input.reset()
                        if (input.skip(1) < 1) break
                        junk++
                        consumed++
                        if (framesThisFile > 0 && junk > 1_000_000) break
                        if (framesThisFile == 0 && junk > 4_000_000) break
                        continue
                    }
                    junk = 0
                    if (readFullyIn(input, buf, 7, f.length - 7) < f.length - 7) break
                    consumed += f.length
                    if (totalBytes > 0) progress(consumed.toDouble() / totalBytes)
                    if (lockSr < 0) { lockSr = f.srIdx; lockCh = f.channels; lockProfile = f.profile }
                    framesThisFile++
                    val start = timeUs
                    val end = timeUs + f.samples * 1_000_000.0 / f.sampleRate
                    timeUs = end
                    if (ranges != null && start >= stopAt) break
                    if (Ranges.keeps(ranges, start, end)) {
                        out.write(buf, 0, f.length)
                        kept++
                    }
                }
            }
            require(framesThisFile > 0) {
                if (fileIndex == 0) "No AAC audio frames were found in this file."
                else "File ${fileIndex + 1} has a different AAC sample rate or channel count, so it cannot be joined without re-encoding."
            }
        }
        out.flush()
        require(kept > 0) { "The selected range contained no audio to save." }
        return kept
    }
}

// ------------------------------------------------------------------------------------- WAV

object WavTool {
    private fun le32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24))

    private fun le32Bytes(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun writeAll(ch: FileChannel, bytes: ByteArray, length: Int = bytes.size) {
        val bb = ByteBuffer.wrap(bytes, 0, length)
        while (bb.hasRemaining()) ch.write(bb)
    }

    /** Joins WAV files with identical sample format (rate, channels, bits) into [ch]. */
    fun join(inputs: List<() -> InputStream>, ch: FileChannel, totalBytes: Long, progress: (Double) -> Unit) {
        var fmt: ByteArray? = null
        var written = 0L
        var consumed = 0L
        val buf = ByteArray(1 shl 16)
        for ((fileIndex, open) in inputs.withIndex()) {
            open().use { raw ->
                val input = BufferedInputStream(raw, 1 shl 16)
                val riff = ByteArray(12)
                require(readFullyIn(input, riff, 0, 12) == 12 &&
                    String(riff, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                    String(riff, 8, 4, Charsets.ISO_8859_1) == "WAVE") { "File ${fileIndex + 1} is not a readable WAV file." }
                val chunk = ByteArray(8)
                var thisFmt: ByteArray? = null
                while (true) {
                    require(readFullyIn(input, chunk, 0, 8) == 8) { "No audio data was found in WAV file ${fileIndex + 1}." }
                    val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
                    val size = le32(chunk, 4)
                    if (id == "fmt ") {
                        val f = ByteArray(size.toInt())
                        require(readFullyIn(input, f, 0, f.size) == f.size) { "WAV file ${fileIndex + 1} is damaged." }
                        if (size % 2 == 1L) input.skip(1)
                        thisFmt = f
                    } else if (id == "data") {
                        val f = thisFmt ?: throw IllegalStateException("WAV file ${fileIndex + 1} has no format information.")
                        val first = fmt
                        if (first == null) {
                            fmt = f
                            writeAll(ch, ByteArray(12 + 8 + f.size + 8))   // header is written at the end
                        } else {
                            require(f.size >= 16 && first.size >= 16 && f.copyOfRange(0, 16).contentEquals(first.copyOfRange(0, 16))) {
                                "WAV file ${fileIndex + 1} has a different sample rate, channel count or bit depth, so it cannot be joined without re-encoding."
                            }
                        }
                        var remaining = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                        while (remaining > 0) {
                            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (r <= 0) break
                            writeAll(ch, buf, r)
                            remaining -= r
                            written += r
                            consumed += r
                            if (totalBytes > 0) progress(consumed.toDouble() / totalBytes)
                        }
                        break
                    } else {
                        val skip = size + (size % 2)
                        require(skipIn(input, skip) == skip) { "No audio data was found in WAV file ${fileIndex + 1}." }
                    }
                }
            }
        }
        val f = fmt ?: throw IllegalStateException("No audio was found.")
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
    }
}
