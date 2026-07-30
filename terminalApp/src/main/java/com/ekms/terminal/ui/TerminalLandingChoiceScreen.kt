package com.ekms.terminal.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * New post-login landing point for Super Admin / Regional Admin only ([TerminalSession.isAdminTier]
 * — see `postLoginRoute()` in [TerminalAdminApp]). Technician/Vendor still route straight to
 * [TerminalKeyMenuScreen] via `postLoginRoute()`'s `else` branch, completely unchanged.
 *
 * Two large, equal-weight options, reusing [SoftHeroAction] — the same component the dashboard's
 * "Take keys" call-out used before this pass demoted it to a normal tile (see
 * `SuperAdminDashboardScreen`'s doc) — since a genuine hero-weight either/or choice belongs here
 * now, not floating alone above an unrelated grid.
 *
 * [onTakeKey]'s actual destination differs by role (Super Admin -> the unfiltered `KEY_RETRIEVAL`
 * grid, Regional Admin -> the same filtered `KEY_MENU` screen Technician/Vendor use) — that
 * branching lives at the call site in [TerminalAdminApp], not here; this screen only reports
 * "which button was tapped," not "which role is signed in."
 *
 * No way back to this screen once past it, deliberately (confirmed assumption, not an oversight):
 * "Admin Page" (-> `DASHBOARD`) keeps its own demoted "Take keys" tile, and `KEY_RETRIEVAL`/
 * `KEY_MENU`'s own back actions go to `DASHBOARD` for admin-tier sessions (mirroring
 * `KEY_RETRIEVAL`'s pre-existing pattern) rather than back to this chooser — there is no
 * round trip through here after the initial choice.
 */
@Composable
fun TerminalLandingChoiceScreen(
    padding: PaddingValues,
    roleLabel: String,
    onTakeKey: () -> Unit,
    onAdminPage: () -> Unit,
    onSignOut: () -> Unit,
) {
    TerminalPage(padding) {
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "What would you like to do?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        SoftHeroAction(
            title = "Take Key",
            subtitle = "Open the cabinet grid",
            onClick = onTakeKey,
        )
        SoftHeroAction(
            title = "Admin Page",
            subtitle = "Manage personnel, keys, and settings",
            onClick = onAdminPage,
        )

        SoftTextButton(text = "Sign out", onClick = onSignOut)
    }
}
