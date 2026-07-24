package com.ekms.terminal.hardware.face

import java.security.SecureRandom
import kotlin.math.abs

/**
 * Ported unchanged from `../eKMSHardwareTester`'s `face/ActiveBlinkLivenessChallenge.kt` — the
 * RGB-only active-liveness approach chosen for v1 (see CLAUDE.md's face-enrollment tradeoff
 * note: a real, not hypothetical, weaker anti-spoof tier than RGB+IR fusion — deferred as a
 * planned future upgrade, not treated as equivalent to the vendor manual's RGB/IR spec).
 *
 * Requires a centred, open face, a blink, reopened eyes, and a randomly selected head turn.
 * This raises the bar against a still photograph but is not a certified presentation-attack-
 * detection (PAD) solution.
 */
class ActiveBlinkLivenessChallenge {

    enum class State {
        IDLE,
        WAITING_FOR_FRONT_OPEN_EYES,
        WAITING_FOR_BLINK,
        WAITING_FOR_REOPENED_EYES,
        WAITING_FOR_HEAD_TURN,
        PASSED,
        FAILED,
    }

    private enum class HeadTurnDirection(val instruction: String) {
        LEFT("Turn your head left now."),
        RIGHT("Turn your head right now."),
    }

    data class Update(val state: State, val message: String) {
        val isTerminal: Boolean get() = state == State.PASSED || state == State.FAILED
    }

    companion object {
        private const val OPEN_EYE_MAX_SCORE = 0.20f
        private const val CLOSED_EYE_AVERAGE_MIN_SCORE = 0.35f
        private const val CLOSED_EYE_ONE_SIDE_MIN_SCORE = 0.45f

        private const val FRONT_HEAD_MAX_SCORE = 0.10f
        private const val LEFT_HEAD_TURN_MIN_SCORE = 0.18f
        private const val RIGHT_HEAD_TURN_MAX_SCORE = -0.18f

        private const val REQUIRED_STABLE_OPEN_FRAMES = 2
        private const val TIMEOUT_MS = 15_000L
    }

    private val random = SecureRandom()

    private var state = State.IDLE
    private var startedAtMs = 0L
    private var stableOpenFrameCount = 0
    private var requiredHeadTurn = HeadTurnDirection.LEFT

    @get:Synchronized
    val isRunning: Boolean
        get() = state == State.WAITING_FOR_FRONT_OPEN_EYES ||
            state == State.WAITING_FOR_BLINK ||
            state == State.WAITING_FOR_REOPENED_EYES ||
            state == State.WAITING_FOR_HEAD_TURN

    @Synchronized
    fun start(nowMs: Long): Update {
        startedAtMs = nowMs
        stableOpenFrameCount = 0
        requiredHeadTurn = if (random.nextBoolean()) HeadTurnDirection.LEFT else HeadTurnDirection.RIGHT
        state = State.WAITING_FOR_FRONT_OPEN_EYES
        return Update(state, "Face forward with both eyes open.")
    }

    @Synchronized
    fun cancel(): Update {
        stableOpenFrameCount = 0
        state = State.IDLE
        return Update(state, "Challenge cancelled.")
    }

    @Synchronized
    fun consume(
        leftEyeBlinkScore: Float?,
        rightEyeBlinkScore: Float?,
        headTurnScore: Float?,
        nowMs: Long,
    ): Update {
        if (!isRunning) {
            return Update(state, "Start the active liveness challenge first.")
        }

        if (nowMs - startedAtMs > TIMEOUT_MS) {
            state = State.FAILED
            return Update(state, "Timed out. Keep one clear face in view and try again.")
        }

        if (leftEyeBlinkScore == null || rightEyeBlinkScore == null || headTurnScore == null) {
            stableOpenFrameCount = 0
            return Update(state, "Landmark data is unavailable. Keep exactly one face centred.")
        }

        val bothEyesOpen = leftEyeBlinkScore <= OPEN_EYE_MAX_SCORE && rightEyeBlinkScore <= OPEN_EYE_MAX_SCORE
        val averageBlinkScore = (leftEyeBlinkScore + rightEyeBlinkScore) / 2f
        val blinkObserved = averageBlinkScore >= CLOSED_EYE_AVERAGE_MIN_SCORE &&
            maxOf(leftEyeBlinkScore, rightEyeBlinkScore) >= CLOSED_EYE_ONE_SIDE_MIN_SCORE
        val facingForward = abs(headTurnScore) <= FRONT_HEAD_MAX_SCORE
        val correctHeadTurn = when (requiredHeadTurn) {
            HeadTurnDirection.LEFT -> headTurnScore >= LEFT_HEAD_TURN_MIN_SCORE
            HeadTurnDirection.RIGHT -> headTurnScore <= RIGHT_HEAD_TURN_MAX_SCORE
        }

        return when (state) {
            State.WAITING_FOR_FRONT_OPEN_EYES -> {
                stableOpenFrameCount = if (bothEyesOpen && facingForward) stableOpenFrameCount + 1 else 0
                if (stableOpenFrameCount >= REQUIRED_STABLE_OPEN_FRAMES) {
                    state = State.WAITING_FOR_BLINK
                    stableOpenFrameCount = 0
                    Update(state, "Ready. Blink once naturally now.")
                } else {
                    Update(state, "Face forward with both eyes open.")
                }
            }

            State.WAITING_FOR_BLINK -> when {
                !facingForward -> Update(state, "Face forward, then blink once naturally.")
                blinkObserved -> {
                    state = State.WAITING_FOR_REOPENED_EYES
                    stableOpenFrameCount = 0
                    Update(state, "Blink detected. Open both eyes again.")
                }
                else -> Update(state, "Blink once naturally now.")
            }

            State.WAITING_FOR_REOPENED_EYES -> {
                stableOpenFrameCount = if (bothEyesOpen && facingForward) stableOpenFrameCount + 1 else 0
                if (stableOpenFrameCount >= REQUIRED_STABLE_OPEN_FRAMES) {
                    state = State.WAITING_FOR_HEAD_TURN
                    Update(state, requiredHeadTurn.instruction)
                } else {
                    Update(state, "Face forward and open both eyes again.")
                }
            }

            State.WAITING_FOR_HEAD_TURN -> if (correctHeadTurn) {
                state = State.PASSED
                Update(state, "Passed: blink and random head-turn challenge completed.")
            } else {
                Update(state, requiredHeadTurn.instruction)
            }

            State.IDLE, State.PASSED, State.FAILED ->
                Update(state, "Start the active liveness challenge first.")
        }
    }
}
