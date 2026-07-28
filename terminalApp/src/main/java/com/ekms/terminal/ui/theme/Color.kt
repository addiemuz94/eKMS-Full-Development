package com.ekms.terminal.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Soft Material 3 tokens (Cavotec-mapped). Screens use [EkmsColors] /
 * [MaterialTheme.colorScheme] — no raw hex outside this file.
 *
 * Two brand blues, unified (Phase 9 design-system rework): [CavotecBlue] (#0055A5,
 * the same primary `web/` already uses) is now the system PRIMARY; the previous
 * terminal-only primary, [CavotecBlueLight] (#5B7FC4), becomes the lighter
 * ACCENT/secondary tone. Neither value was dropped — both are named tokens so a
 * future pass can still reach either deliberately.
 */
val CavotecBlue = Color(0xFF0055A5)
val CavotecBlueDark = Color(0xFF003C78)
val CavotecBlueLight = Color(0xFF5B7FC4)

/** Legacy names kept as aliases so any not-yet-migrated call site still resolves. */
val CavotecPrimary = CavotecBlue
val CavotecPrimaryDark = CavotecBlueDark

// --- Light scheme surfaces / text --------------------------------------------------
val CavotecSurfaceLight = Color(0xFFF5F6FA)
val CavotecPanelLight = Color(0xFFFFFFFF)
val CavotecTextPrimaryLight = Color(0xFF2A3038)
val CavotecTextSecondaryLight = Color(0xFF6B7380)

// --- Dark scheme surfaces / text ----------------------------------------------------
val CavotecSurfaceDark = Color(0xFF14161A)
val CavotecPanelDark = Color(0xFF1E2126)
val CavotecTextPrimaryDark = Color(0xFFEDEFF2)
val CavotecTextSecondaryDark = Color(0xFFA7AEB8)

// --- Semantic tokens: success / warning / danger / info ------------------------------
// Light-mode tones (also used as the existing defaults elsewhere in the codebase).
val CavotecSuccess = Color(0xFF2E9E5B)
val CavotecWarning = Color(0xFFE0A430)
val CavotecDanger = Color(0xFFD64545)
val CavotecInfo = Color(0xFF3B8FD1)

// Dark-mode tones — lightened/desaturated slightly so each still reads with enough
// contrast against the near-black surfaces above (a straight reuse of the light
// values would look muddy on dark backgrounds).
val CavotecSuccessOnDark = Color(0xFF4CBB7B)
val CavotecWarningOnDark = Color(0xFFE8B65A)
val CavotecDangerOnDark = Color(0xFFE2685F)
val CavotecInfoOnDark = Color(0xFF64AEE0)

// --- Soft chip/card fills for SoftAssistChip/ConnectionHintCard (Phase 9B fix) -------
// Each tone has an explicit light AND dark pair now — see Theme.kt's `softToneColors()`,
// which is what actually branches on the active theme; these are just the raw values.
// Light-mode values are unchanged from before Phase 9B (they were already correct there,
// per the Phase 9A audit finding — only dark mode was ever missing).
val SoftSuccessContainer = Color(0xFFE7F5EC)
val SoftSuccessOnContainer = Color(0xFF1F6B3C)
val SoftWarningContainer = Color(0xFFFFF4DE)
val SoftWarningOnContainer = Color(0xFF8A6410)

// Dark-mode success/warning containers: CavotecSuccessOnDark/CavotecWarningOnDark
// composited at 14% (the same alpha Theme.kt's own tertiaryContainer/errorContainer use)
// over CavotecPanelDark. onContainer reuses the OnDark tone directly — already
// contrast-picked to read against a near-black panel, verified ~5.2:1 against these
// containers (comfortably above the 4.5:1 AA target for normal text).
val SoftSuccessContainerDark = Color(0xFF243732)
val SoftSuccessOnContainerDark = CavotecSuccessOnDark
val SoftWarningContainerDark = Color(0xFF3A362D)
val SoftWarningOnContainerDark = CavotecWarningOnDark

// Info soft chip/card fill — new in Phase 9B (the token itself shipped in Phase 9A but was
// never wired to an actual container/onContainer pair). Light onContainer is a hand-picked
// dark navy (not CavotecBlueDark, to keep info visually distinct from primary-styled
// elements) verified at ~6.2:1 against the light container; dark reuses CavotecInfoOnDark
// (verified ~5.2:1 against the dark container), matching the success/warning pattern above.
val SoftInfoContainer = Color(0xFFE4EFF9)
val SoftInfoOnContainer = Color(0xFF1E5A8A)
val SoftInfoContainerDark = Color(0xFF283540)
val SoftInfoOnContainerDark = CavotecInfoOnDark
