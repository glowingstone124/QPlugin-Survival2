package vip.qoriginal.quantumplugin.lightscan

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegionChunkIndexTest {
    @Test
    fun `saved chunk header maps negative region coordinates and merges unsaved loaded chunks`() = withDirectory { dir ->
        region(dir, -1, 2, 0, 33, 1023)
        val loaded = listOf(pos(-32, 64), pos(5, -3))
        assertEquals(setOf(pos(-32, 64), pos(-31, 65), pos(-1, 95), pos(5, -3)),
            RegionChunkIndex.discover(dir, loaded, null))
    }

    @Test
    fun `bounds restrict both disk and loaded chunks without expanding negative block coordinates`() = withDirectory { dir ->
        region(dir, -1, -1, 1023, 1022, 0)
        val bounds = ScanBounds(-16, -64, -16, -1, 319, -1)
        assertEquals(setOf(pos(-1, -1)), RegionChunkIndex.discover(dir, listOf(pos(0, 0)), bounds))
    }

    @Test
    fun `missing region folder still includes loaded chunks`() = withDirectory { dir ->
        assertEquals(setOf(pos(3, 4)), RegionChunkIndex.discover(dir.resolve("absent"), listOf(pos(3, 4)), null))
    }

    @Test
    fun `truncated headers fail instead of silently producing a complete export`() = withDirectory { dir ->
        Files.write(dir.resolve("r.0.0.mca"), byteArrayOf(0, 0, 0))
        assertFailsWith<IOException> { RegionChunkIndex.discover(dir, emptyList(), null) }
    }

    @Test
    fun `region discovery responds to cancellation`() = withDirectory { dir ->
        region(dir, 0, 0, 0)
        assertFailsWith<CancellationException> { RegionChunkIndex.discover(dir, emptyList(), null) { true } }
    }

    private fun region(dir: Path, x: Int, z: Int, vararg indices: Int) {
        RandomAccessFile(dir.resolve("r.$x.$z.mca").toFile(), "rw").use { file ->
            file.setLength(8192)
            for (index in indices) {
                file.seek(index * 4L)
                file.writeInt((2 shl 8) or 1)
            }
        }
    }

    private fun pos(x: Int, z: Int) = RegionChunkIndex.Position(x, z)

    private fun withDirectory(test: (Path) -> Unit) {
        val dir = Files.createTempDirectory("qo-lightscan-test")
        try { test(dir) } finally { dir.toFile().deleteRecursively() }
    }
}
