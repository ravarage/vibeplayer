package com.vibeplayer.playback

/** A running sleep timer: pause at [endsAtElapsedMs] (elapsedRealtime), or when the current file ends. */
data class SleepTimer(val endsAtElapsedMs: Long? = null, val atEndOfFile: Boolean = false) {

    companion object {
        val MINUTE_CHOICES = listOf(15, 30, 45, 60, 90)

        fun inMinutes(minutes: Int, nowElapsedMs: Long) = SleepTimer(endsAtElapsedMs = nowElapsedMs + minutes * 60_000L)

        fun endOfFile() = SleepTimer(atEndOfFile = true)

        /** "Off", "At the end of this video", or the time left ("23 min", "0:45" in the last minute). */
        fun label(timer: SleepTimer?, nowElapsedMs: Long): String {
            if (timer == null) return "Off"
            if (timer.atEndOfFile) return "At the end of this video"
            val left = ((timer.endsAtElapsedMs ?: nowElapsedMs) - nowElapsedMs).coerceAtLeast(0L)
            return if (left >= 60_000L) {
                "Pauses in ${(left + 59_999L) / 60_000L} min"
            } else {
                val s = (left + 999L) / 1000L
                "Pauses in 0:${s.toString().padStart(2, '0')}"
            }
        }
    }
}
