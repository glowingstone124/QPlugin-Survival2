package vip.qoriginal.quantumplugin.chambers.data

import org.bukkit.generator.ChunkGenerator

// ChunkGenerator's modern generation hooks are no-ops and all Vanilla
// generation switches default to false, which produces an empty void world.
class VoidChunkGenerator : ChunkGenerator()