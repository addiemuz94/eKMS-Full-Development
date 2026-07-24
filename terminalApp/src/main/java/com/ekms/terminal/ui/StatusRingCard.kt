package com.ekms.terminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.LocalEkmsColors
import com.ekms.terminal.ui.theme.StatusTone
import com.ekms.terminal.ui.theme.ringColor
import androidx.compose.ui.graphics.compositeOver

/**
 * Soft Material 3 status surface — tone via fill, not a hard border ring.
 * [StatusTone.INACTIVE] stays dimmed so taken/disconnected tiles recede.
 */
@Composable
fun StatusRingCard(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEkmsColors.current
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        StatusTone.NORMAL -> scheme.primaryContainer
        StatusTone.INACTIVE -> scheme.surfaceContainer
        StatusTone.ATTENTION -> colors.warning.copy(alpha = 0.18f).compositeOver(scheme.surface)
        StatusTone.ALARM -> scheme.errorContainer
    }
    val contentAlpha = if (tone == StatusTone.INACTIVE) 0.65f else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .alpha(contentAlpha),
        ) {
            content()
        }
    }
}

/** Kept for call sites that still want the tone color itself. */
@Composable
fun statusToneColor(tone: StatusTone): Color {
    val colors = LocalEkmsColors.current
    return tone.ringColor(colors)
}
