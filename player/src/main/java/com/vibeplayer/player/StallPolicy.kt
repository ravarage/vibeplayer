package com.vibeplayer.player

/**
 * B-08 safety net. A MediaCodec decoder sometimes stops after its first frame; the mpv core then
 * never leaves the decoder and every synchronous mpv call blocks. Pure decision logic, so it can
 * be unit tested; [PlayerEngine] supplies the facts and carries out the result.
 */
object StallPolicy {
    /** Time after `file-loaded` without `playback-restart` before the engine is checked. */
    const val FIRST_CHECK_MS = 4_000L

    /** A core that still answers is only slow (4K software decode, long seek): look again later. */
    const val RECHECK_MS = 2_000L

    /** How long the liveness probe on the command thread may take before the core counts as stuck. */
    const val PROBE_TIMEOUT_MS = 1_000L

    /** Stop watching a load that is still alive but has not started after this long. */
    const val GIVE_UP_MS = 30_000L

    enum class Action { NONE, PROBE }

    data class Facts(
        val released: Boolean,
        /** The load being watched is still the current one. */
        val sameLoad: Boolean,
        val playbackRestartSeen: Boolean,
        val surfaceAttached: Boolean,
        /**
         * This file was already reloaded with software decoding after a stall. The decoder can
         * stall before mpv reports `hwdec-current`, so that property cannot be the condition.
         */
        val alreadyRecovered: Boolean,
        val elapsedSinceFileLoadedMs: Long,
        /** Value of mpv's `vid` property (e.g. "1", "auto", "no"), or null if unspecified. */
        val vid: String? = null
    )

    /** Whether the engine should be probed for a stuck core now. */
    fun onTimer(facts: Facts): Action = when {
        facts.released || !facts.sameLoad || facts.playbackRestartSeen -> Action.NONE
        !facts.surfaceAttached -> Action.NONE
        facts.vid == "no" -> Action.NONE
        // One software reload per file. A slow but healthy load is told apart by the probe.
        facts.alreadyRecovered -> Action.NONE
        facts.elapsedSinceFileLoadedMs < FIRST_CHECK_MS -> Action.NONE
        facts.elapsedSinceFileLoadedMs > GIVE_UP_MS -> Action.NONE
        else -> Action.PROBE
    }

    /** Whether the file [identity] is to be decoded in software because it stalled before. */
    fun softwareAfterStall(recoveryIdentity: String?, identity: String?): Boolean =
        recoveryIdentity != null && recoveryIdentity == identity

    /**
     * The software-after-stall flag once [loadIdentity] loads: kept for the same file (any reload,
     * reconfigure or fallback of it), dropped for another file, which gets hardware decoding and a
     * fresh one-reload budget.
     */
    fun recoveryAfterLoad(recoveryIdentity: String?, loadIdentity: String): String? =
        recoveryIdentity?.takeIf { it == loadIdentity }

    /** After a probe: recover only when the core did not answer in time. */
    fun shouldRecover(probeAnswered: Boolean): Boolean = !probeAnswered
}
