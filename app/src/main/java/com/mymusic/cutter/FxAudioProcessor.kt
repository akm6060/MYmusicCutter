package com.mymusic.cutter

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends every decoded audio block through the C++ sound engine inside the player's own
 * audio pipeline (before Android's audio output), so effects never lag behind the music.
 * Output is always stereo 16-bit; mono is spread to both sides and 5.1 is folded to stereo.
 */
@UnstableApi
class FxAudioProcessor : BaseAudioProcessor() {
    private var inChannels = 2
    private var isFloat = false
    private var scratch: ByteBuffer? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inChannels = inputAudioFormat.channelCount
        isFloat = enc == C.ENCODING_PCM_FLOAT
        FxNative.configure(inputAudioFormat.sampleRate, inChannels)
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val bytes = inputBuffer.limit() - pos
        val frameBytes = (if (isFloat) 4 else 2) * inChannels
        val frames = bytes / frameBytes
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val out = replaceOutputBuffer(frames * 4)
        if (inputBuffer.isDirect) {
            FxNative.process(inputBuffer, pos, frames, isFloat, out)
        } else {
            var s = scratch
            if (s == null || s.capacity() < frames * frameBytes) {
                s = ByteBuffer.allocateDirect(frames * frameBytes).order(ByteOrder.nativeOrder())
                scratch = s
            }
            s!!.clear()
            val dup = inputBuffer.duplicate()
            dup.limit(pos + frames * frameBytes)
            s.put(dup)
            FxNative.process(s, 0, frames, isFloat, out)
        }
        inputBuffer.position(pos + frames * frameBytes)
        out.position(frames * 4)
        out.flip()
    }

    override fun onFlush() {
        FxNative.reset()
    }

    override fun onReset() {
        FxNative.reset()
    }
}
