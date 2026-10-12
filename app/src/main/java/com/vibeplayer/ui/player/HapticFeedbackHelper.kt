package com.vibeplayer.ui.player

import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

object HapticFeedbackHelper {
    fun triggerTick(view: View, reason: String) {
        Log.i("VP", "Haptic tick: $reason")
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
