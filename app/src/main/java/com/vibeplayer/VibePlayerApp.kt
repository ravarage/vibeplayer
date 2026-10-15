package com.vibeplayer

import android.app.Application
import com.vibeplayer.data.prefs.AppSettingsRepository
import com.vibeplayer.diagnostics.ExitDiagnostics
import com.vibeplayer.profiling.ProfilingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class VibePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            ExitDiagnostics.checkAndLog(this@VibePlayerApp)
        }
        CoroutineScope(Dispatchers.IO).launch {
            // Only this setting matters: re-registering triggers on every unrelated settings write is waste.
            AppSettingsRepository(this@VibePlayerApp).appSettingsFlow
                .map { it.collectSystemProfiles }
                .distinctUntilChanged()
                .collect { enabled ->
                    ProfilingController.setCollectSystemProfilesEnabled(this@VibePlayerApp, enabled)
                }
        }
    }
}
