package vip.qoriginal.quantumplugin.chambers

import vip.qoriginal.quantumplugin.chambers.data.ChamberRunResult

enum class ChamberRunState {
    READY,
    RUNNING,
    PAUSED,
    PASSED,
    FAILED,
}

class ChamberRunStateMachine private constructor(
    initialSnapshot: ChamberProgress,
) {
    var snapshot: ChamberProgress = initialSnapshot
        private set

    val state: ChamberRunState
        get() = snapshot.state

    val completedChambers: Int
        get() = snapshot.completedChambers

    val isTerminal: Boolean
        get() = state == ChamberRunState.PASSED || state == ChamberRunState.FAILED

    fun start(): ChamberProgress {
        check(state in STARTABLE_STATES) {
            "cannot start chamber run from $state"
        }
        return transition(state = ChamberRunState.RUNNING, failureReason = null)
    }

    fun pause(): ChamberProgress {
        check(state == ChamberRunState.RUNNING) {
            "cannot pause chamber run from $state"
        }
        return transition(state = ChamberRunState.PAUSED, failureReason = null)
    }

    fun completeCurrentChamber(): ChamberProgress {
        check(state == ChamberRunState.RUNNING) {
            "cannot complete a chamber from $state"
        }
        val completed = completedChambers + 1
        check(completed <= snapshot.chamberIds.size) {
            "completed chamber count exceeds selected sequence"
        }
        return transition(
            state = if (completed == snapshot.chamberIds.size) {
                ChamberRunState.PASSED
            } else {
                ChamberRunState.RUNNING
            },
            completedChambers = completed,
            failureReason = null,
        )
    }

    fun fail(reason: ChamberRunResult.FinishReason): ChamberProgress {
        require(reason in FAILURE_REASONS) {
            "$reason is not a chamber failure reason"
        }
        check(state == ChamberRunState.RUNNING) {
            "cannot fail chamber run from $state"
        }
        return transition(
            state = ChamberRunState.FAILED,
            failureReason = reason,
        )
    }

    fun terminalReason(): ChamberRunResult.FinishReason? = when (state) {
        ChamberRunState.PASSED -> ChamberRunResult.FinishReason.PASSED
        ChamberRunState.FAILED -> snapshot.failureReason
        else -> null
    }

    private fun transition(
        state: ChamberRunState,
        completedChambers: Int = snapshot.completedChambers,
        failureReason: ChamberRunResult.FinishReason? = snapshot.failureReason,
    ): ChamberProgress {
        snapshot = snapshot.copy(
            state = state,
            completedChambers = completedChambers,
            failureReason = failureReason,
        )
        validate(snapshot)
        return snapshot
    }

    companion object {
        private val STARTABLE_STATES = setOf(
            ChamberRunState.READY,
            ChamberRunState.PAUSED,
        )
        private val FAILURE_REASONS = setOf(
            ChamberRunResult.FinishReason.TIMED_OUT,
            ChamberRunResult.FinishReason.CANCELLED,
        )

        fun restore(progress: ChamberProgress): ChamberRunStateMachine {
            validate(progress)
            val recovered = if (progress.state == ChamberRunState.RUNNING) {
                progress.copy(state = ChamberRunState.PAUSED)
            } else {
                progress
            }
            return ChamberRunStateMachine(recovered)
        }

        fun validate(progress: ChamberProgress) {
            require(progress.completedChambers in 0..progress.chamberIds.size) {
                "completed chamber count is outside selected sequence"
            }
            when (progress.state) {
                ChamberRunState.PASSED -> {
                    require(progress.completedChambers == progress.chamberIds.size) {
                        "passed run has unfinished chambers"
                    }
                    require(progress.failureReason == null) {
                        "passed run must not have a failure reason"
                    }
                }
                ChamberRunState.FAILED -> {
                    require(progress.completedChambers < progress.chamberIds.size) {
                        "failed run has no unfinished chamber"
                    }
                    require(progress.failureReason in FAILURE_REASONS) {
                        "failed run is missing a valid failure reason"
                    }
                }
                else -> {
                    require(progress.completedChambers < progress.chamberIds.size) {
                        "non-terminal run has no unfinished chamber"
                    }
                    require(progress.failureReason == null) {
                        "non-failed run must not have a failure reason"
                    }
                }
            }
        }
    }
}
