package com.vibeplayer.ui.player

/** P3-09: pure decision logic for "press and hold on the right half for fast playback". */
object HoldGesture {
    enum class Outcome {
        /** Finger still down, within slop, before the long-press timeout: nothing decided yet. */
        PENDING,
        /** Long press on the right half without leaving the slop circle: start the hold. */
        HOLD,
        /** Moved beyond slop before the timeout: the existing swipe (volume / brightness / seek) wins. */
        DRAG
    }

    /**
     * @param downX x of the first touch, [width] the layer width, [elapsedMs] time since the down,
     * [movedPx] straight-line distance from the down position.
     */
    fun classify(
        downX: Float,
        width: Float,
        elapsedMs: Long,
        movedPx: Float,
        touchSlopPx: Float,
        longPressMs: Long,
        holdAllowed: Boolean = true
    ): Outcome {
        if (movedPx > touchSlopPx) return Outcome.DRAG
        val rightHalf = downX >= width / 2f
        return if (holdAllowed && rightHalf && elapsedMs >= longPressMs) Outcome.HOLD else Outcome.PENDING
    }

    /** Hold speeds offered in settings; the first match of [normalize] wins, default 2x. */
    val SPEEDS = listOf(1.5, 2.0, 3.0, 4.0)
    const val DEFAULT_SPEED = 2.0
    fun normalize(speed: Double): Double = SPEEDS.firstOrNull { it == speed } ?: DEFAULT_SPEED

    fun label(speed: Double): String =
        if (speed % 1.0 == 0.0) "${speed.toInt()}x" else "${speed}x"
}

/** Starts and ends the temporary speed; ending is idempotent and always clears the override. */
class HoldSpeedController(private val setOverride: (Double?) -> Unit) {
    var active: Double? = null
        private set

    fun start(speed: Double) {
        if (active != null) return
        active = speed
        setOverride(speed)
    }

    fun end() {
        if (active == null) return
        active = null
        setOverride(null)
    }
}
