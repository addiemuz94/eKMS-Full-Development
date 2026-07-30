package com.ekms.terminal.hardware.face

import java.security.SecureRandom

/**
 * Active-liveness challenge — single-gesture rework of the original blink+head-turn design
 * ported from `../eKMSHardwareTester` (see the deleted `ActiveBlinkLivenessChallenge.kt`).
 *
 * **Why blink detection was dropped, not just re-tuned:** live hardware testing showed the
 * frame-pump's ~350ms polling interval is fundamentally too coarse to reliably sample a natural
 * human blink (~100-400ms duration) — the closed-eye moment can fall entirely between two polls,
 * so a real blink that genuinely happened is simply never observed. That is a sampling-rate
 * problem, not a threshold-tuning problem, and it compounded with the original design's
 * sequential multi-gate structure (stable-open-eyes -> blink -> stable-open-eyes-again ->
 * head-turn, all within one fixed 15s window, no partial credit) — any single missed sample
 * anywhere in that chain forced a full restart.
 *
 * A head turn is a sustained geometric state (typically 500ms-1.5s from centered to turned),
 * not a brief instantaneous event — it tolerates the same 350ms polling interval fine. Dropping
 * to head-turn-only removes the fragile link without removing the property that actually matters
 * for a v1 RGB-only check: responding live to a randomly-chosen instruction defeats a static
 * photo or a pre-recorded loop, the same way the original design intended.
 *
 * Still a real, not hypothetical, weaker anti-spoof tier than the vendor manual's RGB+IR spec
 * (see CLAUDE.md's face-enrollment note) — this rework doesn't change that tradeoff, only makes
 * the RGB-only tier actually reliable to pass for a live person.
 */
class ActiveHeadTurnLivenessChallenge {

    enum class State {
        IDLE,
        WAITING_FOR_HEAD_TURN,
        PASSED,
        FAILED,
    }

    /**
     * Was `private` — made public (additive, UI/trigger-timing pass) so a hosting screen can
     * show which way to turn as a typed value instead of parsing [Update.message]'s English
     * text. [instruction] itself is unchanged and still populates [Update.message] as before.
     */
    enum class HeadTurnDirection(val instruction: String) {
        LEFT("Turn your head left now."),
        RIGHT("Turn your head right now."),
    }

    /**
     * @param direction Which way this attempt is asking for — populated on every [Update], not
     * only while [state] == WAITING_FOR_HEAD_TURN, for simplicity; meaningless outside that
     * state (nothing reads it then).
     * @param progress Display-only, 0f (dead-center) to 1f (exactly at the pass threshold) —
     * `abs(headTurnScore) / threshold`, clamped. Does **not** feed back into [consume]'s own
     * pass/fail decision, which is byte-for-byte unchanged; this is purely an additional value
     * for a UI to bind a progress indicator to. Always `0f` outside an active
     * WAITING_FOR_HEAD_TURN attempt, `1f` on the frame that passes.
     */
    data class Update(
        val state: State,
        val message: String,
        val direction: HeadTurnDirection,
        val progress: Float = 0f,
    ) {
        val isTerminal: Boolean get() = state == State.PASSED || state == State.FAILED
    }

    companion object {
        private const val LEFT_HEAD_TURN_MIN_SCORE = 0.18f
        private const val RIGHT_HEAD_TURN_MAX_SCORE = -0.18f
        private const val TIMEOUT_MS = 15_000L
    }

    private val random = SecureRandom()

    private var state = State.IDLE
    private var startedAtMs = 0L
    private var requiredHeadTurn = HeadTurnDirection.LEFT

    @get:Synchronized
    val isRunning: Boolean
        get() = state == State.WAITING_FOR_HEAD_TURN

    @Synchronized
    fun start(nowMs: Long): Update {
        startedAtMs = nowMs
        requiredHeadTurn = if (random.nextBoolean()) HeadTurnDirection.LEFT else HeadTurnDirection.RIGHT
        state = State.WAITING_FOR_HEAD_TURN
        return Update(state, requiredHeadTurn.instruction, requiredHeadTurn)
    }

    @Synchronized
    fun cancel(): Update {
        state = State.IDLE
        return Update(state, "Challenge cancelled.", requiredHeadTurn)
    }

    @Synchronized
    fun consume(headTurnScore: Float?, nowMs: Long): Update {
        if (!isRunning) {
            return Update(state, "Start the active liveness challenge first.", requiredHeadTurn)
        }

        if (nowMs - startedAtMs > TIMEOUT_MS) {
            state = State.FAILED
            return Update(state, "Timed out. Keep one clear face in view and try again.", requiredHeadTurn)
        }

        if (headTurnScore == null) {
            return Update(state, "Landmark data is unavailable. Keep exactly one face centred.", requiredHeadTurn)
        }

        val progress = progressToward(requiredHeadTurn, headTurnScore)

        val correctHeadTurn = when (requiredHeadTurn) {
            HeadTurnDirection.LEFT -> headTurnScore >= LEFT_HEAD_TURN_MIN_SCORE
            HeadTurnDirection.RIGHT -> headTurnScore <= RIGHT_HEAD_TURN_MAX_SCORE
        }

        return if (correctHeadTurn) {
            state = State.PASSED
            Update(state, "Passed: head-turn challenge completed.", requiredHeadTurn, progress = 1f)
        } else {
            Update(state, requiredHeadTurn.instruction, requiredHeadTurn, progress = progress)
        }
    }

    /**
     * Display-only normalization, no effect on [consume]'s pass/fail decision above (unchanged
     * — still the exact same `headTurnScore >= LEFT_HEAD_TURN_MIN_SCORE`/`<= RIGHT_HEAD_TURN_MAX_SCORE`
     * comparisons). Dividing by the signed threshold rather than its magnitude makes the sign
     * cancel out correctly for both directions in one expression: a score past the threshold in
     * the *correct* direction always yields a positive ratio ≥1 (clamped to 1f); a score of zero
     * (centered) or past the threshold in the *wrong* direction yields ≤0 (clamped to 0f) — no
     * separate LEFT/RIGHT branching needed beyond picking which threshold to divide by.
     */
    private fun progressToward(direction: HeadTurnDirection, headTurnScore: Float): Float {
        val threshold = when (direction) {
            HeadTurnDirection.LEFT -> LEFT_HEAD_TURN_MIN_SCORE
            HeadTurnDirection.RIGHT -> RIGHT_HEAD_TURN_MAX_SCORE
        }
        return (headTurnScore / threshold).coerceIn(0f, 1f)
    }
}
