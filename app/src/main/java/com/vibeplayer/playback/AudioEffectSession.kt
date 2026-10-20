package com.vibeplayer.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/**
 * Tells equalizer and effect apps (ViPER4Android, Wavelet, the system equalizer) which audio session
 * the player uses, so they can attach their effects to it. mpv plays through an AudioTrack created
 * with this session id (`audiotrack-session-id`); without the broadcasts those apps never see it.
 */
class AudioEffectSession(private val context: Context, val sessionId: Int) {

    private var open = false

    /** Announces the session (once until [close]). */
    fun open() {
        if (open || sessionId <= 0) return
        send(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        open = true
    }

    fun close() {
        if (!open) return
        send(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        open = false
    }

    private fun send(action: String) {
        try {
            context.sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            )
            Log.i("VP", "Audio effect session $sessionId: ${action.substringAfterLast('.')}")
        } catch (e: Exception) {
            Log.w("VP", "Audio effect broadcast failed: ${e.message}")
        }
    }
}
