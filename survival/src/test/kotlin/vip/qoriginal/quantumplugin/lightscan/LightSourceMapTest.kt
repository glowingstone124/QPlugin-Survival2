package vip.qoriginal.quantumplugin.lightscan

import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightSourceMapTest {
    @Test
    fun `map is north up and distinguishes missing dark and source pixels`() {
        val map = LightSourceMap.create(ScanBounds(-16, 0, 0, 16, 0, 16), emptyList())
        map.add(-16, 0, 14)
        map.markScanned(-1, 0, ScanBounds(-16, 0, 0, 16, 0, 16))

        assertEquals(33, map.width())
        assertEquals(17, map.height())
        assertEquals(1, map.blocksPerPixel())
        assertEquals(15, map.pixelValue(0, 0))
        assertEquals(1, map.pixelValue(15, 15))
        assertEquals(0, map.pixelValue(32, 16))

        val directory = Files.createTempDirectory("qo-light-map-test")
        try {
            val output = directory.resolve("sources.png")
            map.write(output)
            val image = ImageIO.read(output.toFile())
            assertEquals(33, image.width)
            assertEquals(17, image.height)
            assertEquals("sources.png", map.metadata()["file"])
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `wide maps downsample to at most 8k without a huge test allocation`() {
        val map = LightSourceMap.create(ScanBounds(-30_000_000, 0, 0, 30_000_000, 0, 0), emptyList())
        assertTrue(map.width() <= LightSourceMap.MAX_DIMENSION)
        assertEquals(1, map.height())
        assertTrue(map.blocksPerPixel() > 1)
        map.add(30_000_000, 0, 15)
        assertTrue(map.pixelValue(map.width() - 1, 0) > 1)
    }
}
