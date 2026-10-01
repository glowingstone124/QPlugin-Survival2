package vip.qoriginal.quantumplugin.lightscan

import java.io.StringWriter
import java.util.concurrent.CancellationException
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightScanCsvTest {
    private val dark = LightScanCsv.State("AIR", "minecraft:air", 0, 14, 15)

    @Test
    fun `propagated light does not make a block a source and zero chunks are exported`() {
        val result = scan(ScanBounds(0, 0, 0, 0, 0, 0)) { _, _, _ -> dark }
        assertEquals("", result.sourcesCsv())
        assertEquals(0, result.sources())
        assertEquals("\"world\",0,0,7.5,7.5,0,0,0,0,0,0,1,0,0,0,123", result.chunkCsv().trim().split(',').take(16).joinToString(","))
        assertEquals("\"world\",0,0,0,0,0,\"AIR\",0,14,15,15,123\n", result.lightingCsv())
    }

    @Test
    fun `negative coordinates clipped at chunk and inclusive height preserve source states`() {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val result = LightScanCsv.scan("world", -1, -1, 123, ScanBounds(-17, -64, -2, -1, -63, -1),
            { x, y, z ->
                calls += Triple(x, y, z)
                if (x == 15 && y == -64 && z == 15)
                    LightScanCsv.State("REDSTONE_LAMP", "minecraft:redstone_lamp[lit=true]", 15, 15, 0)
                else dark
            }, { false })
        assertEquals(64, calls.size)
        assertTrue(calls.all { it.first in 0..15 && it.second in -64..-63 && it.third in 14..15 })
        assertEquals(1, result.sources())
        assertEquals("\"world\",-1,-64,-1,-1,-1,\"REDSTONE_LAMP\",\"minecraft:redstone_lamp[lit=true]\",15,15,0,123\n", result.sourcesCsv())
        assertEquals("\"world\",-1,-1,-8.5,-8.5,-16,-1,-64,-63,-2,-1,64,1,15,15,123", result.chunkCsv().trim().split(',').take(16).joinToString(","))
        assertEquals(64, result.lightingCsv().lines().filter { it.isNotEmpty() }.size)
    }

    @Test
    fun `material totals sum strength and source counts`() {
        val result = scan(ScanBounds(0, 0, 0, 2, 0, 0)) { x, _, _ ->
            if (x < 2) LightScanCsv.State("TORCH", "minecraft:torch", 14, 14, 15)
            else LightScanCsv.State("LAVA", "minecraft:lava[level=0]", 15, 15, 0)
        }
        assertEquals(3, result.sources())
        assertEquals(LightScanCsv.Totals(2, 28), result.materials()["TORCH"])
        val writer = StringWriter()
        LightScanCsv.writeMaterials(writer, result.materials())
        assertEquals("material,source_count,emission_sum\n\"LAVA\",1,15\n\"TORCH\",2,28\n", writer.toString())
        assertEquals(LightScanCsv.Totals(4, 56), LightScanCsv.add(result.materials()["TORCH"]!!, result.materials()["TORCH"]!!))
    }

    @Test
    fun `csv quotes commas quotes and line breaks`() {
        assertEquals("\"world,\"\"name\"\"\nsecond line\"", LightScanCsv.cell("world,\"name\"\nsecond line"))
        val result = scan(ScanBounds(0, 0, 0, 0, 0, 0)) { _, _, _ ->
            LightScanCsv.State("CANDLE", "minecraft:candle[candles=4,lit=true]", 12, 12, 0)
        }
        assertTrue(result.sourcesCsv().contains("\"minecraft:candle[candles=4,lit=true]\",12,12,0"))
    }

    @Test
    fun `cancellation discards an unfinished chunk`() {
        assertFailsWith<CancellationException> {
            LightScanCsv.scan("world", 0, 0, 123, ScanBounds(0, 0, 0, 0, 10, 0),
                { _, _, _ -> error("cancelled scan must not read blocks") }, { true })
        }
    }

    @Test
    fun `spatial lighting includes dark air and distinguishes propagated light from emission`() {
        val result = scan(ScanBounds(0, 0, 0, 2, 0, 0)) { x, _, _ ->
            when (x) {
                0 -> LightScanCsv.State("AIR", "", 0, 0, 0)
                1 -> LightScanCsv.State("AIR", "", 0, 13, 15)
                else -> LightScanCsv.State("TORCH", "minecraft:torch", 14, 14, 0)
            }
        }
        assertEquals(1, result.sources())
        assertEquals(3, result.lightingCsv().lines().filter { it.isNotEmpty() }.size)
        assertTrue(result.lightingCsv().contains("\"AIR\",0,0,0,0,123"))
        assertTrue(result.lightingCsv().contains("\"AIR\",0,13,15,15,123"))
        val summary = LightScanCsv.CHUNKS_HEADER.trim().split(',').zip(result.chunkCsv().trim().split(',')).toMap()
        assertEquals("27", summary["block_light_sum"])
        assertEquals("15", summary["sky_light_sum"])
        assertEquals("1", summary["block_light_0"])
        assertEquals("1", summary["block_light_13"])
        assertEquals("1", summary["block_light_14"])
        assertEquals("2", summary["sky_light_0"])
        assertEquals("1", summary["sky_light_15"])
        assertEquals("14", summary["max_block_light"])
        assertEquals("15", summary["max_sky_light"])
        assertEquals(3, (0..15).sumOf { summary["block_light_$it"]!!.toInt() })
        assertEquals(3, (0..15).sumOf { summary["sky_light_$it"]!!.toInt() })
    }

    @Test
    fun `compressed lighting csv round trips unicode and gzip footer`() {
        val file = Files.createTempFile("qo-lighting", ".csv.gz")
        try {
            val text = LightScanCsv.LIGHTING_HEADER + "\"世界\",0,1,2,0,0,\"AIR\",0,0,15,15,123\n"
            LightScanCsv.openLighting(file).use { it.write(text) }
            val actual = GZIPInputStream(Files.newInputStream(file)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals(text, actual)
        } finally { Files.delete(file) }
    }

    @Test
    fun `chunk bounds use floor division and reject invalid limits`() {
        val bounds = ScanBounds(-17, -64, -1, -1, 319, 0)
        assertTrue(bounds.includesChunk(-2, -1))
        assertTrue(bounds.includesChunk(-1, 0))
        assertFalse(bounds.includesChunk(0, 0))
        assertFailsWith<IllegalArgumentException> { ScanBounds(0, 1, 0, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { ScanBounds(-30_000_001, 0, 0, 0, 0, 0) }
    }

    private fun scan(bounds: ScanBounds, volume: LightScanCsv.Volume) =
        LightScanCsv.scan("world", 0, 0, 123, bounds, volume, { false })
}
