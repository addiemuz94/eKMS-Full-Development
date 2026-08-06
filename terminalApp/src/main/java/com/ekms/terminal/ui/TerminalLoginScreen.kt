package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.LocalAudioClick
import com.ekms.terminal.ui.theme.LocalEkmsColors

/** Which method screen to show under [SuperAdminRoute.LOGIN] once a logo is tapped. `null`
 * (handled by the caller, not a case here) means "show [TerminalLoginScreen] itself." */
enum class LoginMethod {
    PASSWORD,
    FINGERPRINT,
    FACE,
    NFC_CARD,
    PASSKEY,
}

/**
 * Smart Key Cabinet User Manual V2.1, Section 1 — Login method selection (Phase 3 rework).
 *
 * All five entry methods from the manual are represented as selectable logos — Password,
 * Fingerprint, Facial Recognition, NFC Card, Passkey — tapping one navigates to that method's
 * own dedicated screen ([TerminalPasswordLoginScreen], [TerminalFingerprintLoginScreen],
 * [TerminalFaceLoginScreen], [TerminalNfcCardLoginScreen], [TerminalPasskeyLoginScreen]).
 *
 * The old single-screen layout's ambient "Personnel"/"Key card" tiles are gone from here, but
 * the underlying ambient NFC detection they represented is completely unchanged: the public
 * card reader listens for both personnel-card and key-card swipes for as long as
 * `route == SuperAdminRoute.LOGIN`, regardless of which of these five method screens (or this
 * chooser) happens to be on screen — see `TerminalAdminApp.kt`'s `cardReaderShouldBeActive`,
 * untouched by this rework. The old screen's manual "Key card" tap tile (a hardware-free
 * testing convenience for simulating a key-card return with no reader attached, see
 * `resolveReturningKey`'s doc) has no home among these five method logos and was dropped —
 * real key-card returns are unaffected since they never depended on that tile.
 */
@Composable
fun TerminalLoginScreen(
    padding: PaddingValues,
    onSelectMethod: (LoginMethod) -> Unit,
    isDarkTheme: Boolean = false,
    onToggleTheme: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Brand block (eK logo + "eKMS" wordmark + "CAB · Terminal" subtitle) removed —
            // the cabinet/location identity now lives in the global TopAppBar title
            // ("eKMS Cabinet · {location}") instead of being repeated here. With it gone,
            // this row is just the sign-in instruction (left) and the theme toggle
            // (right) — no third weighted section needed to fill a middle gap, since
            // there's no longer a brand block on the opposite end to balance against.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Choose how you'd like to sign in.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onToggleTheme != null) {
                    ThemeModeToggle(isDarkTheme = isDarkTheme, onToggle = onToggleTheme)
                }
            }

            // Single-column full-width vertical list (reference-device UI, replaces the
            // rejected 3+2 grid): one SoftScanTile per row, stacked top to bottom, each
            // `fillMaxWidth()` + `weight(1f)` so all 5 rows divide the available height
            // evenly and fill it completely — no leftover empty space top or bottom, and
            // no side-by-side tiles. Tile internals (icon centered above title+subtitle)
            // are SoftScanTile's own unchanged content; only this container arrangement
            // changed.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SoftScanTile(
                    title = "Password",
                    description = "Account & password",
                    icon = Icons.Filled.Password,
                    onClick = { onSelectMethod(LoginMethod.PASSWORD) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    expanded = true,
                )
                SoftScanTile(
                    title = "Fingerprint",
                    description = "Scan a finger",
                    icon = Icons.Filled.Fingerprint,
                    onClick = { onSelectMethod(LoginMethod.FINGERPRINT) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    expanded = true,
                )
                SoftScanTile(
                    title = "Facial Recognition",
                    description = "Look at the camera",
                    icon = Icons.Filled.Face,
                    onClick = { onSelectMethod(LoginMethod.FACE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    expanded = true,
                )
                SoftScanTile(
                    title = "NFC Card",
                    description = "Tap your card",
                    icon = Icons.Filled.Nfc,
                    onClick = { onSelectMethod(LoginMethod.NFC_CARD) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    expanded = true,
                )
                SoftScanTile(
                    title = "Passkey",
                    description = "4-digit code",
                    icon = Icons.Filled.VpnKey,
                    onClick = { onSelectMethod(LoginMethod.PASSKEY) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    expanded = true,
                )
            }
        }
    }
}

/**
 * Corner dark/light control (Phase 9 design-system rework) — a plain [Switch] rather than a
 * new icon, since sun/moon glyphs live only in the much larger material-icons-extended
 * artifact and this pass deliberately limits itself to material-icons-core (see
 * IconActionButton.kt's doc). Placement choice: the login/method-chooser screen's top-right
 * corner, reachable pre-authentication since this is a cosmetic device preference, not an
 * admin-gated setting — flagged here so it can be revisited (e.g. moved into Admin Menu)
 * if that's not the right call.
 */
@Composable
internal fun ThemeModeToggle(
    isDarkTheme: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalEkmsColors.current
    val playClick = LocalAudioClick.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (isDarkTheme) "Dark" else "Light",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = isDarkTheme,
            onCheckedChange = { playClick(); onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
internal fun SoftFilledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    )
}
