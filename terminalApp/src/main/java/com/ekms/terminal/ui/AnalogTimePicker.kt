package com.ekms.terminal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.LocalAudioClick
import com.ekms.terminal.ui.theme.LocalEkmsColors
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Large custom analog clock time-picker (mandatory-manual-return-time rework). Time-only (no
 * date) — the hour hand and minute hand are both independently draggable at any time, not a
 * forced hour-then-minute sequence, matching a standard analog clock's own interaction model.
 * [dialDiameter] defaults to 340.dp, notably larger than a typical phone time picker, since this
 * is used on a cabinet-mounted tablet at arm's length, not a phone in hand.
 *
 * Deliberately Canvas-drawn rather than Material3's `TimePicker`: M3's dial has no size-
 * customization hook (confirmed against the pinned `material3:1.3.2` — the one existing call
 * site in this monorepo, `mobileApp`'s `DateTimePickerField`, renders it at its fixed default
 * size with no theming), and every other interactive surface in this app (`SoftScanTile`,
 * `SoftWaitPanel`, etc.) is a bespoke `LocalEkmsColors`-themed widget, never a bare Material3
 * component — see the design audit that preceded this build (`CLAUDE_TERMINAL.md`).
 *
 * [onConfirm] receives the resolved epoch millis directly (today's date + the selected time,
 * rolling to tomorrow if that combination has already passed) rather than a raw hour/minute pair
 * or a `CheckoutDeadlineChoice`, so this component stays a reusable time-picker, not coupled to
 * the take-flow domain type — the caller (`TerminalCloseToDeadlineScreen`) wraps the result in
 * `CheckoutDeadlineChoice.manual(...)`.
 */
@Composable
fun AnalogTimePicker(
    nowEpochMillis: () -> Long,
    onConfirm: (dueAtEpochMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
    dialDiameter: Dp = 340.dp,
) {
    val colors = LocalEkmsColors.current
    val initial = remember {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis()), ZoneId.systemDefault())
        Triple(if (now.hour % 12 == 0) 12 else now.hour % 12, now.minute, now.hour >= 12)
    }
    var hour12 by remember { mutableStateOf(initial.first) }
    var minute by remember { mutableStateOf(initial.second) }
    var isPm by remember { mutableStateOf(initial.third) }

    // Decided once at drag-start by nearest-hand hit-testing (not re-decided mid-drag), so one
    // continuous drag gesture can't "jump" from one hand to the other partway through.
    var draggingHour by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = formatReadout(hour12, minute, isPm),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )

        // No existing SegmentedControl pattern in terminalApp (checked) — a simple two-state pill
        // toggle matching SoftAssistChip's rounded-pill shape/tone language instead.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AmPmSegment(label = "AM", selected = !isPm, onClick = { isPm = false })
            AmPmSegment(label = "PM", selected = isPm, onClick = { isPm = true })
        }

        val textMeasurer = rememberTextMeasurer()
        val numeralStyle = TextStyle(
            fontSize = MaterialTheme.typography.titleMedium.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val dialColor = MaterialTheme.colorScheme.surface
        val outlineColor = MaterialTheme.colorScheme.outlineVariant

        Canvas(
            modifier = Modifier
                .size(dialDiameter)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val radius = min(size.width, size.height) / 2f
                            val hourTip = handTip(center, radius * HOUR_HAND_FRACTION, hourAngle(hour12, minute))
                            val minuteTip = handTip(center, radius * MINUTE_HAND_FRACTION, minute * 6f)
                            draggingHour = (offset - hourTip).getDistance() <= (offset - minuteTip).getDistance()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val angle = angleDegrees(change.position, center)
                            if (draggingHour) {
                                hour12 = angleToHour12(angle)
                            } else {
                                minute = angleToMinute(angle)
                            }
                        },
                    )
                },
        ) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = min(this.size.width, this.size.height) / 2f

            drawCircle(color = dialColor, radius = radius, center = center)
            drawCircle(
                color = outlineColor,
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )

            for (h in 1..12) {
                val angle = h * 30f
                val position = handTip(center, radius * NUMBER_RING_FRACTION, angle)
                val text = h.toString()
                val measured = textMeasurer.measure(text, numeralStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = text,
                    style = numeralStyle,
                    topLeft = Offset(position.x - measured.size.width / 2f, position.y - measured.size.height / 2f),
                )
            }

            val hourTip = handTip(center, radius * HOUR_HAND_FRACTION, hourAngle(hour12, minute))
            drawLine(
                color = colors.primaryDark,
                start = center,
                end = hourTip,
                strokeWidth = 8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val minuteTip = handTip(center, radius * MINUTE_HAND_FRACTION, minute * 6f)
            drawLine(
                color = colors.primary,
                start = center,
                end = minuteTip,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Generously-sized grab handles at each hand's tip — a real touch-target aid, not
            // just decoration, given this dial is meant for arm's-length cabinet use.
            drawCircle(color = colors.primaryDark, radius = 14.dp.toPx(), center = hourTip)
            drawCircle(color = colors.primary, radius = 11.dp.toPx(), center = minuteTip)
            drawCircle(color = colors.primary, radius = 6.dp.toPx(), center = center)
        }

        IconActionButton(
            type = ActionButtonType.ACCEPT,
            label = "Use this return time",
            onClick = {
                val hour24 = to24Hour(hour12, isPm)
                onConfirm(resolveDueAtEpochMillis(hour24, minute, nowEpochMillis()))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AmPmSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalEkmsColors.current
    val playClick = LocalAudioClick.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) colors.primary else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    playClick()
                    onClick()
                },
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private const val HOUR_HAND_FRACTION = 0.5f
private const val MINUTE_HAND_FRACTION = 0.82f
private const val NUMBER_RING_FRACTION = 0.82f

private fun hourAngle(hour12: Int, minute: Int): Float = (hour12 % 12) * 30f + minute * 0.5f

private fun handTip(center: Offset, length: Float, angleDeg: Float): Offset {
    val rad = Math.toRadians(angleDeg.toDouble())
    return Offset(
        x = center.x + length * sin(rad).toFloat(),
        y = center.y - length * cos(rad).toFloat(),
    )
}

private fun angleDegrees(position: Offset, center: Offset): Float {
    val dx = (position.x - center.x).toDouble()
    val dy = (position.y - center.y).toDouble()
    var deg = Math.toDegrees(atan2(dx, -dy)).toFloat()
    if (deg < 0f) deg += 360f
    return deg
}

private fun angleToMinute(angleDeg: Float): Int {
    var raw = (angleDeg / 6f).roundToInt() % 60
    if (raw < 0) raw += 60
    return raw
}

private fun angleToHour12(angleDeg: Float): Int {
    var raw = (angleDeg / 30f).roundToInt() % 12
    if (raw < 0) raw += 12
    return if (raw == 0) 12 else raw
}

private fun to24Hour(hour12: Int, isPm: Boolean): Int {
    val base = hour12 % 12
    return if (isPm) base + 12 else base
}

private fun formatReadout(hour12: Int, minute: Int, isPm: Boolean): String =
    "%d:%02d %s".format(hour12, minute, if (isPm) "PM" else "AM")

/**
 * Today's date (device local zone) + the selected time — rolling to tomorrow if that combination
 * has already passed, same judgment call the text-field version this replaces already made (a
 * same-day clock time that's already gone has no sensible same-day interpretation).
 */
private fun resolveDueAtEpochMillis(hour24: Int, minute: Int, nowEpochMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
    var candidate = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour24, minute), zone)
    if (candidate.toInstant().toEpochMilli() <= nowEpochMillis) {
        candidate = candidate.plusDays(1)
    }
    return candidate.toInstant().toEpochMilli()
}
