package com.ekms.terminal.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.LocalEkmsColors
import com.ekms.terminal.ui.theme.StatusTone
import com.ekms.terminal.ui.theme.ringColor

/**
 * The single reusable "status ring" pattern (CLAUDE.md "Terminal App UX
 * Baseline (Production)" visual theme rework). Every hardware/lifecycle
 * state indicator in terminalApp — key tiles, connection indicators,
 * admin device health, alert/notification surfaces — renders through this
 * one Composable rather than each screen inventing its own color logic,
 * so the visual language and the state it represents stay in sync by
 * construction: pass the [StatusTone] the caller already computed from
 * real state, never re-derive a color from scratch at the call site.
 *
 * [StatusTone.INACTIVE] is additionally rendered at reduced content alpha
 * ("dimmed", per the design spec) on top of the grey ring, so a taken/
 * disconnected tile reads as visually receded, not just differently
 * bordered.
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
    val ring = tone.ringColor(colors)
    val contentAlpha = if (tone == StatusTone.INACTIVE) 0.6f else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, ring),
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
