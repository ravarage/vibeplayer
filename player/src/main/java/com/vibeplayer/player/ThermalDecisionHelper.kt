package com.vibeplayer.player

/**
 * Pure decision logic for thermal adaptation (P10-02).
 *
 * At THERMAL_STATUS_MODERATE or higher, steps quality down one notch:
 * - Drops active shaders first (e.g. Anime4K, SGSR -> NONE).
 * - If shaders are already NONE and vo is "gpu-next", steps vo down to "gpu".
 * - Never steps back up within the same file.
 * - Prevents oscillation by tracking the highest thermal status that has already triggered a step-down.
 * - Never touches the safety chain (Vulkan -> GLES -> software) ordering.
 */
object ThermalDecisionHelper {
    const val THERMAL_STATUS_NONE = 0
    const val THERMAL_STATUS_LIGHT = 1
    const val THERMAL_STATUS_MODERATE = 2
    const val THERMAL_STATUS_SEVERE = 3
    const val THERMAL_STATUS_CRITICAL = 4
    const val THERMAL_STATUS_EMERGENCY = 5
    const val THERMAL_STATUS_SHUTDOWN = 6

    const val REDUCED_QUALITY_MESSAGE = "Reduced quality (device hot)"

    sealed class Action {
        object None : Action()
        data class StepDown(
            val dropShaders: Boolean = false,
            val stepDownVoToGpu: Boolean = false,
            val message: String = REDUCED_QUALITY_MESSAGE
        ) : Action()
    }

    /**
     * Human-readable string for PowerManager thermal status integer.
     */
    fun statusToString(status: Int): String = when (status) {
        THERMAL_STATUS_NONE -> "none"
        THERMAL_STATUS_LIGHT -> "light"
        THERMAL_STATUS_MODERATE -> "moderate"
        THERMAL_STATUS_SEVERE -> "severe"
        THERMAL_STATUS_CRITICAL -> "critical"
        THERMAL_STATUS_EMERGENCY -> "emergency"
        THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    /**
     * Pure function to decide if quality should be stepped down.
     *
     * @param currentStatus Current thermal status from PowerManager.
     * @param highestTriggeredStatus The highest thermal status that has already triggered a step-down in this file (null if none yet).
     * @param currentShaderPreset Currently active shader preset.
     * @param currentVo Currently active video output ("gpu-next", "gpu", etc.).
     * @return [Action.StepDown] with specific degradation instructions, or [Action.None].
     */
    fun decideAction(
        currentStatus: Int,
        highestTriggeredStatus: Int?,
        currentShaderPreset: ShaderPreset,
        currentVo: String
    ): Action {
        // Only trigger at MODERATE or higher
        if (currentStatus < THERMAL_STATUS_MODERATE) {
            return Action.None
        }

        // Prevent oscillation and repeated step-downs for the same or lower thermal status:
        // Once stepped down at MODERATE, do not step down again while still at MODERATE or if cooling down.
        if (highestTriggeredStatus != null && currentStatus <= highestTriggeredStatus) {
            return Action.None
        }

        // Step down one notch:
        // 1. Drop shaders first if any are active
        if (currentShaderPreset != ShaderPreset.NONE) {
            return Action.StepDown(dropShaders = true, stepDownVoToGpu = false)
        }

        // 2. If no shaders (or already dropped), step down vo from gpu-next to gpu
        if (currentVo.contains("gpu-next")) {
            return Action.StepDown(dropShaders = false, stepDownVoToGpu = true)
        }

        // 3. If already on gpu without shaders, we cannot step down further without violating
        // the safety chain (never touch Vulkan->GLES->software ordering).
        return Action.None
    }
}
