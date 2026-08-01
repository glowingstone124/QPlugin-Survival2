package vip.qoriginal.quantumplugin.chambers.data

import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest

data class ChamberRunResult(
	val registrationSession: MinecraftRegistrationTest.Session?,
	val reason: FinishReason,
	val completedChambers: Int,
	val totalChambers: Int,
) {
    val passed: Boolean
        get() = reason == FinishReason.PASSED

    enum class FinishReason {
        PASSED,
        TIMED_OUT,
        CANCELLED,
        QUIT,
        PLUGIN_DISABLED,
    }
}