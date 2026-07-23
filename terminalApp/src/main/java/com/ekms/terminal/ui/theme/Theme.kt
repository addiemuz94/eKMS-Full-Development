package com.ekms.terminal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Brand tokens with no direct Material3 [androidx.compose.material3.ColorScheme]
 * slot — Material3 only ships primary/secondary/tertiary/error, not
 * Success/Warning. Read via [LocalEkmsColors]; the values themselves live
 * only in Color.kt.
 */
data class EkmsColors(
    val primary: Color = CavotecPrimary,
    val primaryDark: Color = CavotecPrimaryDark,
    val surface: Color = CavotecSurface,
    val panel: Color = CavotecPanel,
    val textPrimary: Color = CavotecTextPrimary,
    val textSecondary: Color = CavotecTextSecondary,
    val success: Color = CavotecSuccess,
    val warning: Color = CavotecWarning,
    val danger: Color = CavotecDanger,
)

val LocalEkmsColors = staticCompositionLocalOf { EkmsColors() }

/**
 * The status-ring pattern's four tones (CLAUDE.md "Terminal App UX
 * Baseline (Production)") — see [com.ekms.terminal.ui.StatusRingCard],
 * the single reusable Composable every hardware/lifecycle indicator uses.
 */
enum class StatusTone {
    /** Blue — available / normal / connected. */
    NORMAL,

    /** Grey/dimmed — taken / unavailable / disconnected. */
    INACTIVE,

    /** Amber — pending / attention / door open. */
    ATTENTION,

    /** Red — alarm / abandoned-take / error. */
    ALARM,
}

fun StatusTone.ringColor(colors: EkmsColors): Color = when (this) {
    StatusTone.NORMAL -> colors.primary
    StatusTone.INACTIVE -> colors.textSecondary
    StatusTone.ATTENTION -> colors.warning
    StatusTone.ALARM -> colors.danger
}

@Composable
fun EkmsTerminalTheme(content: @Composable () -> Unit) {
    val ekmsColors = EkmsColors()

    // Material3 1.3's ColorScheme has more slots than the 9 tokens the
    // design spec defines (the "surface container" tonal family in
    // particular). Every slot below is derived from those 9 tokens by
    // alpha compositing, not left to lightColorScheme()'s own defaults —
    // an unset slot falls back to Material's baseline purple seed, which
    // visibly bled through on cards that don't set an explicit
    // containerColor (e.g. plain Card()) before this was filled in.
    fun tint(alpha: Float) = ekmsColors.textSecondary.copy(alpha = alpha).compositeOver(ekmsColors.panel)

    val primaryContainer = ekmsColors.primary.copy(alpha = 0.12f).compositeOver(ekmsColors.panel)
    val secondaryContainer = tint(0.06f)
    val tertiaryContainer = ekmsColors.success.copy(alpha = 0.14f).compositeOver(ekmsColors.panel)
    val errorContainer = ekmsColors.danger.copy(alpha = 0.14f).compositeOver(ekmsColors.panel)
    val surfaceVariant = tint(0.08f)
    val outline = ekmsColors.textSecondary.copy(alpha = 0.6f).compositeOver(ekmsColors.panel)
    val outlineVariant = tint(0.25f)

    val colorScheme = lightColorScheme(
        primary = ekmsColors.primary,
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = ekmsColors.primaryDark,
        inversePrimary = ekmsColors.primary.copy(alpha = 0.7f).compositeOver(ekmsColors.textPrimary),
        secondary = ekmsColors.primaryDark,
        onSecondary = Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = ekmsColors.textPrimary,
        tertiary = ekmsColors.success,
        onTertiary = Color.White,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = ekmsColors.textPrimary,
        background = ekmsColors.surface,
        onBackground = ekmsColors.textPrimary,
        surface = ekmsColors.panel,
        onSurface = ekmsColors.textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = ekmsColors.textSecondary,
        surfaceTint = ekmsColors.primary,
        inverseSurface = ekmsColors.textPrimary,
        inverseOnSurface = ekmsColors.panel,
        error = ekmsColors.danger,
        onError = Color.White,
        errorContainer = errorContainer,
        onErrorContainer = ekmsColors.textPrimary,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = ekmsColors.panel,
        surfaceDim = tint(0.06f),
        surfaceContainerLowest = ekmsColors.panel,
        surfaceContainerLow = tint(0.03f),
        surfaceContainer = tint(0.05f),
        surfaceContainerHigh = tint(0.08f),
        surfaceContainerHighest = tint(0.11f),
    )

    CompositionLocalProvider(LocalEkmsColors provides ekmsColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EkmsTypography,
            content = content,
        )
    }
}
