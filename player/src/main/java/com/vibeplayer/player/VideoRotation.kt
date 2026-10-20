package com.vibeplayer.player

/** The size a video is shown at, after its container rotation (mpv `video-params/rotate`, degrees). */
object VideoRotation {
    fun displaySize(width: Int, height: Int, rotate: Int): Pair<Int, Int> =
        if (Math.floorMod(rotate, 180) == 90) height to width else width to height
}
