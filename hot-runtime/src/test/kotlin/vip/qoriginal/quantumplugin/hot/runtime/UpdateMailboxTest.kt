package vip.qoriginal.quantumplugin.hot.runtime

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger
import kotlin.test.*

class UpdateMailboxTest {
    @Test fun `deployment receipt is written only after the host confirms activation`() {
        val root = Files.createTempDirectory("qhot-mailbox")
        val instance = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val complete = CompletableFuture<HotSwapManager.Result>()
        var submissions = 0
        try {
            val mailbox = UpdateMailbox(root, instance, { request ->
                assertEquals("v1", request.expectedActive())
                submissions++
                complete
            }, Executor(Runnable::run), Logger.getAnonymousLogger())
            AtomicFiles.write(root.resolve("requests/$id.json"), ReleaseManifest.JSON.toJson(
                UpdateMailbox.Request(id, instance, "v2", "v1", System.currentTimeMillis())))
            mailbox.poll(); mailbox.poll()
            assertEquals(1, submissions)
            assertFalse(Files.exists(root.resolve("results/$id.json")))
            complete.complete(HotSwapManager.Result(true, "ACTIVE", "v2", "healthy"))
            assertTrue(Files.readString(root.resolve("results/$id.json")).contains("ACTIVE"))
            assertFalse(Files.exists(root.resolve("requests/$id.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `old process and stale filesystem requests cannot apply code on a restarted backend`() {
        val root = Files.createTempDirectory("qhot-mailbox")
        val id = UUID.randomUUID().toString()
        try {
            val mailbox = UpdateMailbox(root, "current-process", { error("Must not submit an old request") }, Executor(Runnable::run), Logger.getAnonymousLogger())
            AtomicFiles.write(root.resolve("requests/$id.json"), ReleaseManifest.JSON.toJson(
                UpdateMailbox.Request(id, "old-process", "v2", "v1", System.currentTimeMillis())))
            mailbox.poll()
            assertTrue(Files.readString(root.resolve("results/$id.json")).contains("INVALID_REQUEST"))
            AtomicFiles.write(root.resolve("requests/$id.json"), "not json")
            mailbox.poll()
            assertFalse(Files.exists(root.resolve("requests/$id.json")))
        } finally { root.toFile().deleteRecursively() }
    }
}
