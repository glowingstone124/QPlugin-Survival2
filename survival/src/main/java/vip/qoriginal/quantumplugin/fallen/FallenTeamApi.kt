package vip.qoriginal.quantumplugin.fallen

import com.google.gson.JsonParser

data class FallenTeamLookupResult(
	val responseValid: Boolean,
	val finalizedTeam: FallenTeam?
)

object FallenTeamApi {
	fun parseLookupResponse(body: String?): FallenTeamLookupResult {
		if (body.isNullOrBlank()) return FallenTeamLookupResult(false, null)
		return runCatching {
			val response = JsonParser.parseString(body).asJsonObject
			val selected = response.get("selected")?.asBoolean
				?: return FallenTeamLookupResult(false, null)
			if (!selected) return FallenTeamLookupResult(true, null)
			val finalized = response.get("finalized")?.asBoolean
				?: return FallenTeamLookupResult(false, null)
			if (!finalized) return FallenTeamLookupResult(true, null)
			FallenTeamLookupResult(true, FallenTeam.parse(response.get("team")?.asString))
		}.getOrDefault(FallenTeamLookupResult(false, null))
	}
}
