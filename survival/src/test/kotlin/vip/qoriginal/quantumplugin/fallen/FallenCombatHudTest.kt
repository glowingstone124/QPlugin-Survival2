package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallenCombatHudTest {
	private val plain = PlainTextComponentSerializer.plainText()

	@Test
	fun `buildScoreHudText formats score and reason in COD style`() {
		val kill = FallenCombatHud.buildScoreHudText(100, "消灭敌人")
		val killText = plain.serialize(kill)
		assertTrue(killText.contains("+100"))
		assertTrue(killText.contains("消灭敌人"))

		val carrier = FallenCombatHud.buildScoreHudText(100, "截获密钥")
		val carrierText = plain.serialize(carrier)
		assertTrue(carrierText.contains("+100"))
		assertTrue(carrierText.contains("截获密钥"))

		val assist = FallenCombatHud.buildScoreHudText(50, "协助消灭")
		val assistText = plain.serialize(assist)
		assertTrue(assistText.contains("+50"))
		assertTrue(assistText.contains("协助消灭"))
	}

	@Test
	fun `calculateCameraLocation places HUD in front and above crosshair in camera space`() {
		// Eye looking towards +Z
		val eye = Location(null, 0.0, 10.0, 0.0, 0f, 0f)
		val target = FallenCombatHud.calculateCameraLocation(eye, distance = 2.0, cameraUpOffset = 0.5)

		assertEquals(0.0, target.x, 0.001)
		assertEquals(10.5, target.y, 0.001) // +0.5 up in camera space
		assertEquals(2.0, target.z, 0.001)  // +2.0 forward in camera space
	}
}
