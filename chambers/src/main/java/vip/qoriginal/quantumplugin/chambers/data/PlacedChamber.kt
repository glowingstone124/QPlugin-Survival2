package vip.qoriginal.quantumplugin.chambers.data

import org.bukkit.Location

data class PlacedChamber(
	val definition: ChamberDefinition,
	private val placedOrigin: Location,
) {
    val origin: Location = placedOrigin.clone()
}