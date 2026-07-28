package com.ekms.terminal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Brand tokens with no direct Material3 [androidx.compose.material3.ColorScheme]
 * slot — Material3 only ships primary/secondary/tertiary/error, not
 * Success/Warning/Info. Read via [LocalEkmsColors]; the values themselves live
 * only in Color.kt.
 */
data class EkmsColors(
    val primary: Color = CavotecBlue,
    val primaryDark: Color = CavotecBlueDark,
    val accent: Color = CavotecBlueLight,
    val surface: Color = CavotecSurfaceLight,
    val panel: Color = CavotecPanelLight,
    val textPrimary: Color = CavotecTextPrimaryLight,
    val textSecondary: Color = CavotecTextSecondaryLight,
    val success: Color = CavotecSuccess,
    val warning: Color = CavotecWarning,
    val danger: Color = CavotecDanger,
    val info: Color = CavotecInfo,
    /**
     * True for [DarkEkmsColors]. Lets a component branch on the *actually active* theme
     * (which may be a local override, not the system setting) without needing its own
     * `darkTheme: Boolean` param threaded in from [EkmsTerminalTheme] — see SoftScanTile's
     * light-mode-only fill fix for the motivating case: Material3's own automatic tonal-
     * elevation overlay (applied when a Card's containerColor is literally
     * `colorScheme.surface`) reads as good, visible depth in dark theme but is barely
     * perceptible in light theme at the same elevation — a real, documented M3 behavior
     * difference, not a bug in this codebase — so light mode needs an explicit tinted fill
     * where dark mode does not.
     */
    val isDark: Boolean = false,
)

/** Light-mode token set — same values [EkmsColors]'s defaults already used. */
val LightEkmsColors = EkmsColors()

/**
 * Dark-mode token set. Surfaces/text swap to the dark pairs; primary/accent and the
 * four semantic tones swap to their `OnDark`-suffixed, contrast-adjusted variants
 * (see Color.kt doc) rather than reusing the light-mode hues as-is.
 */
val DarkEkmsColors = EkmsColors(
    primary = CavotecBlueLight,
    primaryDark = CavotecBlue,
    accent = CavotecBlueLight,
    surface = CavotecSurfaceDark,
    panel = CavotecPanelDark,
    textPrimary = CavotecTextPrimaryDark,
    textSecondary = CavotecTextSecondaryDark,
    success = CavotecSuccessOnDark,
    warning = CavotecWarningOnDark,
    danger = CavotecDangerOnDark,
    info = CavotecInfoOnDark,
    isDark = true,
)

val LocalEkmsColors = staticCompositionLocalOf { EkmsColors() }

/**
 * Semantic tones with a dedicated "soft chip/card" fill (Phase 9B fix) — success/warning/info.
 * Danger isn't here because Material3's own `errorContainer`/`onErrorContainer` (built in
 * [EkmsTerminalTheme] from `ekmsColors.danger`) is already theme-correct in both modes; these
 * three exist only because Material3 doesn't ship success/warning/info container roles.
 */
enum class SoftTone { SUCCESS, WARNING, INFO }

/** A resolved [SoftTone]'s background + foreground pair. */
data class SoftToneColors(val container: Color, val onContainer: Color)

/**
 * Resolves a [SoftTone] to the correct light/dark pair for the *actually active* theme (reads
 * [LocalEkmsColors.current.isDark], the same flag introduced for the Phase 9A SoftScanTile
 * fill fix — not [isSystemInDarkTheme], since the active theme may be a local override).
 *
 * Fixes the Phase 9A-flagged bug: [SoftAssistChip]/[com.ekms.terminal.ui.ConnectionHintCard]
 * used to reference [SoftSuccessContainer]/[SoftWarningContainer] directly — literal
 * light-mode-only hex with no dark-mode counterpart at all. Every call site now goes through
 * this function instead of touching the raw Color.kt constants directly, so a future tone
 * addition only needs one new `when` arm here rather than a fix repeated per call site.
 */
@Composable
fun softToneColors(tone: SoftTone): SoftToneColors {
    val dark = LocalEkmsColors.current.isDark
    return when (tone) {
        SoftTone.SUCCESS -> if (dark) {
            SoftToneColors(SoftSuccessContainerDark, SoftSuccessOnContainerDark)
        } else {
            SoftToneColors(SoftSuccessContainer, SoftSuccessOnContainer)
        }
        SoftTone.WARNING -> if (dark) {
            SoftToneColors(SoftWarningContainerDark, SoftWarningOnContainerDark)
        } else {
            SoftToneColors(SoftWarningContainer, SoftWarningOnContainer)
        }
        SoftTone.INFO -> if (dark) {
            SoftToneColors(SoftInfoContainerDark, SoftInfoOnContainerDark)
        } else {
            SoftToneColors(SoftInfoContainer, SoftInfoOnContainer)
        }
    }
}

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

/**
 * @param darkTheme Defaults to the system setting on first composition; callers that
 * thread a persisted [com.ekms.terminal.data.TerminalThemeMode] override (Phase 9 —
 * device-local, same pattern as serverAddress/activationCode, never backend-synced)
 * pass the resolved boolean explicitly instead of relying on this default.
 */
@Composable
fun EkmsTerminalTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ekmsColors = if (darkTheme) DarkEkmsColors else LightEkmsColors

    // Material3 1.3's ColorScheme has more slots than the tokens the design spec
    // defines (the "surface container" tonal family in particular). Every slot below
    // is derived from those tokens by alpha compositing, not left to
    // lightColorScheme()/darkColorScheme()'s own defaults — an unset slot falls back
    // to Material's baseline purple seed, which visibly bled through on cards that
    // don't set an explicit containerColor (e.g. plain Card()) before this was filled in.
    fun tint(alpha: Float) = ekmsColors.textSecondary.copy(alpha = alpha).compositeOver(ekmsColors.panel)

    val primaryContainer = ekmsColors.primary.copy(alpha = 0.12f).compositeOver(ekmsColors.panel)
    val secondaryContainer = tint(0.06f)
    val tertiaryContainer = ekmsColors.success.copy(alpha = 0.14f).compositeOver(ekmsColors.panel)
    val errorContainer = ekmsColors.danger.copy(alpha = 0.14f).compositeOver(ekmsColors.panel)
    val surfaceVariant = tint(0.08f)
    val outline = ekmsColors.textSecondary.copy(alpha = 0.6f).compositeOver(ekmsColors.panel)
    val outlineVariant = tint(0.25f)
    val onBrand = if (darkTheme) ekmsColors.textPrimary else Color.White

    // lightColorScheme()/darkColorScheme() take identical named parameters but are two
    // distinct top-level functions — named-argument calls need the literal function,
    // not a variable holding a function reference, hence the duplicated call below
    // rather than picking one function value up front.
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = ekmsColors.primary,
            onPrimary = onBrand,
            primaryContainer = primaryContainer,
            onPrimaryContainer = ekmsColors.primaryDark,
            inversePrimary = ekmsColors.primary.copy(alpha = 0.7f).compositeOver(ekmsColors.textPrimary),
            secondary = ekmsColors.primaryDark,
            onSecondary = onBrand,
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
    } else {
        lightColorScheme(
            primary = ekmsColors.primary,
            onPrimary = onBrand,
            primaryContainer = primaryContainer,
            onPrimaryContainer = ekmsColors.primaryDark,
            inversePrimary = ekmsColors.primary.copy(alpha = 0.7f).compositeOver(ekmsColors.textPrimary),
            secondary = ekmsColors.primaryDark,
            onSecondary = onBrand,
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
    }

    CompositionLocalProvider(LocalEkmsColors provides ekmsColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EkmsTypography,
            content = content,
        )
    }
}
