package com.ekms.terminal.data

import com.ekms.shared.api.DueDateSource
import com.ekms.shared.api.SiteOfficeHoursDto
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 5. Pure computation of a key-take's checkout deadline from the site's office hours.
 * java.time-based, so this lives in terminalApp (Android-only, minSdk 26) rather than `shared`,
 * which also targets Wasm where java.time isn't available.
 */
object CheckoutDeadlinePolicy {

    /**
     * How close to today's office-hours close time counts as "too close to auto-assign a due
     * date" and requires the operator to make an explicit choice instead. Picked as a reasonable
     * default per this phase's own instruction ("pick a reasonable constant") — not derived from
     * any spec value. Flag if the real product requirement differs.
     */
    const val CLOSE_TO_DEADLINE_THRESHOLD_MINUTES = 60L

    /**
     * Emergency checkouts get a flat window from the moment of take, per this phase's confirmed
     * design ("now + 3 hours"). Same "pick a reasonable constant" provenance as the threshold above.
     */
    const val EMERGENCY_WINDOW_HOURS = 3L

    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val DEFAULT_CLOSE_TIME: LocalTime = LocalTime.of(17, 0)

    sealed interface DeadlineDecision {
        /** Comfortably before close — no operator input needed, [dueAtEpochMillis] is today's close time. */
        data class Auto(val dueAtEpochMillis: Long) : DeadlineDecision

        /**
         * Within [CLOSE_TO_DEADLINE_THRESHOLD_MINUTES] of close, or already past it — the operator
         * must resolve a [CheckoutDeadlineChoice] (manual entry or Emergency) before the take
         * proceeds. [closeAtEpochMillis] is surfaced so the decision screen can show it.
         */
        data class NeedsDecision(val closeAtEpochMillis: Long) : DeadlineDecision
    }

    /**
     * [officeHours] may be null (e.g. the site's office-hours fetch itself failed) — treated the
     * same as "already past close," since there's no safe auto value to fall back to; the
     * operator resolves it explicitly rather than the terminal guessing a deadline.
     */
    fun computeDeadline(officeHours: SiteOfficeHoursDto?, nowEpochMillis: Long): DeadlineDecision {
        val zone = officeHours?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: DEFAULT_ZONE
        val closeTime = officeHours?.closeTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: DEFAULT_CLOSE_TIME
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
        val closeAtEpochMillis = ZonedDateTime.of(now.toLocalDate(), closeTime, zone).toInstant().toEpochMilli()

        if (officeHours == null) {
            return DeadlineDecision.NeedsDecision(closeAtEpochMillis)
        }

        val thresholdMillis = CLOSE_TO_DEADLINE_THRESHOLD_MINUTES * 60_000L
        return if (nowEpochMillis < closeAtEpochMillis - thresholdMillis) {
            DeadlineDecision.Auto(dueAtEpochMillis = closeAtEpochMillis)
        } else {
            DeadlineDecision.NeedsDecision(closeAtEpochMillis = closeAtEpochMillis)
        }
    }

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
        fun auto(dueAtEpochMillis: Long) = CheckoutDeadlineChoice(dueAtEpochMillis, DueDateSource.AUTO)
        fun manual(dueAtEpochMillis: Long) = CheckoutDeadlineChoice(dueAtEpochMillis, DueDateSource.MANUAL)
        fun emergency(nowEpochMillis: Long) = CheckoutDeadlineChoice(
            dueAtEpochMillis = CheckoutDeadlinePolicy.emergencyDueAtEpochMillis(nowEpochMillis),
            source = DueDateSource.EMERGENCY,
        )
    }
}
