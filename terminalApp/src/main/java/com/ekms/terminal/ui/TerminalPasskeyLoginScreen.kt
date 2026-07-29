package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Passkey login — wired to the real backend as of migration 009's follow-up phase.
 * `POST /v1/terminal/passkey-login` (unauthenticated, see `TerminalApiClient.passkeyLogin`)
 * validates the submitted 4-digit code against an approved, not-yet-expired
 * `key_access_requests` row and returns a KEY_ACCESS_SESSION-scoped token plus the exact
 * approved key(s)/site/expiry. This screen itself stays presentation-only, same as
 * [TerminalPasswordLoginScreen] — the actual call, session resolution, and routing into the
 * take flow for the approved key(s) all live in `TerminalAdminApp`'s [onSubmit] callback.
 */
@Composable
fun TerminalPasskeyLoginScreen(
    padding: PaddingValues,
    onSubmit: (code: String) -> Unit,
    loginError: String?,
    onBack: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton(onBack)
            SoftCard(contentPadding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Passkey",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Enter the 4-digit code from your approved key access request.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input -> code = input.filter { it.isDigit() }.take(4) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("4-digit code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (loginError != null) {
                        Text(
                            text = loginError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SoftPrimaryButton(
                        text = "Sign in",
                        onClick = { onSubmit(code) },
                        enabled = code.length == 4,
                    )
                }
            }
        }
    }
}
