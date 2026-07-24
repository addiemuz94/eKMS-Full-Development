package com.ekms.terminal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.terminal.hardware.CabinetHardwareState
import com.ekms.terminal.ui.theme.LocalEkmsColors
import com.ekms.terminal.ui.theme.SoftSuccessOnContainer
import com.ekms.terminal.ui.theme.SoftSuccessContainer
import com.ekms.terminal.ui.theme.SoftWarningContainer
import com.ekms.terminal.ui.theme.SoftWarningOnContainer
import com.ekms.terminal.ui.theme.readout

/**
 * Hardware Control → Status page: cabinet illustration, live readout,
 * problem suggestions, and Connect / Disconnect.
 */
@Composable
fun HardwareStatusPage(
    hardwareState: CabinetHardwareState,
    portPath: String,
    onPortPathChange: (String) -> Unit,
    baudRateText: String,
    onBaudRateChange: (String) -> Unit,
    boxAddressText: String,
    onBoxAddressChange: (String) -> Unit,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connecting = hardwareState.busy && !hardwareState.connected
    val hints = connectionHints(hardwareState)

    SoftCard(contentPadding = 20.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SoftAssistChip(
                text = when {
                    hardwareState.connected -> "Connected"
                    connecting -> "Connecting…"
                    else -> "Disconnected"
                },
                success = hardwareState.connected,
                attention = !hardwareState.connected,
            )

            CabinetConnectionIllustration(
                connected = hardwareState.connected,
                busy = hardwareState.busy,
            )

            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )
            }

            Text(
                text = hardwareState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${hardwareState.portPath} @ ${hardwareState.baudRate} · Box ${hardwareState.boxAddress}",
                style = MaterialTheme.typography.bodySmall.readout(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Text(
        text = if (hardwareState.connected) "Status checks" else "Where to look",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )

    hints.forEach { hint ->
        ConnectionHintCard(hint)
    }

    if (!hardwareState.connected) {
        SoftCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftStatusField(
                    value = portPath,
                    onValueChange = onPortPathChange,
                    label = "Cabinet serial port",
                )
                SoftStatusField(
                    value = baudRateText,
                    onValueChange = { onBaudRateChange(it.filter(Char::isDigit)) },
                    label = "Baud rate",
                )
                SoftStatusField(
                    value = boxAddressText,
                    onValueChange = { onBoxAddressChange(it.filter(Char::isDigit)) },
                    label = "Box Address (1–255)",
                )
                SoftPrimaryButton(
                    text = if (connecting) "Connecting…" else "Connect cabinet",
                    onClick = onConnect,
                    enabled = canConnect,
                    loading = connecting,
                )
            }
        }
    } else {
        SoftPrimaryButton(
            text = "Disconnect cabinet",
            onClick = onDisconnect,
            enabled = !hardwareState.busy,
        )
    }
}

@Composable
private fun ConnectionHintCard(hint: ConnectionHint) {
    val container = when (hint.severity) {
        HintSeverity.LIKELY -> SoftWarningContainer
        HintSeverity.CHECK -> MaterialTheme.colorScheme.surfaceContainerLow
        HintSeverity.OK -> SoftSuccessContainer
    }
    val titleColor = when (hint.severity) {
        HintSeverity.LIKELY -> SoftWarningOnContainer
        HintSeverity.CHECK -> MaterialTheme.colorScheme.onSurface
        HintSeverity.OK -> SoftSuccessOnContainer
    }
    SoftCard(containerColor = container, contentPadding = 14.dp) {
        Text(
            text = when (hint.severity) {
                HintSeverity.LIKELY -> "Likely · ${hint.title}"
                HintSeverity.CHECK -> "Check · ${hint.title}"
                HintSeverity.OK -> "OK · ${hint.title}"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = hint.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CabinetConnectionIllustration(
    connected: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEkmsColors.current
    val scheme = MaterialTheme.colorScheme
    val cabinetFill = when {
        connected -> colors.primary.copy(alpha = 0.22f)
        busy -> scheme.surfaceContainerHigh
        else -> scheme.surfaceContainer
    }
    val cabinetStroke = when {
        connected -> colors.primary
        busy -> colors.primary.copy(alpha = 0.45f)
        else -> scheme.outlineVariant
    }
    val cableColor = when {
        connected -> SoftSuccessOnContainer
        else -> SoftWarningOnContainer
    }
    val terminalFill = scheme.surface
    val dashed = !connected && !busy

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)

        // Terminal (left)
        val termLeft = w * 0.06f
        val termTop = h * 0.22f
        val termW = w * 0.28f
        val termH = h * 0.56f
        drawRoundRect(
            color = terminalFill,
            topLeft = Offset(termLeft, termTop),
            size = Size(termW, termH),
            cornerRadius = CornerRadius(16.dp.toPx()),
        )
        drawRoundRect(
            color = cabinetStroke,
            topLeft = Offset(termLeft, termTop),
            size = Size(termW, termH),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = stroke,
        )
        // Screen inset
        drawRoundRect(
            color = colors.primary.copy(alpha = if (connected) 0.35f else 0.12f),
            topLeft = Offset(termLeft + termW * 0.12f, termTop + termH * 0.14f),
            size = Size(termW * 0.76f, termH * 0.42f),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )

        // Cabinet (right)
        val cabLeft = w * 0.52f
        val cabTop = h * 0.12f
        val cabW = w * 0.40f
        val cabH = h * 0.72f
        drawRoundRect(
            color = cabinetFill,
            topLeft = Offset(cabLeft, cabTop),
            size = Size(cabW, cabH),
            cornerRadius = CornerRadius(18.dp.toPx()),
        )
        drawRoundRect(
            color = cabinetStroke,
            topLeft = Offset(cabLeft, cabTop),
            size = Size(cabW, cabH),
            cornerRadius = CornerRadius(18.dp.toPx()),
            style = stroke,
        )
        // Door panel
        drawRoundRect(
            color = Color.White.copy(alpha = if (connected) 0.55f else 0.25f),
            topLeft = Offset(cabLeft + cabW * 0.14f, cabTop + cabH * 0.16f),
            size = Size(cabW * 0.72f, cabH * 0.68f),
            cornerRadius = CornerRadius(10.dp.toPx()),
        )
        // Key slots suggestion
        val slotY = cabTop + cabH * 0.28f
        for (i in 0 until 3) {
            val y = slotY + i * (cabH * 0.18f)
            drawRoundRect(
                color = if (connected) colors.primary.copy(alpha = 0.45f) else scheme.outlineVariant,
                topLeft = Offset(cabLeft + cabW * 0.28f, y),
                size = Size(cabW * 0.44f, cabH * 0.08f),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }

        // Serial cable between devices
        val cableStart = Offset(termLeft + termW, termTop + termH * 0.55f)
        val cableEnd = Offset(cabLeft, cabTop + cabH * 0.55f)
        val mid = Offset((cableStart.x + cableEnd.x) / 2f, cableStart.y + h * 0.08f)
        // Simple two-segment path via lines through mid
        drawLine(
            color = cableColor,
            start = cableStart,
            end = mid,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
        )
        drawLine(
            color = cableColor,
            start = mid,
            end = cableEnd,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
        )

        // Port dots
        drawCircle(color = cableColor, radius = 5.dp.toPx(), center = cableStart)
        drawCircle(color = cableColor, radius = 5.dp.toPx(), center = cableEnd)

        if (!connected && !busy) {
            // Break mark on cable
            drawLine(
                color = SoftWarningOnContainer,
                start = Offset(mid.x - 10.dp.toPx(), mid.y - 10.dp.toPx()),
                end = Offset(mid.x + 10.dp.toPx(), mid.y + 10.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = SoftWarningOnContainer,
                start = Offset(mid.x + 10.dp.toPx(), mid.y - 10.dp.toPx()),
                end = Offset(mid.x - 10.dp.toPx(), mid.y + 10.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SoftStatusField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    )
}
