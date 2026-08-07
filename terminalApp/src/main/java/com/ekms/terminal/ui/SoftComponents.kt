package com.ekms.terminal.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.StatusTone
import com.ekms.terminal.ui.theme.readout
import com.ekms.terminal.ui.theme.OutfitFontFamily
import com.ekms.terminal.ui.theme.LocalAudioClick
import com.ekms.terminal.ui.theme.LocalEkmsColors
import com.ekms.terminal.ui.theme.EkmsColors
import com.ekms.terminal.ui.theme.SoftTone
import com.ekms.terminal.ui.theme.softToneColors

private val SoftCardShape = RoundedCornerShape(22.dp)
private val SoftChipShape = RoundedCornerShape(17.dp)
private val SoftButtonShape = RoundedCornerShape(24.dp)

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = 18.dp,
    // Both default to the prior look (flat, no border) so every existing SoftCard call site
    // is unaffected — only call sites that explicitly need visible depth (e.g. SoftScanTile,
    // see its "light mode cards invisible" hardware-bug fix) pass non-default values.
    elevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Click sound wired here (Part 2 of the immersive-mode/click-sound pass) — the single
    // central point for SoftCard's own direct onClick usage plus everything built on top of it
    // (SoftScanTile, SoftNavTile), so those never need their own separate wiring.
    val playClick = LocalAudioClick.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            playClick()
                            onClick()
                        },
                    )
                } else {
                    Modifier
                },
            ),
        shape = SoftCardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = border,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SoftBrandHeader(
    title: String = "eKMS",
    subtitle: String = "Terminal",
) {
    val colors = LocalEkmsColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            // Readability pass: 48dp -> 58dp, corner 16dp -> 19dp (both x1.2, see
            // TERMINAL_TEXT_SCALE's doc in Typography.kt for the same multiplier applied to
            // icon/logo sizing across the app, not just text).
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(
                    Brush.linearGradient(
                        listOf(colors.primary.copy(alpha = 0.95f), colors.primaryDark),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "eK",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = OutfitFontFamily,
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium.readout(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hardware bug fix (Phase 9A pilot, found on a real F7G18P screenshot pass):
 * 1. [icon] is now a real [ImageVector] (Fingerprint/Face/Nfc/VpnKey/Password-style glyphs
 *    from material-icons-extended — those specific icons aren't in material-icons-core, see
 *    build.gradle.kts's dependency-swap comment) instead of a single-letter text fallback.
 * 2. The icon badge's container no longer hardcodes `Color.White` — that literal ignored dark
 *    mode entirely (a fixed white box regardless of theme), which is the root cause of the
 *    "blank/wrong-looking square in dark mode" bug. It now uses
 *    [androidx.compose.material3.ColorScheme.primaryContainer], which Theme.kt already derives
 *    per-mode from the current [EkmsColors] tokens.
 * 3. The outer card itself gets an explicit [border] + small [elevation] (SoftCard's own
 *    defaults stay flat/borderless for every other call site — see SoftCard's doc) — without
 *    this, the not-listening state's `containerColor` (surface/panel) sat almost exactly on
 *    top of the screen's own background color in light mode and was effectively invisible.
 * 4. Light-mode-only polish (Phase 9A follow-up, dark confirmed good on hardware and must not
 *    regress): the resting (non-`listening`) fill used literal `colorScheme.surface`, which
 *    Material3's `Card` auto-tints toward `surfaceTint` (`primary`) based on `elevation` —
 *    but that automatic tonal-elevation overlay is a real, documented M3 behavior difference
 *    that reads as barely perceptible in light theme at the same elevation that gives dark
 *    theme genuine depth. Light mode's resting fill is now an explicit, deliberately-weaker
 *    tint than the icon badge/`listening` state's `primaryContainer`, so the badge stays a
 *    visually distinct accent chip rather than blending into the card — see [restingContainerColorFor].
 *    Dark mode's resting fill is untouched (still the literal `colorScheme.surface` that
 *    already looks right there).
 * 5. [selected] (Phase 9B, added for `TerminalKeyMenuScreen`'s multi-select key tiles) —
 *    a persistent highlighted state, distinct from [listening]'s transient pulse-while-scanning
 *    animation: stronger `primary`-tinted border + `primaryContainer` fill, no animation. A
 *    tile can be `selected` without `listening` (the Key Menu case) or vice versa (the login
 *    screen case) — the two are independent, never combined by any current call site.
 */
private fun restingContainerColorFor(colors: EkmsColors, scheme: androidx.compose.material3.ColorScheme): Color =
    if (colors.isDark) {
        // Unchanged from before this fix — Material3's own tonal-elevation overlay already
        // gives this the right amount of depth in dark theme; do not touch.
        scheme.surface
    } else {
        // A softer tint than primaryContainer's 0.12f (used by the badge/`listening` state)
        // so the badge remains visually distinct from the card it sits on, instead of both
        // resolving to the exact same color.
        colors.primary.copy(alpha = 0.08f).compositeOver(colors.panel)
    }

@Composable
fun SoftScanTile(
    title: String,
    description: String,
    icon: ImageVector,
    listening: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * Full-width single-column rows (TerminalLoginScreen's v3 rework) run far taller than
     * the compact grid tiles this composable was originally sized for — `false` (the
     * default, used by every pre-existing call site, e.g. TerminalKeyMenuScreen's
     * multi-select key tiles) renders pixel-identical to before. `true` scales up the icon
     * / icon-container / title+description typography and centers the whole icon+text
     * block vertically in the tile instead of pinning it to the top, so a tall row doesn't
     * read as an oversized, mostly-empty box around a small icon.
     */
    expanded: Boolean = false,
) {
    val colors = LocalEkmsColors.current
    val pulseScale = if (listening) {
        val transition = rememberInfiniteTransition(label = "scanPulse")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scanPulseScale",
        )
        scale
    } else {
        1f
    }
    // expanded=false sizing is completely independent of expanded=true's — no constant
    // below is shared between the two branches — so TerminalKeyMenuScreen's compact grid
    // tiles (expanded=false, the default) render pixel-identical to before this fix.
    val contentPadding = 16.dp

    SoftCard(
        modifier = modifier.scale(pulseScale),
        onClick = onClick,
        containerColor = if (listening || selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            restingContainerColorFor(colors, MaterialTheme.colorScheme)
        },
        contentPadding = contentPadding,
        elevation = 3.dp,
        border = if (selected) {
            BorderStroke(2.dp, colors.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        if (expanded) {
            // Root cause of the subtitle-clipping bug (confirmed via hardware photo): the
            // v4 pass sized the icon/title/subtitle up (64dp icon + titleLarge + bodyLarge)
            // without checking that against the tile's actual assigned height — v3's
            // TerminalLoginScreen arrangement gives each of 5 stacked tiles an equal,
            // fairly short share of the screen (Column/weight, untouched by this fix), and
            // that fixed content sizing simply didn't fit inside it. SoftCard's Surface
            // clips to its own shape (it has elevation), so the overflowing subtitle got
            // silently cut off at the tile's bottom edge instead of visibly overflowing.
            //
            // Fix: measure the tile's real available content height via
            // BoxWithConstraints (this is exactly "available tile height" from the task,
            // not a guess) and choose between two fully-independent size tiers instead of
            // one fixed oversized one — COMFORTABLE when there's room, or FALLBACK
            // (identical to the expanded=false sizing below, already proven to fit
            // wherever that's used) otherwise. Either tier's icon+title+subtitle block is
            // then centered as a group via fillMaxHeight() + Arrangement.Center, same as
            // the v4 pass intended.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                // Readability pass (recalculated from scratch, NOT the old numbers carried
                // forward — the prior threshold's margin was already near-zero, and every
                // input to this math just grew ~20%, so reusing it would very likely
                // reintroduce the subtitle-clipping bug this fix exists to prevent):
                // Comfortable tier's worst-case content height at the new sizes below —
                // 67 (icon) + 17 (gap) + 29 (titleMedium line height, TERMINAL_TEXT_SCALE-
                // scaled 24sp -> ~28.8sp) + 24 (bodyMedium line height, scaled 20sp ->
                // ~24sp) = 137dp, plus 2 * contentPadding (32dp, unscaled — this pass is
                // font/icon size only, not spacing) = 169dp actual worst-case need.
                // Threshold set to 190dp, not just-above-169dp: the old ~4dp margin (146
                // needed vs. 150 threshold) was flagged as "near-zero" even before this
                // pass, so this deliberately keeps a real ~21dp cushion instead of
                // preserving that same thin margin at the new, larger scale.
                val useComfortableTier = maxHeight >= 190.dp
                val iconBoxSize = if (useComfortableTier) 67.dp else 53.dp
                val iconBoxCorner = if (useComfortableTier) 19.dp else 17.dp
                val iconGlyphSize = if (useComfortableTier) 36.dp else 29.dp
                val iconTextGap = if (useComfortableTier) 17.dp else 12.dp
                val titleStyle = if (useComfortableTier) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleSmall
                }
                val descriptionStyle = if (useComfortableTier) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodySmall
                }
                SoftScanTileContent(
                    icon = icon,
                    title = title,
                    description = description,
                    iconBoxSize = iconBoxSize,
                    iconBoxCorner = iconBoxCorner,
                    iconGlyphSize = iconGlyphSize,
                    iconTextGap = iconTextGap,
                    titleStyle = titleStyle,
                    descriptionStyle = descriptionStyle,
                    iconTint = colors.primary,
                    columnModifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                )
            }
        } else {
            // Readability pass: same x1.2 scale as the expanded/comfortable tier above
            // (44/14/24/10 -> 53/17/29/12) — this tier backs both the expanded=true
            // fallback (new worst-case content ~140dp incl. padding, still comfortably
            // under the 190dp comfortable threshold above) and every expanded=false call
            // site (TerminalKeyMenuScreen's key-selection tiles), which sit in a
            // self-sizing LazyVerticalGrid (GridCells.Adaptive), not a fixed-height
            // container — no clipping risk there regardless of size.
            SoftScanTileContent(
                icon = icon,
                title = title,
                description = description,
                iconBoxSize = 53.dp,
                iconBoxCorner = 17.dp,
                iconGlyphSize = 29.dp,
                iconTextGap = 12.dp,
                titleStyle = MaterialTheme.typography.titleSmall,
                descriptionStyle = MaterialTheme.typography.bodySmall,
                iconTint = colors.primary,
                columnModifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Top,
            )
        }
    }
}

/**
 * Shared icon+title+description block for [SoftScanTile]'s two branches — pulled out only
 * to avoid duplicating the `Box`/`Icon`/`Text`/`Text` structure between the `expanded` and
 * non-`expanded` paths; every actual size/style value is still passed in by the caller, so
 * this holds no sizing constants of its own and the two branches remain fully independent.
 */
@Composable
private fun SoftScanTileContent(
    icon: ImageVector,
    title: String,
    description: String,
    iconBoxSize: Dp,
    iconBoxCorner: Dp,
    iconGlyphSize: Dp,
    iconTextGap: Dp,
    titleStyle: androidx.compose.ui.text.TextStyle,
    descriptionStyle: androidx.compose.ui.text.TextStyle,
    iconTint: Color,
    columnModifier: Modifier,
    verticalArrangement: Arrangement.Vertical,
) {
    Column(
        modifier = columnModifier,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(iconBoxSize)
                .clip(RoundedCornerShape(iconBoxCorner))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconGlyphSize),
            )
        }
        Spacer(modifier = Modifier.height(iconTextGap))
        Text(
            text = title,
            style = titleStyle,
            // Explicit rather than relying on Card's contentColorFor(containerColor)
            // auto-resolution: that only matches when containerColor is exactly one of
            // ColorScheme's named roles (e.g. the literal `surface` dark mode still passes),
            // and light mode's resting fill above is now a custom blended Color that won't
            // match any role — leaving this implicit would leave the title's color undefined
            // in light mode. onSurface resolves to the same textPrimary token this already
            // rendered with before, in both modes, so this is a no-visual-change safety fix.
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = description,
            style = descriptionStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SoftPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val playClick = LocalAudioClick.current
    Button(
        onClick = { playClick(); onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled && !loading,
        shape = SoftButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            // Readability pass: 18dp -> 22dp (x1.2). The trailing Spacer is general layout
            // spacing, not icon sizing, and stays untouched per this pass's scope.
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Phase 9B fix: background/foreground now resolve via [softToneColors] instead of referencing
 * `SoftSuccessContainer`/`SoftWarningContainer` directly — those are light-mode-only hex with
 * no dark-mode counterpart (flagged during the Phase 9A audit). Signature unchanged so every
 * existing call site (across `TerminalAdminApp.kt`, `StartupDiagnosticsScreen.kt`,
 * `HardwareStatusPage.kt`, `TerminalPasskeyLoginScreen.kt`) keeps compiling as-is.
 */
@Composable
fun SoftAssistChip(
    text: String,
    attention: Boolean = false,
    success: Boolean = false,
) {
    val tone = when {
        success -> softToneColors(SoftTone.SUCCESS)
        attention -> softToneColors(SoftTone.WARNING)
        else -> null
    }
    val background = tone?.container ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = tone?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(SoftChipShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun SoftHeroAction(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEkmsColors.current
    val playClick = LocalAudioClick.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.95f),
                        colors.primary,
                        colors.primaryDark,
                    ),
                ),
            )
            .clickable(onClick = { playClick(); onClick() })
            .padding(18.dp),
    ) {
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun SoftNavTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SoftCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = 16.dp,
    ) {
        // Readability pass: 40dp -> 48dp box, corner 14dp -> 17dp (both x1.2).
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(2),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Phase 9F fix: the default look (no border, `0.dp` elevation) had the same "may blend into the
 * light-mode background" issue Phase 9A found and fixed in [SoftScanTile] — flagged rather than
 * fixed in Phase 9E because [strongAttention] (added that pass) only opted in for one state;
 * every other call site (`TerminalKeyReturnScreen`, `TerminalNfcCardLoginScreen`,
 * `TerminalFingerprintLoginScreen`, and this screen's own non-urgent states) still had the
 * unfixed default. The default itself is now a visible `1.dp` `outlineVariant` border + `3.dp`
 * elevation (the same recipe `SoftScanTile` uses). `strongAttention`'s `2.dp` `colors.warning`
 * border + `4.dp` elevation stays deliberately stronger than that new default in both width and
 * color, so Key Take Flow's door-left-open state still reads as more urgent than an ordinary
 * wait, not just equally-bordered.
 *
 * @param strongAttention Added in Phase 9E — when `true`, escalates beyond the (now-visible)
 * default border/elevation using `colors.warning` — reusing the existing warning tone, not a
 * new color — for the one state that needs to read as more urgent than an ordinary
 * [StatusTone.ATTENTION] wait (Key Take Flow's door-left-open warning: the door is physically
 * open and the timer already expired, not just "still waiting normally"). Only
 * `TerminalKeyTakeScreen` passes `true` today.
 */
@Composable
fun SoftWaitPanel(
    tone: StatusTone,
    title: String,
    message: String,
    showProgress: Boolean = false,
    assistText: String? = null,
    assistAttention: Boolean = false,
    strongAttention: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEkmsColors.current
    SoftCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentPadding = 28.dp,
        elevation = if (strongAttention) 4.dp else 3.dp,
        border = if (strongAttention) {
            BorderStroke(2.dp, colors.warning)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showProgress) {
                // Readability pass: 48dp -> 58dp (x1.2).
                CircularProgressIndicator(
                    modifier = Modifier.size(58.dp),
                    strokeWidth = 4.dp,
                    color = when (tone) {
                        StatusTone.ALARM -> colors.danger
                        StatusTone.ATTENTION -> colors.warning
                        else -> colors.primary
                    },
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (assistText != null) {
                SoftAssistChip(
                    text = assistText,
                    attention = assistAttention || tone == StatusTone.ATTENTION,
                    success = tone == StatusTone.NORMAL && !assistAttention,
                )
            }
        }
    }
}

@Composable
fun SoftTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playClick = LocalAudioClick.current
    TextButton(onClick = { playClick(); onClick() }, modifier = modifier.fillMaxWidth()) {
        Text(text)
    }
}
