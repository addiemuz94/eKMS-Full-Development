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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.TerminalAdminStore

/**
 * Smart Key Cabinet User Manual V2.1, Section 1 — Login (soft Material 3).
 *
 * All five entry methods remain visible: personnel card, key card, account/
 * password, Face Recognition, Fingerprint Recognition. Behaviour unchanged —
 * only presentation follows the soft M3 mockup.
 */
@Composable
fun TerminalLoginScreen(
    padding: PaddingValues,
    onAccountLogin: (username: String, password: String) -> Unit,
    loginError: String?,
    onKeyCardSwiped: (() -> Unit)? = null,
) {
    var username by rememberSaveable { mutableStateOf(TerminalAdminStore.SUPER_ADMIN_USERNAME) }
    var password by rememberSaveable { mutableStateOf("") }

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
                text = "Ready when you are — swipe a card or sign in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SoftScanTile(
                    title = "Personnel",
                    description = "Listening…",
                    listening = true,
                    modifier = Modifier.weight(1f),
                )
                SoftScanTile(
                    title = "Key card",
                    description = "Return a key",
                    onClick = onKeyCardSwiped,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "or use account",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            SoftCard(contentPadding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftFilledField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Account",
                    )
                    SoftFilledField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        password = true,
                    )
                    if (loginError != null) {
                        Text(
                            text = loginError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SoftPrimaryButton(
                        text = "Login",
                        onClick = { onAccountLogin(username, password) },
                        enabled = username.isNotBlank() && password.isNotBlank(),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftAssistChip(text = "Face · soon")
                SoftAssistChip(text = "Fingerprint · soon")
            }
        }
    }
}

@Composable
private fun SoftFilledField(
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
