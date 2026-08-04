package com.ekms.terminal.data

import com.ekms.shared.api.DueDateSource

/**
 * Every Take flow except Only B passkey now always requires a manually-entered return time (see
 * `TerminalCloseToDeadlineScreen`'s `AnalogTimePicker`) — no automatic office-hours computation,
 * no silent Auto due-date. This object is now just the Emergency-window constant/computation;
 * the office-hours fetch, `DeadlineDecision` Auto/NeedsDecision branching, and
 * `CLOSE_TO_DEADLINE_THRESHOLD_MINUTES` are gone (superseded, not just unused — see
 * CLAUDE_TERMINAL.md for the removal rationale).
 */
object CheckoutDeadlinePolicy {

    /**
     * Emergency checkouts get a flat window from the moment of take, per this phase's confirmed
     * design ("now + 3 hours"). "Pick a reasonable constant" provenance — not derived from any
     * spec value.
     */
    const val EMERGENCY_WINDOW_HOURS = 3L

    fun emergencyDueAtEpochMillis(nowEpochMillis: Long): Long = nowEpochMillis + EMERGENCY_WINDOW_HOURS * 3_600_000L
}

/**
 * The resolved once-per-session deadline decision, carried through [TakeFlow]/[MultiKeyTakeQueue]
 * so every key in a multi-key session shares the same due date/source rather than re-deciding per key.
 */
data class CheckoutDeadlineChoice(
    val dueAtEpochMillis: Long,
    val source: DueDateSource,
) {
    val isEmergency: Boolean get() = source == DueDateSource.EMERGENCY
    val emergencyWindowEndsAtEpochMillis: Long? get() = if (isEmergency) dueAtEpochMillis else null

    companion object {
        fun manual(dueAtEpochMillis: Long) = CheckoutDeadlineChoice(dueAtEpochMillis, DueDateSource.MANUAL)
        fun emergency(nowEpochMillis: Long) = CheckoutDeadlineChoice(
            dueAtEpochMillis = CheckoutDeadlinePolicy.emergencyDueAtEpochMillis(nowEpochMillis),
            source = DueDateSource.EMERGENCY,
        )

        /**
         * Migration 009 follow-up: a passkey-authenticated take's due time was already fixed at
         * key-access-request approval time (`passkeyExpiresAtEpochMillis`) — never operator-entered
         * (MANUAL) and never office-hours-computed. Callers must NOT route through
         * `TerminalCloseToDeadlineScreen` for this case; this factory is the entire "decision."
         */
        fun passkeyRequest(dueAtEpochMillis: Long) =
            CheckoutDeadlineChoice(dueAtEpochMillis, DueDateSource.PASSKEY_REQUEST)
    }
}
