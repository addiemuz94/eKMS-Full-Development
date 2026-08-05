package com.ekms.mobile.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ekms.mobile.data.MobileThemeMode

/**
 * Phase M-B theme toggle. Genuine 3-way System/Light/Dark control (unlike terminalApp's
 * login-screen binary Switch, which can't express "follow system" once touched — flagged as a
 * revisit candidate in terminalApp's own code), matching web's `SegmentedControl` (System/Light/
 * Dark) affordance instead, since that's the richer, already-established precedent for this
 * exact 3-state model in this codebase.
 *
 * Extracted out of `SuperAdminCompanionApp.kt` (its original, still-only-until-now home, the
 * authenticated shell's overflow menu) so [com.ekms.mobile.ui.auth.LoginScreen] can host the
 * same control pre-login — a single source of truth for the segmented row itself, both call
 * sites still each own their surrounding chrome (the overflow menu's "Theme" label header stays
 * in `SuperAdminCompanionApp.kt`; LoginScreen's corner placement has none, deliberately more
 * unobtrusive there).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeModeSegmentedControl(
    mode: MobileThemeMode,
    onModeChange: (MobileThemeMode) -> Unit,
) {
    val options = listOf(
        MobileThemeMode.SYSTEM to "System",
        MobileThemeMode.LIGHT to "Light",
        MobileThemeMode.DARK to "Dark",
    )
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, (optionMode, label) ->
            SegmentedButton(
                selected = mode == optionMode,
                onClick = { onModeChange(optionMode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}
