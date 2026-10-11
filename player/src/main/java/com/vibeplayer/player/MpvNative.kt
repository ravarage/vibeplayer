package com.vibeplayer.player

object MpvNative {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("swscale")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("avfilter")
        System.loadLibrary("mpv")
        System.loadLibrary("player_jni")
    }

    external fun create(): Long
    external fun setOptionString(handle: Long, name: String, value: String): Int
    external fun initialize(handle: Long): Int
    external fun command(handle: Long, args: Array<String>): Int
    external fun destroy(handle: Long)
}
