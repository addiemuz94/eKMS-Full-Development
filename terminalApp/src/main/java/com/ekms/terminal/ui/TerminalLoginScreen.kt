package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SoftBrandHeader(subtitle = "CAB · Terminal")
            Text(
                text = "Choose how you'd like to sign in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SoftScanTile(
                    title = "Password",
                    description = "Account & password",
                    onClick = { onSelectMethod(LoginMethod.PASSWORD) },
                    modifier = Modifier.weight(1f),
                )
                SoftScanTile(
                    title = "Fingerprint",
                    description = "Scan a finger",
                    onClick = { onSelectMethod(LoginMethod.FINGERPRINT) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SoftScanTile(
                    title = "Facial Recognition",
                    description = "Look at the camera",
                    onClick = { onSelectMethod(LoginMethod.FACE) },
                    modifier = Modifier.weight(1f),
                )
                SoftScanTile(
                    title = "NFC Card",
                    description = "Tap your card",
                    onClick = { onSelectMethod(LoginMethod.NFC_CARD) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SoftScanTile(
                    title = "Passkey",
                    description = "4-digit code",
                    onClick = { onSelectMethod(LoginMethod.PASSKEY) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
