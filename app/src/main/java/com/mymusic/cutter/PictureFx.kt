package com.mymusic.cutter

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.RgbMatrix
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picture settings applied by the player itself on the graphics chip, so the picture can keep
 * using the direct (true-colour) display path. Values are read every frame, so moving a slider
 * changes the picture immediately without restarting anything.
 */
@UnstableApi
object PictureFx {
    @Volatile var brightness = 100f   // %
    @Volatile var contrast = 100f     // %
    @Volatile var saturation = 100f   // %
    @Volatile var hue = 0f            // degrees
    @Volatile var sharpness = 0f      // 0..100

    fun isNeutral() = brightness == 100f && contrast == 100f && saturation == 100f && hue == 0f && sharpness == 0f

    // Each step is out = A*in + a (3x3 rows + offset), applied in the CSS filter order:
    // brightness, contrast, saturation, hue.
    private fun step(M: FloatArray, t: FloatArray, A: FloatArray, a: FloatArray): Pair<FloatArray, FloatArray> {
        val m2 = FloatArray(9)
        val t2 = FloatArray(3)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += A[i * 3 + k] * M[k * 3 + j]
                m2[i * 3 + j] = s
            }
            var s2 = a[i]
            for (k in 0 until 3) s2 += A[i * 3 + k] * t[k]
            t2[i] = s2
        }
        return Pair(m2, t2)
    }

    val colour = object : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val br = brightness / 100f
            val c = contrast / 100f
            val s = saturation / 100f
            val ang = Math.toRadians(hue.toDouble())
            val co = cos(ang).toFloat()
            val si = sin(ang).toFloat()
            val zero = floatArrayOf(0f, 0f, 0f)
            var r = Pair(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), zero)
            r = step(r.first, r.second, floatArrayOf(br, 0f, 0f, 0f, br, 0f, 0f, 0f, br), zero)
            val co5 = 0.5f * (1 - c)
            r = step(r.first, r.second, floatArrayOf(c, 0f, 0f, 0f, c, 0f, 0f, 0f, c), floatArrayOf(co5, co5, co5))
            r = step(r.first, r.second, floatArrayOf(
                0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
                0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
                0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s), zero)
            r = step(r.first, r.second, floatArrayOf(
                0.213f + co * 0.787f - si * 0.213f, 0.715f - co * 0.715f - si * 0.715f, 0.072f - co * 0.072f + si * 0.928f,
                0.213f - co * 0.213f + si * 0.143f, 0.715f + co * 0.285f + si * 0.140f, 0.072f - co * 0.072f - si * 0.283f,
                0.213f - co * 0.213f - si * 0.787f, 0.715f - co * 0.715f + si * 0.715f, 0.072f + co * 0.928f + si * 0.072f), zero)
            val m = r.first
            val t = r.second
            // 4x4 column-major for OpenGL; the offset sits in the last column (alpha = 1)
            return floatArrayOf(
                m[0], m[3], m[6], 0f,
                m[1], m[4], m[7], 0f,
                m[2], m[5], m[8], 0f,
                t[0], t[1], t[2], 1f)
        }
    }

    val sharpen = object : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = SharpenProgram(useHdr)
    }

    private const val VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}"""

    // Unsharp mask: adds back the difference between each pixel and its four neighbours.
    private const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uAmount;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  if (uAmount <= 0.0) { gl_FragColor = c; return; }
  vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb;
  vec3 s = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
  vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb;
  vec3 w = texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb;
  vec3 edge = 4.0 * c.rgb - n - s - e - w;
  gl_FragColor = vec4(clamp(c.rgb + uAmount * edge, 0.0, 1.0), c.a);
}"""

    private class SharpenProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = GlProgram(VERTEX, FRAGMENT)
        private var w = 1
        private var h = 1

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            w = inputWidth
            h = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uTexelSize", floatArrayOf(1f / w, 1f / h))
                program.setFloatUniform("uAmount", sharpness / 100f * 1.2f)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { program.delete() } catch (_: Exception) {}
        }
    }
}
