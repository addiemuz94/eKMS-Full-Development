package com.ekms.terminal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ekms.terminal.R

/**
 * Cavotec theme fonts (visual theme rework — CLAUDE.md "Terminal App UX
 * Baseline (Production)"). Two distinct voices, by design:
 * - Inter for UI text — labels, buttons, body copy, everything
 *   conversational. This is [MaterialTheme.typography]'s default, so it
 *   applies everywhere automatically without each screen opting in.
 * - IBM Plex Mono for data readouts — node addresses, timestamps, key
 *   IDs, the cabinet ID/MAC display — giving hardware/technical values a
 *   distinct visual voice from the surrounding UI text. This is opt-in:
 *   apply [DataReadoutTextStyle] explicitly on the specific `Text()` call
 *   showing the raw value, not the whole screen.
 *
 * Only Regular and Medium weights are bundled for each family (see
 * res/font/) — Bold/SemiBold requests fall back to Android's synthetic
 * (faux) bold, which is correct but not a true bold face. Add
 * inter_semibold.ttf/inter_bold.ttf/plex_mono_semibold.ttf if real bold
 * weights are supplied later; no code change needed beyond adding those
 * [Font] entries below.
 */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
)

val PlexMonoFontFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

private val defaultTypography = Typography()

val EkmsTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = InterFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = InterFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = InterFontFamily),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = InterFontFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = InterFontFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = InterFontFamily),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = InterFontFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
)

/** Apply explicitly to a `Text()` showing a raw hardware/technical value — see class doc. */
val DataReadoutTextStyle = TextStyle(
    fontFamily = PlexMonoFontFamily,
    fontWeight = FontWeight.Medium,
)
