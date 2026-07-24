package vip.qoriginal.quantumplugin.registration

import org.bukkit.entity.Player

class ReservedMinecraftRegistrationTest : MinecraftRegistrationTest {
    override fun isAvailable(): Boolean = false

    override fun start(
        player: Player,
        session: MinecraftRegistrationTest.Session,
    ): MinecraftRegistrationTest.StartResult =
        MinecraftRegistrationTest.StartResult.unavailable()

    override fun cancel(player: Player) {
        // Reserved for the future world-based registration test implementation.
    }
}
