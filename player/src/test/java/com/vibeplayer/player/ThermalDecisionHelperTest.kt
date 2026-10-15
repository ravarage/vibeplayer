package com.vibeplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalDecisionHelperTest {

    @Test
    fun `below moderate triggers no action`() {
        val actionNone = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_NONE,
            highestTriggeredStatus = null,
            currentShaderPreset = ShaderPreset.ANIME4K_A,
            currentVo = "gpu-next"
        )
        assertTrue(actionNone is ThermalDecisionHelper.Action.None)

        val actionLight = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_LIGHT,
            highestTriggeredStatus = null,
            currentShaderPreset = ShaderPreset.ANIME4K_A,
            currentVo = "gpu-next"
        )
        assertTrue(actionLight is ThermalDecisionHelper.Action.None)
    }

    @Test
    fun `moderate with active shaders drops shaders first`() {
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            highestTriggeredStatus = null,
            currentShaderPreset = ShaderPreset.ANIME4K_A,
            currentVo = "gpu-next"
        )
        assertTrue(action is ThermalDecisionHelper.Action.StepDown)
        val step = action as ThermalDecisionHelper.Action.StepDown
        assertTrue(step.dropShaders)
        assertFalse(step.stepDownVoToGpu)
        assertEquals("Reduced quality (device hot)", step.message)
    }

    @Test
    fun `moderate with no shaders drops gpu-next to gpu`() {
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            highestTriggeredStatus = null,
            currentShaderPreset = ShaderPreset.NONE,
            currentVo = "gpu-next"
        )
        assertTrue(action is ThermalDecisionHelper.Action.StepDown)
        val step = action as ThermalDecisionHelper.Action.StepDown
        assertFalse(step.dropShaders)
        assertTrue(step.stepDownVoToGpu)
        assertEquals("Reduced quality (device hot)", step.message)
    }

    @Test
    fun `moderate with no shaders and gpu already active triggers no action`() {
        // Preserves safety chain: never touches Vulkan->GLES->software ordering
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            highestTriggeredStatus = null,
            currentShaderPreset = ShaderPreset.NONE,
            currentVo = "gpu"
        )
        assertTrue(action is ThermalDecisionHelper.Action.None)
    }

    @Test
    fun `no oscillation when thermal status remains moderate`() {
        // Step 1: Moderate triggered earlier
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            highestTriggeredStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            currentShaderPreset = ShaderPreset.NONE,
            currentVo = "gpu-next"
        )
        // Since highestTriggeredStatus == MODERATE, no repeated action!
        assertTrue(action is ThermalDecisionHelper.Action.None)
    }

    @Test
    fun `never step back up when cooling down`() {
        // Temperature cools from MODERATE to LIGHT
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_LIGHT,
            highestTriggeredStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            currentShaderPreset = ShaderPreset.NONE,
            currentVo = "gpu"
        )
        // No step up!
        assertTrue(action is ThermalDecisionHelper.Action.None)
    }

    @Test
    fun `escalation from moderate to severe steps down the next notch`() {
        // At MODERATE, shaders were dropped and highestTriggeredStatus became MODERATE.
        // Now status escalates to SEVERE:
        val action = ThermalDecisionHelper.decideAction(
            currentStatus = ThermalDecisionHelper.THERMAL_STATUS_SEVERE,
            highestTriggeredStatus = ThermalDecisionHelper.THERMAL_STATUS_MODERATE,
            currentShaderPreset = ShaderPreset.NONE,
            currentVo = "gpu-next"
        )
        assertTrue(action is ThermalDecisionHelper.Action.StepDown)
        val step = action as ThermalDecisionHelper.Action.StepDown
        assertFalse(step.dropShaders)
        assertTrue(step.stepDownVoToGpu)
    }

    @Test
    fun `statusToString produces human-readable strings`() {
        assertEquals("none", ThermalDecisionHelper.statusToString(0))
        assertEquals("light", ThermalDecisionHelper.statusToString(1))
        assertEquals("moderate", ThermalDecisionHelper.statusToString(2))
        assertEquals("severe", ThermalDecisionHelper.statusToString(3))
        assertEquals("critical", ThermalDecisionHelper.statusToString(4))
        assertEquals("emergency", ThermalDecisionHelper.statusToString(5))
        assertEquals("shutdown", ThermalDecisionHelper.statusToString(6))
        assertEquals("unknown(99)", ThermalDecisionHelper.statusToString(99))
    }
}
