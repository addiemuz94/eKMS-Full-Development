package com.ekms.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Cavotec-branded Material3 theme for mobileApp.
 *
 * DUPLICATED from terminalApp's `ui/theme/` rather than extracted into a genuinely shared
 * Compose module — flagged deliberately, not an oversight. This mobileApp foundation pass
 * (Digital Key hardware-feasibility investigation cleanup) is explicitly scoped
 * "terminalApp revert-only": terminalApp must end this pass byte-for-byte identical to before
 * the investigation started. A real shared theme module would require moving
 * terminalApp/.../ui/theme/{Color,Typography,Theme}.kt into a new module and repointing
 * terminalApp's own imports at it — exactly the kind of terminalApp change "revert-only"
 * rules out. Duplicating the token values (Color.kt) and this same color-scheme-derivation
 * approach is the acceptable fallback named for this situation. If/when terminalApp work is
 * back in scope for a future pass, extracting a real `:uiTheme`-style shared module (Android
 * library, Compose enabled, depended on by both apps) is the natural next step — do that then,
 * not by quietly expanding scope here.
 *
 * Deliberately narrower than terminalApp's Theme.kt: dropped `SoftTone`/`softToneColors`/
 * `StatusTone` (terminal-hardware status-ring/assist-chip concepts — key-slot NORMAL/INACTIVE/
 * ATTENTION/ALARM states, hardware connection chips) since mobileApp has no equivalent concept
 * yet at foundation stage. Add them back here (not by copy-pasting into a screen file) if a
 * future mobileApp screen genuinely needs the same tone language.
 */
data class EkmsMobileColors(
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
    val isDark: Boolean = false,
)

val LightEkmsMobileColors = EkmsMobileColors()

val DarkEkmsMobileColors = EkmsMobileColors(
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

@Composable
fun EkmsMobileTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ekmsColors = if (darkTheme) DarkEkmsMobileColors else LightEkmsMobileColors

    fun tint(alpha: Float) = ekmsColors.textSecondary.copy(alpha = alpha).compositeOver(ekmsColors.panel)

    val primaryContainer = ekmsColors.primary.copy(alpha = 0.12f).compositeOver(ekmsColors.panel)
    val secondaryContainer = tint(0.06f)
    val tertiaryContainer = ekmsColors.success.copy(alpha = 0.14f).compositeOver(ekmsColors.panel)
    val errorContainer = ekmsColors.danger.copy(alpha = 0.14f).compositeOver(ekmsColors.panel)
    val surfaceVariant = tint(0.08f)
    val outline = ekmsColors.textSecondary.copy(alpha = 0.6f).compositeOver(ekmsColors.panel)
    val outlineVariant = tint(0.25f)
    val onBrand = if (darkTheme) ekmsColors.textPrimary else Color.White

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = ekmsColors.primary,
            onPrimary = onBrand,
            primaryContainer = primaryContainer,
            onPrimaryContainer = ekmsColors.primaryDark,
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EkmsMobileTypography,
        content = content,
    )
}
