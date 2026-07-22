package com.ekms.terminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.TerminalAdminStore

/**
 * Smart Key Cabinet User Manual V2.1, Section 1 — Login.
 *
 * All five entry methods appear together on this one screen, exactly as the
 * manual specifies: personnel card swipe, key card swipe (which returns a
 * key directly instead of logging in), account/password, a Face Recognition
 * button, and a Fingerprint Recognition button.
 *
 * Personnel card swipe is still a passive, inert reader zone: matching a
 * swiped personnel card to a signed-in user has no data model yet. Key card
 * swipe now calls [onKeyCardSwiped] directly into Section 3's return flow —
 * see [TerminalKeyReturnScreen] — when the caller is the idle/login screen;
 * pass null to keep it inert (used when this screen is reused as the
 * return flow's own certification-login step, where a nested swipe would
 * be meaningless). Real card-reader hardware is still not wired to either
 * panel; see PublicCardReaderController for that future integration.
 * Face Recognition and Fingerprint Recognition are disabled buttons until
 * their hardware bridge lands. This screen adds no confirmation step,
 * dialog, or notice beyond a real account/password login error.
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "eKMS Terminal",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            SwipeInputPanel(title = "Personnel card", description = "Swipe your personnel card")
            SwipeInputPanel(
                title = "Key card",
                description = "Swipe a key card to return it directly",
                onClick = onKeyCardSwiped,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Account / password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Account") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (loginError != null) {
                        Text(
                            text = loginError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { onAccountLogin(username, password) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = username.isNotBlank() && password.isNotBlank(),
                    ) {
                        Text("Login")
                    }
                }
            }

            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            ) {
                Text("Face Recognition")
            }
            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            ) {
                Text("Fingerprint Recognition")
            }
        }
    }
}

@Composable
private fun SwipeInputPanel(title: String, description: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
