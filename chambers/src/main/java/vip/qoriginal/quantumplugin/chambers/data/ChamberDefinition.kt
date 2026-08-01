package vip.qoriginal.quantumplugin.chambers.data

import org.bukkit.structure.Structure
import vip.qoriginal.quantumplugin.chambers.ChamberScripts

data class ChamberDefinition(
	val id: String,
	val title: String,
	val objective: String,
	val structure: Structure,
	val includeEntities: Boolean,
	val spawn: ChamberPosition,
	val goal: ChamberRegion,
	val timeLimitSeconds: Int,
	val scripts: ChamberScripts,
)