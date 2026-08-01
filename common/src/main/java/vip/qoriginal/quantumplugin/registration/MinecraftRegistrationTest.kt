package vip.qoriginal.quantumplugin.registration

import org.bukkit.entity.Player

interface MinecraftRegistrationTest {
    fun isAvailable(): Boolean

    /**
     * Starts a test for an online player. Implementations and callers must
     * invoke this on the Minecraft server thread because a test may teleport
     * the player immediately.
     */
    fun start(player: Player, session: Session): StartResult

    fun cancel(player: Player)

    @JvmRecord
    data class Session(
        val sessionId: String,
        val username: String,
    )

    @JvmRecord
    data class StartResult(
        val accepted: Boolean,
        val code: String,
        val message: String,
    ) {
        companion object {
            @JvmStatic
            fun unavailable(): StartResult = StartResult(
                accepted = false,
                code = "minecraft_verification_reserved",
                message = "Minecraft 世界测试接口已预留，当前尚未开放。",
            )
        }
    }

    companion object {
        const val METHOD_ID = "minecraft"
        const val SESSION_ENDPOINT = "/qo/registration/minecraft/session"
        const val CLAIM_ENDPOINT = "/qo/registration/minecraft/claim"
        const val RESULT_ENDPOINT = "/qo/registration/minecraft/result"
    }
}
