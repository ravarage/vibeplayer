package com.vibeplayer.playback

import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.vibeplayer.player.PlayerEngine
import com.vibeplayer.player.ShaderPreset
import com.vibeplayer.player.ThermalDecisionHelper

/**
 * Manages thermal status listening and quality adaptation (P10-02).
 *
 * Registers [PowerManager.OnThermalStatusChangedListener] while playing and unregisters on stop.
 * Exposes the thermal status to [PlayerEngine] for the stats overlay.
 * Steps down quality at THERMAL_STATUS_MODERATE or higher, without oscillation or stepping back up.
 */
class ThermalManager(
    private val context: Context,
    private val playerEngine: PlayerEngine
) {
    private val powerManager: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var isRegistered = false

    /** Highest thermal status that triggered a step-down in the current file. */
    @Volatile
    var highestTriggeredStatus: Int? = null
        private set

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        Log.i("VP", "onThermalStatusChanged: status=$status (${ThermalDecisionHelper.statusToString(status)})")
        handleThermalStatus(status)
    }

    fun onPlaybackStarted() {
        if (!isRegistered && powerManager != null) {
            try {
                powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermalListener)
                isRegistered = true
                Log.i("VP", "ThermalManager: registered thermal listener")
                handleThermalStatus(powerManager.currentThermalStatus)
            } catch (e: Exception) {
                Log.w("VP", "Failed to addThermalStatusListener: ${e.message}")
            }
        }
    }

    fun onPlaybackStopped() {
        if (isRegistered && powerManager != null) {
            try {
                powerManager.removeThermalStatusListener(thermalListener)
                Log.i("VP", "ThermalManager: unregistered thermal listener")
            } catch (e: Exception) {
                Log.w("VP", "Failed to removeThermalStatusListener: ${e.message}")
            }
            isRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fileGeneration = 0

    /**
     * Called before a new file is handed to the engine. Resets the step-down budget and re-checks the
     * status once the new file plays: checking right away would act on the outgoing file (a vo step-down
     * reconfigures and reloads whatever is loaded) and read its paused/eof state.
     */
    fun onFileChanged(newPath: String?) {
        highestTriggeredStatus = null
        val generation = ++fileGeneration
        mainHandler.postDelayed({
            if (generation == fileGeneration && isRegistered && powerManager != null) {
                handleThermalStatus(powerManager.currentThermalStatus)
            }
        }, FILE_SETTLE_MS)
    }

    companion object {
        private const val FILE_SETTLE_MS = 3000L
    }

    fun handleThermalStatus(status: Int) {
        val statusStr = ThermalDecisionHelper.statusToString(status)
        playerEngine.setThermalStatus(statusStr)

        val isPlaying = !playerEngine.state.value.paused && !playerEngine.state.value.eof
        if (!isPlaying) return

        val currentShader = playerEngine.currentShaderPreset
        val currentVo = playerEngine.latestRequestedConfig.vo
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = status,
            highestTriggeredStatus = highestTriggeredStatus,
            currentShaderPreset = currentShader,
            currentVo = currentVo
        )

        when (action) {
            is ThermalDecisionHelper.Action.StepDown -> {
                highestTriggeredStatus = status
                Log.w("VP", "Thermal adaptation: stepped down quality at $statusStr (dropShaders=${action.dropShaders}, stepDownVo=${action.stepDownVoToGpu})")
                if (action.dropShaders) {
                    playerEngine.setShaderPreset(ShaderPreset.NONE)
                }
                if (action.stepDownVoToGpu) {
                    val newConfig = playerEngine.latestRequestedConfig.copy(voOverride = "gpu")
                    playerEngine.reconfigure(newConfig)
                }
                playerEngine.showOnScreenMessage(action.message)
                try {
                    Toast.makeText(context, action.message, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            is ThermalDecisionHelper.Action.None -> {}
        }
    }
}
