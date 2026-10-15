package com.vibeplayer

import android.app.Application
import com.vibeplayer.diagnostics.ExitDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VibePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            ExitDiagnostics.checkAndLog(this@VibePlayerApp)
        }
    }
}
