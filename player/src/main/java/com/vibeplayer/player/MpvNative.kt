package com.vibeplayer.player

import android.view.Surface

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
    external fun getPropertyString(handle: Long, name: String): String?
    external fun setPropertyString(handle: Long, name: String, value: String): Int
    external fun attachSurface(handle: Long, surface: Surface)
    external fun detachSurface(handle: Long)
    external fun destroy(handle: Long)
}
