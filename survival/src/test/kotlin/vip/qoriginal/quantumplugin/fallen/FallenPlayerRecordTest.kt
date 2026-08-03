package vip.qoriginal.quantumplugin.fallen

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

class FallenPlayerRecordTest {
	@Test
	fun `player activity record survives yaml round trip`() {
		val original = FallenPlayerRecord(
			lastKnownName = "ResearchSubject",
			team = FallenTeam.B,
			upgradePath = FallenUpgradePath.C,
			kills = 7,
			assists = 4,
			deaths = 3,
			damageDealt = 123.75,
			damageTaken = 81.5,
			firstJoinedAtMillis = 1_000L,
			lastSeenAtMillis = 9_000L
		)
		val config = YamlConfiguration()
		original.save(config.createSection("record"))

		assertEquals(original, FallenPlayerRecord.load(config.getConfigurationSection("record")!!))
	}
}
