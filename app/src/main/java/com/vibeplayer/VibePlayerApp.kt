package com.vibeplayer

import android.app.Application
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.diagnostics.ExitDiagnostics
import com.vibeplayer.profiling.ProfilingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VibePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            ExitDiagnostics.checkAndLog(this@VibePlayerApp)
        }
        CoroutineScope(Dispatchers.IO).launch {
            AppSettingsRepository(this@VibePlayerApp).appSettingsFlow.collect { settings ->
                ProfilingController.setCollectSystemProfilesEnabled(this@VibePlayerApp, settings.collectSystemProfiles)
            }
        }
    }
}
