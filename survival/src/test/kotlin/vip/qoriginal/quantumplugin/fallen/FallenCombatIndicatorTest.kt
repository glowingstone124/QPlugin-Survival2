package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallenCombatIndicatorTest {
	private val plain = PlainTextComponentSerializer.plainText()

	@Test
	fun `calculatePostDamageState handles normal health damage without absorption`() {
		val state = FallenCombatIndicator.calculatePostDamageState(
			currentHealth = 20.0,
			currentAbsorption = 0.0,
			maxHealth = 20.0,
			finalDamage = 6.5
		)
		assertEquals(13.5, state.remainingHealth)
		assertEquals(0.0, state.remainingAbsorption)
		assertEquals(13.5, state.totalRemaining)
		assertEquals(20.0, state.maxHealth)
		assertEquals(13.5 / 20.0, state.healthRatio)
	}

	@Test
	fun `calculatePostDamageState deducts absorption first`() {
		val state = FallenCombatIndicator.calculatePostDamageState(
			currentHealth = 20.0,
			currentAbsorption = 4.0,
			maxHealth = 20.0,
			finalDamage = 6.0
		)
		assertEquals(18.0, state.remainingHealth)
		assertEquals(0.0, state.remainingAbsorption)
		assertEquals(18.0, state.totalRemaining)
		assertEquals(20.0, state.maxHealth)
	}

	@Test
	fun `calculatePostDamageState keeps partial absorption when damage is small`() {
		val state = FallenCombatIndicator.calculatePostDamageState(
			currentHealth = 20.0,
			currentAbsorption = 4.0,
			maxHealth = 20.0,
			finalDamage = 2.5
		)
		assertEquals(20.0, state.remainingHealth)
		assertEquals(1.5, state.remainingAbsorption)
		assertEquals(21.5, state.totalRemaining)
		assertEquals(1.0, state.healthRatio)
	}

	@Test
	fun `calculatePostDamageState clamps lethal damage to zero`() {
		val state = FallenCombatIndicator.calculatePostDamageState(
			currentHealth = 5.0,
			currentAbsorption = 0.0,
			maxHealth = 20.0,
			finalDamage = 12.0
		)
		assertEquals(0.0, state.remainingHealth)
		assertEquals(0.0, state.remainingAbsorption)
		assertEquals(0.0, state.totalRemaining)
		assertEquals(0.0, state.healthRatio)
	}

	@Test
	fun `formatHitActionBar includes team name damage health and bar`() {
		val state = FallenCombatIndicator.calculatePostDamageState(20.0, 0.0, 20.0, 5.0)
		val component = FallenCombatIndicator.formatHitActionBar(
			targetName = "TargetPlayer",
			targetTeam = FallenTeam.B,
			finalDamage = 5.0,
			state = state,
			isCritical = false,
			isKeyCarrier = false,
			projectileDistance = null
		)
		val text = plain.serialize(component)
		assertTrue(text.contains("[B]"))
		assertTrue(text.contains("TargetPlayer"))
		assertTrue(text.contains("-5.0❤"))
		assertTrue(text.contains("15.0/20❤"))
		assertTrue(text.contains("❤"))
	}

	@Test
	fun `formatHitActionBar shows crit key carrier and distance indicators`() {
		val state = FallenCombatIndicator.calculatePostDamageState(20.0, 4.0, 20.0, 2.0)
		val component = FallenCombatIndicator.formatHitActionBar(
			targetName = "CarrierPlayer",
			targetTeam = FallenTeam.A,
			finalDamage = 2.0,
			state = state,
			isCritical = true,
			isKeyCarrier = true,
			projectileDistance = 38.4
		)
		val text = plain.serialize(component)
		assertTrue(text.contains("💥"))
		assertTrue(text.contains("[🔑 密钥]"))
		assertTrue(text.contains("远射!"))
		assertTrue(text.contains("38m"))
		assertTrue(text.contains("(+2.0💛)"))
	}

	@Test
	fun `kill and assist actionbar formatting include key carrier and score details`() {
		val normalKillBar = FallenCombatIndicator.formatKillActionBar("EnemyPlayer", FallenTeam.C, 120, isKeyCarrier = false)
		val normalKillText = plain.serialize(normalKillBar)
		assertTrue(normalKillText.contains("击杀 [C] EnemyPlayer"))
		assertTrue(normalKillText.contains("+120 积分"))

		val carrierKillBar = FallenCombatIndicator.formatKillActionBar("CarrierEnemy", FallenTeam.B, 120, isKeyCarrier = true)
		val carrierKillText = plain.serialize(carrierKillBar)
		assertTrue(carrierKillText.contains("击杀 [B] CarrierEnemy"))
		assertTrue(carrierKillText.contains("击落密钥携带者"))

		val assistBar = FallenCombatIndicator.formatAssistActionBar("EnemyPlayer", FallenTeam.B, 50)
		val assistText = plain.serialize(assistBar)
		assertTrue(assistText.contains("助攻击杀 [B] EnemyPlayer"))
		assertTrue(assistText.contains("+50 积分"))
	}

	@Test
	fun `low health warning formats threshold correctly`() {
		val warning = FallenCombatIndicator.formatLowHealthWarning(4.5)
		val text = plain.serialize(warning)
		assertTrue(text.contains("生命值过低"))
		assertTrue(text.contains("4.5❤"))
	}

	@Test
	fun `friendly fire notice shows teammate name`() {
		val notice = FallenCombatIndicator.formatFriendlyFireNotice("Teammate", FallenTeam.A)
		val text = plain.serialize(notice)
		assertTrue(text.contains("无法伤害同阵营成员"))
		assertTrue(text.contains("Teammate"))
	}

	@Test
	fun `tab list formatting produces player list name and headers`() {
		val playerListName = FallenCombatIndicator.formatPlayerListName("TestPlayer", FallenTeam.A, 5, 2, 3, false)
		val nameText = plain.serialize(playerListName)
		assertTrue(nameText.contains("[A] TestPlayer"))
		assertTrue(nameText.contains("5/2/3"))

		val specListName = FallenCombatIndicator.formatPlayerListName("SpecPlayer", null, 0, 1, 0, true)
		val specText = plain.serialize(specListName)
		assertTrue(specText.contains("[旁观] SpecPlayer"))
		assertTrue(specText.contains("0/1/0"))

		val header = FallenCombatIndicator.formatTabHeader("进行中", "01:20:00")
		assertTrue(plain.serialize(header).contains("《陷落》阵营生存对抗"))
		assertTrue(plain.serialize(header).contains("进行中"))

		val footer = FallenCombatIndicator.formatTabFooter(5, 2, 3, "2.50", 120.5, 90.0)
		val footerText = plain.serialize(footer)
		assertTrue(footerText.contains("5 击杀"))
		assertTrue(footerText.contains("2 死亡"))
		assertTrue(footerText.contains("3 助攻"))
		assertTrue(footerText.contains("K/D: 2.50"))
		assertTrue(footerText.contains("输出伤害: 120.5❤"))
	}

	@Test
	fun `formatMobHitActionBar displays mob health and damage`() {
		val state = FallenCombatIndicator.calculatePostDamageState(20.0, 0.0, 20.0, 6.0)
		val actionbar = FallenCombatIndicator.formatMobHitActionBar(
			mobName = Component.text("僵尸"),
			finalDamage = 6.0,
			state = state,
			isCritical = true,
			projectileDistance = 12.0
		)
		val text = plain.serialize(actionbar)
		assertTrue(text.contains("僵尸"))
		assertTrue(text.contains("-6.0❤"))
		assertTrue(text.contains("14.0/20❤"))
		assertTrue(text.contains("💥"))
		assertTrue(text.contains("12m"))
	}
}
