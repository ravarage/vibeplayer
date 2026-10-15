package com.vibeplayer.player

/**
 * Pure decisions for the ADPF performance hint session (P10-06). The Android side lives in the app's
 * `PerformanceHintController`; everything here is plain logic so it can be unit tested.
 *
 * Thread names (checked in the mpv 0.41, dav1d and FFmpeg sources of this build, 2026-10-15; Linux keeps
 * at most 15 characters in /proc/self/task/<tid>/comm):
 * - mpv (`mp_thread_set_name`, HAVE_GLIBC_THREAD_NAME=1 on Android): `vo` (video output and rendering),
 *   `core` (playloop), `demux`, `dec/video`, `dec/audio`, `ao/audiotrack`, `worker` (thread pool),
 *   `opener`, `input`, `log`. There is no `mpv/` prefix.
 * - dav1d: `dav1d-worker` (all decode workers, `thread_task.c`).
 * - FFmpeg frame threads: `av:<codec>:df<n>` (software HEVC/H.264 etc., named through prctl).
 * - The project's JNI event thread is not named.
 *
 * Matched: exactly `vo`, `core`, `demux`, `dec/video`; by prefix `dav1d-worker` and `av:`. Everything
 * else stays out on purpose: audio threads (`dec/audio`, `ao/...`) wake rarely and run on their own
 * clock, `worker`/`opener`/`input`/`log` are not per-frame work, and a loose "worker" match could catch
 * truncated names of unrelated JVM threads.
 */
object AdpfHintPolicy {
    private val EXACT_NAMES = setOf("vo", "core", "demux", "dec/video")
    private val PREFIXES = listOf("dav1d-worker", "av:")

    const val FALLBACK_FPS = 60.0
    /** Factor reported for a second in which frames were dropped (the work did not fit the budget). */
    const val OVERRUN_FACTOR = 1.5

    /** True when a thread with this /proc comm name does per-frame playback work. */
    fun isPlaybackThread(comm: String): Boolean {
        val name = comm.trim()
        if (name.isEmpty()) return false
        return name in EXACT_NAMES || PREFIXES.any { name.startsWith(it) }
    }

    /** The tids among (tid, comm) pairs that belong in the hint session, sorted for stable comparison. */
    fun selectTids(threads: List<Pair<Int, String>>): IntArray =
        threads.filter { it.first > 0 && isPlaybackThread(it.second) }.map { it.first }.distinct().sorted().toIntArray()

    /** Per-frame budget in nanoseconds: 1e9 / fps; an unknown or invalid fps counts as [FALLBACK_FPS]. Always > 0. */
    fun targetDurationNanos(fps: Double?): Long {
        val f = if (SurfaceFrameRateHelper.isValidFps(fps)) fps!! else FALLBACK_FPS
        return (1_000_000_000.0 / f).toLong().coerceAtLeast(1L)
    }

    /**
     * Prefer power efficiency only when the frames come from the hardware decoder straight to the GPU
     * (zero-copy `mediacodec`: the HEVC and VP9 rows of the format table). Software decode (AV1 with
     * dav1d, Hi10P, a hardware file that fell back) and copy-back need the CPU and get false.
     */
    fun preferPowerEfficiency(hwdecCurrent: String?): Boolean = hwdecCurrent == DecodeMode.MEDIACODEC_ZERO_COPY.hwdec

    /**
     * Work duration to report for the last second. mpv exposes no cheap per-frame render time, so the
     * report is derived from the drop counters (`frame-drop-count` + `decoder-frame-drop-count`): the
     * target when no drops were added, [OVERRUN_FACTOR] x target when the count rose. A count lower than
     * before is a reset (new file or handle) and counts as no drops. Null previous count (first sample)
     * also reports the target.
     */
    fun reportedDurationNanos(targetNanos: Long, previousDrops: Long?, currentDrops: Long): Long {
        val target = targetNanos.coerceAtLeast(1L)
        val increased = previousDrops != null && currentDrops > previousDrops
        return if (increased) (target * OVERRUN_FACTOR).toLong() else target
    }

    /**
     * When to re-read /proc after a file load: the decoder and dav1d threads appear while the file
     * opens and the first frames decode, so look at seconds 0, 1, 2 and 5 after the load, then every
     * [PERIODIC_RESCAN_SECONDS] as a backstop (e.g. a decoder re-init after a fallback).
     */
    fun shouldRescan(secondsSinceLoad: Int): Boolean =
        secondsSinceLoad in EARLY_RESCAN_SECONDS || (secondsSinceLoad > 0 && secondsSinceLoad % PERIODIC_RESCAN_SECONDS == 0)

    private val EARLY_RESCAN_SECONDS = setOf(0, 1, 2, 5)
    const val PERIODIC_RESCAN_SECONDS = 10

    /** Overlay text. */
    fun overlayText(enabled: Boolean, supported: Boolean, threadCount: Int?): String = when {
        !enabled -> "off"
        !supported -> "unsupported"
        threadCount == null -> "idle"
        else -> "on ($threadCount threads)"
    }
}
