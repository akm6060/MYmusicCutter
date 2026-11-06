package com.mymusic.cutter

/** The C++ sound engine (app/src/main/cpp/mmfx.cpp). */
object FxNative {
    init {
        System.loadLibrary("mmfx")
    }

    @JvmStatic external fun configure(sampleRate: Int, channels: Int)
    @JvmStatic external fun reset()
    @JvmStatic external fun setParams(params: FloatArray)
    @JvmStatic external fun process(input: java.nio.ByteBuffer, offset: Int, frames: Int, isFloat: Boolean, output: java.nio.ByteBuffer)
    @JvmStatic external fun analyse(on: Boolean)
    @JvmStatic external fun analysisResult(): FloatArray
    @JvmStatic external fun meter(): FloatArray
}
