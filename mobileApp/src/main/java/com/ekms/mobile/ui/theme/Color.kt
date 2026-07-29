package com.ekms.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Cavotec brand tokens — duplicated from `terminalApp/.../ui/theme/Color.kt`, not shared via a
 * common module. terminalApp is revert-only for this pass (Digital Key hardware-feasibility
 * investigation), so extracting a genuinely shared Compose theme module would mean moving and
 * re-importing terminalApp's existing theme files, which is out of scope here. See Theme.kt's
 * doc comment for the full reasoning. Keep these values in sync with terminalApp's Color.kt by
 * hand if either changes — there is currently no automated guard against drift.
 */
val CavotecBlue = Color(0xFF0055A5)
val CavotecBlueDark = Color(0xFF003C78)
val CavotecBlueLight = Color(0xFF5B7FC4)

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
val CavotecSuccess = Color(0xFF2E9E5B)
val CavotecWarning = Color(0xFFE0A430)
val CavotecDanger = Color(0xFFD64545)
val CavotecInfo = Color(0xFF3B8FD1)

val CavotecSuccessOnDark = Color(0xFF4CBB7B)
val CavotecWarningOnDark = Color(0xFFE8B65A)
val CavotecDangerOnDark = Color(0xFFE2685F)
val CavotecInfoOnDark = Color(0xFF64AEE0)
