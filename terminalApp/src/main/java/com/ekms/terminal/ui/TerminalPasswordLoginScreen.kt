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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.TerminalAdminStore

/**
 * Password login — Phase 3 rework's job for this method is presentation only. The account/
 * password fields, [onAccountLogin] callback, and validation (non-blank username+password before
 * enabling the button) are moved here unchanged from the old single-screen `TerminalLoginScreen`;
 * nothing about how a password login is processed was touched.
 */
@Composable
fun TerminalPasswordLoginScreen(
    padding: PaddingValues,
    onAccountLogin: (username: String, password: String) -> Unit,
    loginError: String?,
    /** Null when there's no method chooser to return to — e.g. the Key Return Certification
     * gate, which has its own abandonment timeout instead of a back affordance. */
    onBack: (() -> Unit)? = null,
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
            onBack?.let { BackButton(it) }
            SoftBrandHeader(subtitle = "CAB · Terminal")
            Text(
                text = "Sign in with your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
    }
}
