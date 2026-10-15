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

    /** First look at a load that has not reached loadfile yet (queued behind the command thread). */
    const val LOAD_START_CHECK_MS = 1_500L

    /** How often a pending load is looked at again. */
    const val LOAD_RECHECK_MS = 500L

    /**
     * A single command-thread task running this long is stuck in a blocked mpv call: no healthy
     * task takes that long (the slowest is a reconfigure, which destroys and creates a handle).
     */
    const val STUCK_COMMAND_MS = 3_000L

    enum class PendingLoadAction { NONE, RECHECK, ABANDON }

    data class PendingLoad(
        val released: Boolean,
        /** The load being watched is still the newest one. */
        val sameLoad: Boolean,
        /** Its loadfile command has been issued. */
        val issued: Boolean,
        /** The stuck handle was already abandoned for this load. */
        val alreadyAbandoned: Boolean,
        /** How long the task running on the command thread has been running (0 when idle). */
        val commandBusyMs: Long,
        /** Time since the load was queued. */
        val waitedMs: Long
    )

    /**
     * A new open (next/previous, auto-advance, a picked file) during a decoder stall queues behind
     * the blocked command thread and never reaches file-loaded, so the file-loaded watchdog cannot
     * help: abandon the stuck handle the same way and load the new file on a fresh one.
     */
    fun onPendingLoad(p: PendingLoad): PendingLoadAction = when {
        p.released || !p.sameLoad || p.issued || p.alreadyAbandoned -> PendingLoadAction.NONE
        p.commandBusyMs >= STUCK_COMMAND_MS -> PendingLoadAction.ABANDON
        p.waitedMs > GIVE_UP_MS -> PendingLoadAction.NONE
        else -> PendingLoadAction.RECHECK
    }

    /**
     * Position to save or carry over when mpv cannot be asked (bounded read timed out). Before the
     * current load has started playing, the last known position is the previous file's or 0 (load
     * resets it), so the position the load started at is the right one: saving 0 would overwrite
     * the resume point of a file that stalled while opening.
     */
    fun fallbackPositionMs(playbackStarted: Boolean, knownMs: Long, loadStartMs: Long?): Long =
        if (!playbackStarted && loadStartMs != null) loadStartMs else knownMs

    /**
     * After a probe: recover only when the core did not answer in time and the command thread has
     * been inside one task for [STUCK_COMMAND_MS]. A probe can also time out queued behind a healthy
     * task that is merely slow, such as the Vulkan-to-GLES reconfigure that starts 3.2 s after
     * file-loaded of a file whose Vulkan VO failed (no playback-restart either): look again then.
     * In a real stall the thread has been blocked since file-loaded, so recovery is not delayed.
     */
    fun shouldRecover(probeAnswered: Boolean, commandBusyMs: Long): Boolean =
        !probeAnswered && commandBusyMs >= STUCK_COMMAND_MS
}
