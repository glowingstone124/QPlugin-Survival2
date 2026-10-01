package vip.qoriginal.quantumplugin.lightscan

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class LightScanWorkerTest {
    @Test
    fun `calling calculation on the command thread is rejected`() {
        LightScanWorker().use { worker ->
            assertFailsWith<IllegalStateException> { worker.requireWorkerThread() }
            val ready = CompletableFuture<Unit>()
            worker.execute {
                try {
                    worker.requireWorkerThread()
                    ready.complete(Unit)
                } catch (error: Throwable) { ready.completeExceptionally(error) }
            }
            ready.get(5, TimeUnit.SECONDS)
            // Even once the worker exists, callers cannot run work on their own thread.
            assertFailsWith<IllegalStateException> { worker.requireWorkerThread() }
        }
    }

    @Test
    fun `region discovery block calculations csv and gzip export stay on worker`() {
        val caller = Thread.currentThread()
        val directory = Files.createTempDirectory("qo-lightscan-thread-test")
        val done = CompletableFuture<String>()
        try {
            LightScanWorker().use { worker ->
                worker.execute {
                    try {
                        worker.requireWorkerThread()
                        assertNotSame(caller, Thread.currentThread())
                        assertEquals("QO-light-scan", Thread.currentThread().name)
                        val bounds = ScanBounds(0, 0, 0, 0, 0, 0)
                        val chunks = RegionChunkIndex.discover(directory, listOf(RegionChunkIndex.Position(0, 0)), bounds)
                        val position = chunks.single()
                        val result = LightScanCsv.scan("world", position.x(), position.z(), 123, bounds,
                            { _, _, _ ->
                                worker.requireWorkerThread()
                                assertNotSame(caller, Thread.currentThread())
                                LightScanCsv.State("TORCH", "minecraft:torch", 14, 14, 0)
                            }, { false })
                        worker.requireWorkerThread()
                        val output = directory.resolve("lighting.csv.gz")
                        LightScanCsv.openLighting(output).use { it.write(LightScanCsv.LIGHTING_HEADER + result.lightingCsv()) }
                        val text = GZIPInputStream(Files.newInputStream(output)).bufferedReader().use { it.readText() }
                        done.complete(text)
                    } catch (error: Throwable) { done.completeExceptionally(error) }
                }
                assertEquals(LightScanCsv.LIGHTING_HEADER + "\"world\",0,0,0,0,0,\"TORCH\",14,14,0,14,123\n",
                    done.get(5, TimeUnit.SECONDS))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
