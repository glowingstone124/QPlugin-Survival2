package vip.qoriginal.quantumplugin.chambers

import vip.qoriginal.quantumplugin.chambers.data.ChamberRunResult
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChamberProgressStoreTest {
    @Test
    fun `random sequence and completion are restored from disk`() = withStore { store, _ ->
        val session = MinecraftRegistrationTest.Session(
            UUID.randomUUID().toString(),
            "Alex_123",
        )
        val created = store.loadOrCreate(session) {
            listOf("laser-intro", "cube-drop", "final-door")
        }
        val machine = ChamberRunStateMachine.restore(created)
        machine.start()
        machine.completeCurrentChamber()
        machine.pause()
        val saved = store.save(machine.snapshot)

        val restored = store.loadOrCreate(session) {
            error("a persisted session must not choose another random sequence")
        }

        assertEquals(saved.chamberIds, restored.chamberIds)
        assertEquals(1, restored.completedChambers)
        assertEquals(ChamberRunState.PAUSED, restored.state)
    }

    @Test
    fun `progress cannot be reused by another username`() = withStore { store, _ ->
        val sessionId = UUID.randomUUID().toString()
        store.loadOrCreate(
            MinecraftRegistrationTest.Session(sessionId, "Alex_123"),
        ) {
            listOf("laser-intro")
        }

        assertFailsWith<IllegalArgumentException> {
            store.loadOrCreate(
                MinecraftRegistrationTest.Session(sessionId, "Steve_123"),
            ) {
                listOf("other")
            }
        }
    }

    @Test
    fun `terminal result survives restart for safe retry`() = withStore { store, _ ->
        val session = MinecraftRegistrationTest.Session(
            UUID.randomUUID().toString(),
            "Alex_123",
        )
        val created = store.loadOrCreate(session) {
            listOf("laser-intro")
        }
        val machine = ChamberRunStateMachine.restore(created)
        machine.start()
        machine.fail(ChamberRunResult.FinishReason.TIMED_OUT)
        store.save(machine.snapshot)

        val restored = store.loadOrCreate(session) {
            error("terminal progress must be restored")
        }
        val restoredMachine = ChamberRunStateMachine.restore(restored)
        assertEquals(ChamberRunState.FAILED, restoredMachine.state)
        assertEquals(
            ChamberRunResult.FinishReason.TIMED_OUT,
            restoredMachine.terminalReason(),
        )
    }

    @Test
    fun `state machine pauses and restarts the first unfinished chamber`() =
        withStore { store, _ ->
            val session = MinecraftRegistrationTest.Session(
                UUID.randomUUID().toString(),
                "Alex_123",
            )
            val machine = ChamberRunStateMachine.restore(
                store.loadOrCreate(session) {
                    listOf("laser-intro", "cube-drop")
                },
            )

            assertEquals(ChamberRunState.READY, machine.state)
            machine.start()
            assertEquals(ChamberRunState.RUNNING, machine.state)
            assertFailsWith<IllegalStateException> {
                machine.start()
            }

            machine.completeCurrentChamber()
            assertEquals(1, machine.completedChambers)
            assertEquals(ChamberRunState.RUNNING, machine.state)

            machine.pause()
            assertEquals(ChamberRunState.PAUSED, machine.state)
            assertFailsWith<IllegalStateException> {
                machine.completeCurrentChamber()
            }
            assertFailsWith<IllegalStateException> {
                machine.pause()
            }

            machine.start()
            machine.completeCurrentChamber()
            assertEquals(2, machine.completedChambers)
            assertEquals(ChamberRunState.PASSED, machine.state)
            assertTrue(machine.isTerminal)
            assertEquals(
                ChamberRunResult.FinishReason.PASSED,
                machine.terminalReason(),
            )
        }

    @Test
    fun `running snapshot from a crash is recovered as paused`() = withStore { store, _ ->
        val session = MinecraftRegistrationTest.Session(
            UUID.randomUUID().toString(),
            "Alex_123",
        )
        val running = ChamberRunStateMachine.restore(
            store.loadOrCreate(session) {
                listOf("laser-intro", "cube-drop")
            },
        ).apply {
            start()
            completeCurrentChamber()
        }
        store.save(running.snapshot)

        val recovered = ChamberRunStateMachine.restore(
            store.loadOrCreate(session) {
                error("the persisted sequence must be reused")
            },
        )

        assertEquals(ChamberRunState.PAUSED, recovered.state)
        assertEquals(1, recovered.completedChambers)
        assertFalse(recovered.isTerminal)
        recovered.start()
        assertEquals(ChamberRunState.RUNNING, recovered.state)
        assertEquals(1, recovered.completedChambers)
    }

    @Test
    fun `legacy disconnected run migrates to paused`() = withStore { store, directory ->
        val session = MinecraftRegistrationTest.Session(
            UUID.randomUUID().toString(),
            "Alex_123",
        )
        val progressFile = directory
            .resolve("progress")
            .resolve("${session.sessionId}.json")
        Files.writeString(
            progressFile,
            """
            {
              "version": 1,
              "sessionId": "${session.sessionId}",
              "username": "${session.username}",
              "chambers": ["laser-intro", "cube-drop"],
              "completedChambers": 1,
              "remainingMillis": 12000,
              "terminalReason": "QUIT",
              "updatedAtMillis": 1
            }
            """.trimIndent(),
        )

        val restored = store.loadOrCreate(session) {
            error("legacy progress must be reused")
        }

        assertEquals(ChamberRunState.PAUSED, restored.state)
        assertEquals(1, restored.completedChambers)
        assertEquals(null, restored.failureReason)
    }

    private fun withStore(block: (ChamberProgressStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("chamber-progress-test")
        try {
            block(ChamberProgressStore(directory), directory)
        } finally {
            deleteTree(directory)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

}
