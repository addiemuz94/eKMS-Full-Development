package com.ekms.mobile.ui.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.composables.icons.lucide.R as LucideR
import com.ekms.mobile.data.BiometricSessionStore
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.data.MobileApiException
import com.ekms.mobile.data.MobileThemeMode
import com.ekms.mobile.push.PushTokenSync
import com.ekms.mobile.ui.common.ThemeModeSegmentedControl
import com.ekms.shared.api.AuthUserProfile
import javax.crypto.Cipher
import kotlinx.coroutines.launch

/**
 * mobileApp's first real auth screen — previously there was none at all (zero network code
 * before this pass). Plain email + password, same as every other client's login form; a fresh
 * [MobileApiClient.login] call works unchanged for all four roles (Super Admin / Regional Admin
 * / Technician / Vendor) since the backend resolves role from the account itself, not from a
 * client-side selector — so there is deliberately no role picker here.
 *
 * [themeMode]/[onThemeModeChange] thread the same [com.ekms.mobile.data.MobileThemePreferences]
 * state `SuperAdminCompanionApp.kt` already owns — this screen never creates its own preferences
 * instance, so there's one source of truth whether the toggle is touched pre- or post-login.
 * The control renders top-end, deliberately unobtrusive (no "Theme" label header, unlike the
 * authenticated shell's overflow-menu copy) and clear of the card below it, so it doesn't
 * compete with the boot-splash-to-login handoff or the sign-in form itself.
 *
 * [sessionExpiredMessage], when non-null, is shown as an inline banner above the form — set by
 * `SuperAdminCompanionApp.kt` when [MobileApiClient.onSessionExpired] fires (a 401 that survived
 * one refresh attempt; see `MobileApiClient.send`'s doc), so a forced sign-out always reads as
 * an explained event, not a mysterious return to this screen.
 *
 * Biometric login (see CLAUDE_MOBILE.md's audit + Completed entry for the full design):
 * - After a successful **manual** login, if the device can plausibly do `BIOMETRIC_STRONG` at
 *   all and this is the first time ever (per [BiometricSessionStore.hasPromptedForEnrollment]),
 *   an "Enable biometric login?" dialog offers to bind this account — accepting immediately
 *   triggers a real [BiometricPrompt] cycle (a freshly-created biometric-gated key cannot
 *   encrypt anything without one, even on its very first use).
 * - The unlock affordance below the form is gated on [BiometricSessionStore.isEnrolled] **and**
 *   `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` actually returning `BIOMETRIC_SUCCESS`
 *   right now — not shown at all otherwise, rather than shown and left to fail on tap (covers a
 *   bound-but-now-Class-2-face-only-available device, biometrics disabled in Settings since
 *   enrollment, etc.).
 */
@Composable
fun LoginScreen(
    apiClient: MobileApiClient,
    onLoginSuccess: (AuthUserProfile) -> Unit,
    themeMode: MobileThemeMode,
    onThemeModeChange: (MobileThemeMode) -> Unit,
    sessionExpiredMessage: String? = null,
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // BiometricPrompt's real constructor only accepts a FragmentActivity (see MainActivity.kt's
    // own doc) — MainActivity always is one, but the cast stays defensive (e.g. a future Compose
    // Preview host) rather than an unchecked assumption: null just means no biometric affordance
    // is ever offered, a graceful, silent degradation, not a crash.
    val activity = context as? FragmentActivity

    val biometricStore = remember(context) { BiometricSessionStore(context) }
    var showEnableBiometricDialog by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<AuthUserProfile?>(null) }
    // Recomputed fresh each time this screen composes (e.g. after a sign-out lands back here) —
    // both the device's biometric enrollment and this app's own bound state can change while
    // this screen isn't on screen, so a one-time check at first composition would go stale.
    val canOfferBiometricUnlock = activity != null && biometricStore.isEnrolled &&
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

    fun finishLogin(profile: AuthUserProfile) {
        pendingProfile = null
        onLoginSuccess(profile)
    }

    fun afterManualLoginSuccess(profile: AuthUserProfile) {
        val canOfferEnroll = activity != null &&
            !biometricStore.hasPromptedForEnrollment &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (canOfferEnroll) {
            pendingProfile = profile
            showEnableBiometricDialog = true
        } else {
            finishLogin(profile)
        }
    }

    fun submit() {
        if (identifier.isBlank() || password.isBlank()) {
            error = "Enter your email and password."
            return
        }
        error = null
        loading = true
        scope.launch {
            try {
                val response = apiClient.login(identifier, password)
                PushTokenSync.syncAfterLogin(context.applicationContext, apiClient)
                afterManualLoginSuccess(response.profile)
            } catch (e: MobileApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Failed to reach the server. Check your connection and try again."
            } finally {
                loading = false
            }
        }
    }

    fun declineBiometricEnrollment() {
        biometricStore.hasPromptedForEnrollment = true
        showEnableBiometricDialog = false
        pendingProfile?.let { finishLogin(it) }
    }

    fun acceptBiometricEnrollment() {
        val hostActivity = activity ?: return declineBiometricEnrollment()
        val profile = pendingProfile ?: return
        val cipher = try {
            biometricStore.prepareEnrollCipher()
        } catch (_: Exception) {
            // No usable Keystore/biometric hardware after all despite the earlier check — fail
            // quietly into "not enabled", same as a decline. Manual login already succeeded.
            declineBiometricEnrollment()
            return
        }
        runBiometricPrompt(
            activity = hostActivity,
            cipher = cipher,
            title = "Enable biometric login",
            subtitle = "Confirm to protect your eKMS session with this device's biometrics.",
            onSuccess = { authenticatedCipher ->
                biometricStore.completeEnroll(profile.email, apiClient.refreshToken.orEmpty(), authenticatedCipher)
                biometricStore.hasPromptedForEnrollment = true
                showEnableBiometricDialog = false
                finishLogin(profile)
            },
            onError = { _, _ ->
                // Cancelled or failed enrollment prompt — proceed signed in regardless; manual
                // login already succeeded, this was purely an opt-in extra.
                declineBiometricEnrollment()
            },
        )
    }

    fun unlockWithBiometrics() {
        val hostActivity = activity ?: return
        val cipher = try {
            biometricStore.prepareDecryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A new fingerprint/face was enrolled on the device since binding — the Keystore key
            // is permanently unusable now, by design (see BiometricSessionStore's own doc on why
            // this is the correct, secure behavior, not a bug to route around).
            biometricStore.clear()
            error = "Please sign in again."
            return
        } catch (_: Exception) {
            error = "Biometric login is unavailable right now. Please sign in with your password."
            return
        }
        runBiometricPrompt(
            activity = hostActivity,
            cipher = cipher,
            title = "Unlock eKMS Digital Key",
            subtitle = biometricStore.boundIdentifier?.let { "Signed in as $it" } ?: "",
            onSuccess = { authenticatedCipher ->
                val refreshToken = biometricStore.completeDecrypt(authenticatedCipher)
                error = null
                loading = true
                scope.launch {
                    try {
                        apiClient.refreshToken = refreshToken
                        val response = apiClient.refreshAccessToken()
                        finishLogin(response.profile)
                    } catch (e: MobileApiException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "Failed to reach the server. Check your connection and try again."
                    } finally {
                        loading = false
                    }
                }
            },
            onError = { errorCode, message ->
                // ERROR_NEGATIVE_BUTTON / ERROR_USER_CANCELED / ERROR_CANCELED are the user
                // dismissing the sheet themselves — silently return to the form, no error text,
                // same as tapping outside any other cancellable dialog.
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    error = message.toString()
                }
            },
        )
    }

    // Root cause of the theme bug this fixes: this Box previously had no background at all, so
    // it fell through to the Activity window's own background — mobileApp/res/values/themes.xml's
    // `Theme.Ekms` parents `android:style/Theme.Material.Light.NoActionBar`, a light-only
    // platform theme whose windowBackground is hardcoded white, independent of MobileThemeMode.
    // Every other screen gets its background from Scaffold's containerColor (which defaults to
    // MaterialTheme.colorScheme.background) in SuperAdminCompanionApp.kt; LoginScreen is
    // composed directly inside EkmsMobileTheme with no Scaffold, so it needs its own.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        // Pre-login theme toggle — same 3-way control the authenticated shell's overflow menu
        // uses (see ThemeModeSegmentedControl's own doc), placed here so it doesn't require
        // signing in first. Corner placement + no header text keeps it unobtrusive relative to
        // the sign-in card, which stays the visually dominant element on this screen.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
        ) {
            ThemeModeSegmentedControl(mode = themeMode, onModeChange = onThemeModeChange)
        }

        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "eKMS Digital Key",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Sign in with your eKMS account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sessionExpiredMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = identifier,
                        onValueChange = { identifier = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = ::submit,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                        } else {
                            Text("Sign in")
                        }
                    }
                    if (canOfferBiometricUnlock) {
                        OutlinedButton(
                            onClick = ::unlockWithBiometrics,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_fingerprint_pattern),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("Unlock with biometrics")
                        }
                    }
                }
            }
        }
    }

    if (showEnableBiometricDialog) {
        AlertDialog(
            onDismissRequest = ::declineBiometricEnrollment,
            title = { Text("Enable biometric login?") },
            text = {
                Text(
                    "Use your fingerprint or face to sign in next time instead of your password. " +
                        "You can still sign in with your password any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = ::acceptBiometricEnrollment) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = ::declineBiometricEnrollment) { Text("Not now") }
            },
        )
    }
}

/**
 * Thin wrapper around `BiometricPrompt.authenticate(PromptInfo, CryptoObject)` shared by both
 * the enable-enrollment and unlock flows above — both need the same shape (title/subtitle in,
 * a cipher-carrying success callback and an error callback out), just different ciphers/copy.
 * `BIOMETRIC_STRONG`-only, no device-credential fallback: a `CryptoObject`-bound key can only
 * ever be unlocked by the biometric class it was created under, so offering a PIN/pattern
 * fallback here would just fail at the Keystore layer anyway.
 */
private fun runBiometricPrompt(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    subtitle: String,
    onSuccess: (Cipher) -> Unit,
    onError: (errorCode: Int, message: CharSequence) -> Unit,
) {
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText("Cancel")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null) {
                    onSuccess(authenticatedCipher)
                } else {
                    onError(-1, "Biometric authentication did not return the expected result.")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString)
            }

            // onAuthenticationFailed (a single rejected attempt, e.g. wrong finger) is
            // deliberately not overridden — BiometricPrompt's own sheet already shows a retry
            // hint and stays open; there's nothing extra for this screen to do until either
            // onAuthenticationSucceeded or onAuthenticationError (e.g. ERROR_LOCKOUT after too
            // many failed attempts) fires.
        },
    )
    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
}
