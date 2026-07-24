package com.ekms.terminal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ekms.terminal.R

/**
 * Terminal type: Outfit for UI, IBM Plex Mono for hardware/technical readouts.
 *
 * Apply [DataReadoutTextStyle] (or [TextStyle.readout]) only on the value itself —
 * node addresses, MAC, cabinet ID, timestamps — not whole screens.
 */
val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
)

val PlexMonoFontFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

private val base = Typography()

private fun TextStyle.outfit(weight: FontWeight? = null): TextStyle =
    copy(fontFamily = OutfitFontFamily, fontWeight = weight ?: fontWeight)

val EkmsTypography = Typography(
    displayLarge = base.displayLarge.outfit(),
    displayMedium = base.displayMedium.outfit(),
    displaySmall = base.displaySmall.outfit(),
    headlineLarge = base.headlineLarge.outfit(),
    headlineMedium = base.headlineMedium.outfit(),
    headlineSmall = base.headlineSmall.outfit(),
    titleLarge = base.titleLarge.outfit(),
    titleMedium = base.titleMedium.outfit(FontWeight.Medium),
    titleSmall = base.titleSmall.outfit(FontWeight.Medium),
    bodyLarge = base.bodyLarge.outfit(),
    bodyMedium = base.bodyMedium.outfit(),
    bodySmall = base.bodySmall.outfit(),
    labelLarge = base.labelLarge.outfit(FontWeight.Medium),
    labelMedium = base.labelMedium.outfit(FontWeight.Medium),
    labelSmall = base.labelSmall.outfit(FontWeight.Medium),
)

/** Mono style for a raw hardware/technical value. */
val DataReadoutTextStyle = TextStyle(
    fontFamily = PlexMonoFontFamily,
    fontWeight = FontWeight.Medium,
)

/** Merge mono readout onto an existing Material text style. */
fun TextStyle.readout(): TextStyle = merge(DataReadoutTextStyle)
