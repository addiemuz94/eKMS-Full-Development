package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * First screen a fresh/unpaired terminal shows — replaces standby/login/Admin Menu entirely
 * (CLAUDE.md "Terminal → live API" superseded by this once deployed; see Project Status). A
 * Super Admin registers this Key Cabinet on the portal, which generates a 6-digit one-time
 * pairing code (30-minute expiry, single-use); entering it here calls
 * `POST /v1/terminal/pair-with-code` and, once redeemed, this screen never reappears unless
 * the terminal is explicitly unpaired/reset (see [TerminalAdminApp]'s `isPaired` gate).
 */
@Composable
fun TerminalPairingScreen(
    padding: PaddingValues,
    serverAddress: String,
    onServerAddressChange: (String) -> Unit,
    isSubmitting: Boolean,
    errorMessage: String?,
    onSubmit: (code: String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SoftBrandHeader(subtitle = "Pair this terminal")
            Text(
                text = "Enter the 6-digit pairing code shown on the Super Admin portal after " +
                    "registering this Key Cabinet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SoftCard(contentPadding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input -> code = input.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        ),
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SoftPrimaryButton(
                        text = "Pair terminal",
                        onClick = { onSubmit(code) },
                        enabled = code.length == 6 && !isSubmitting,
                        loading = isSubmitting,
                    )
                }
            }

            SoftTextButton(
                text = if (showAdvanced) "Hide advanced options" else "Advanced: server address",
                onClick = { showAdvanced = !showAdvanced },
            )

            if (showAdvanced) {
                SoftCard(contentPadding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = serverAddress,
                            onValueChange = onServerAddressChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Server address") },
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            ),
                        )
                        Text(
                            text = "Only change this for on-prem or non-default deployments. " +
                                "Defaults to the production server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
