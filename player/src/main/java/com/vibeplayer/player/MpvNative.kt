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

    interface EventCallback {
        fun onPropertyChange(name: String, value: Any?)
        fun onEvent(id: Int)
    }

    external fun create(): Long
    external fun setEventCallback(handle: Long, callback: EventCallback?)
    external fun setOptionString(handle: Long, name: String, value: String): Int
    external fun initialize(handle: Long): Int
    external fun command(handle: Long, args: Array<String>): Int
    external fun getPropertyString(handle: Long, name: String): String?
    external fun setPropertyString(handle: Long, name: String, value: String): Int
    external fun getPropertyBoolean(handle: Long, name: String): Boolean?
    external fun setPropertyBoolean(handle: Long, name: String, value: Boolean): Int
    external fun getPropertyLong(handle: Long, name: String): Long?
    external fun setPropertyLong(handle: Long, name: String, value: Long): Int
    external fun getPropertyDouble(handle: Long, name: String): Double?
    external fun setPropertyDouble(handle: Long, name: String, value: Double): Int
    external fun observeProperty(handle: Long, replyUserdata: Long, name: String, format: Int): Int
    external fun unobserveProperty(handle: Long, replyUserdata: Long): Int
    external fun attachSurface(handle: Long, surface: Surface)
    external fun detachSurface(handle: Long)
    external fun setSurfaceSize(handle: Long, width: Int, height: Int)
    external fun destroy(handle: Long)
}

object MpvFormat {
    const val NONE: Int = 0
    const val STRING: Int = 1
    const val OSD_STRING: Int = 2
    const val FLAG: Int = 3
    const val INT64: Int = 4
    const val DOUBLE: Int = 5
    const val NODE: Int = 6
    const val NODE_ARRAY: Int = 7
    const val NODE_MAP: Int = 8
    const val BYTE_ARRAY: Int = 9
}

object MpvEvent {
    const val NONE: Int = 0
    const val SHUTDOWN: Int = 1
    const val LOG_MESSAGE: Int = 2
    const val GET_PROPERTY_REPLY: Int = 3
    const val SET_PROPERTY_REPLY: Int = 4
    const val COMMAND_REPLY: Int = 5
    const val START_FILE: Int = 6
    const val END_FILE: Int = 7
    const val FILE_LOADED: Int = 8
    const val TRACKS_CHANGED: Int = 9
    const val TRACK_SWITCHED: Int = 10
    const val IDLE: Int = 11
    const val PAUSE: Int = 12
    const val UNPAUSE: Int = 13
    const val TICK: Int = 14
    const val SCRIPT_INPUT_DISPATCH: Int = 15
    const val CLIENT_MESSAGE: Int = 16
    const val VIDEO_RECONFIG: Int = 17
    const val AUDIO_RECONFIG: Int = 18
    const val METADATA_UPDATE: Int = 19
    const val SEEK: Int = 20
    const val PLAYBACK_RESTART: Int = 21
    const val PROPERTY_CHANGE: Int = 22
    const val CHAPTER_CHANGE: Int = 23
    const val QUEUE_OVERFLOW: Int = 24
    const val HOOK: Int = 25
}
