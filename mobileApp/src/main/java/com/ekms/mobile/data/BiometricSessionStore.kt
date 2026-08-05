package com.ekms.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Biometric-login local storage (see CLAUDE_MOBILE.md's audit + Completed entry for the full
 * design rationale). Seals the signed-in account's **refresh token** — not the access token,
 * which is short-lived (1hr) and would usually already be stale by the time a biometric unlock
 * is used — behind an `AndroidKeyStore`-resident AES key gated on `BIOMETRIC_STRONG`. A later
 * cold launch can then decrypt it and call [MobileApiClient.refreshAccessToken] to resume the
 * session, instead of re-entering the manual email/password form.
 *
 * The key material itself never leaves the device's TEE/StrongBox — this class only ever
 * touches the *ciphertext* it produces, which is useless without that key, so plain
 * `SharedPreferences` storage for the ciphertext/IV is fine (same "encrypted payload in a plain
 * container" reasoning behind Android's own Keystore documentation/samples).
 *
 * `setInvalidatedByBiometricEnrollment(true)` is left at its default, deliberately, not
 * disabled: enrolling a *new* fingerprint/face on the phone permanently invalidates the key,
 * forcing a real password re-login rather than silently trusting whatever biometric was just
 * added — the correct behavior, since without this an attacker with brief access to an unlocked
 * phone could add their own fingerprint and use it to resume someone else's session later.
 *
 * Scoped to exactly one bound account per device — a single fixed Keystore alias and a single
 * ciphertext slot, matching [MobileApiClient]'s own single-slot session model (one
 * `accessToken`/`refreshToken`/`profile`, no list, no account switcher anywhere in this app).
 * [enroll] on a second account overwrites the first account's binding rather than adding a
 * second one — by design, not a gap, since there is no multi-account concept elsewhere to
 * support it.
 */
class BiometricSessionStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the post-login "Enable biometric login?" prompt has already been shown and
     * decided once — accept *or* decline both set this, so it is never re-asked every login. */
    var hasPromptedForEnrollment: Boolean
        get() = preferences.getBoolean(KEY_HAS_PROMPTED, false)
        set(value) = preferences.edit().putBoolean(KEY_HAS_PROMPTED, value).apply()

    /** True once an account's refresh token has actually been sealed behind the biometric key.
     * This — not [hasPromptedForEnrollment] — is what gates showing the unlock affordance;
     * declining the prompt leaves this `false` with no key/ciphertext created at all. */
    val isEnrolled: Boolean
        get() = preferences.contains(KEY_CIPHERTEXT) && preferences.contains(KEY_IV)

    /** The bound account's identifier (email) — purely informational (e.g. "Unlock as
     * jane@..."), never used for any access decision; the actual authorization is the decrypted
     * refresh token exchanged via [MobileApiClient.refreshAccessToken]. */
    val boundIdentifier: String?
        get() = preferences.getString(KEY_IDENTIFIER, null)

    /**
     * Step 1 of enrollment: (re)generates the Keystore key and returns a fresh ENCRYPT_MODE
     * [Cipher] for it, wrapped by the caller in a `BiometricPrompt.CryptoObject` and passed to
     * `BiometricPrompt.authenticate(...)`. A biometric-gated key cannot encrypt anything until a
     * live biometric authentication actually unlocks it — this is true even for a
     * *brand-new* key, so enrollment itself needs a real prompt cycle, not just a background
     * `KeyGenerator` call.
     */
    fun prepareEnrollCipher(): Cipher {
        val key = createKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** Step 2 of enrollment: called from `BiometricPrompt`'s success callback with the now
     * biometric-authenticated [Cipher] `prepareEnrollCipher` returned (via
     * `AuthenticationResult.cryptoObject.cipher`, not a fresh instance — a `Cipher` only stays
     * "unlocked" for its own object identity). */
    fun completeEnroll(identifier: String, refreshToken: String, cipher: Cipher) {
        val ciphertext = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_IDENTIFIER, identifier)
            .apply()
    }

    /**
     * Step 1 of unlock: builds the DECRYPT_MODE [Cipher] to wrap in a `CryptoObject` for
     * `BiometricPrompt.authenticate(...)`. Throws [KeyPermanentlyInvalidatedException]
     * **synchronously here**, before any prompt is ever shown, if a new biometric was enrolled
     * on the device since [enroll]/[completeEnroll] — callers must catch this specifically (not
     * just a generic exception) and respond by calling [clear] + falling back to the manual
     * login form, per this feature's own invalidation handling. Throws [IllegalStateException]
     * if nothing is enrolled at all — callers should have already checked [isEnrolled] before
     * ever reaching this point.
     */
    @Throws(KeyPermanentlyInvalidatedException::class)
    fun prepareDecryptCipher(): Cipher {
        val key = existingKey() ?: error("No biometric key enrolled")
        val iv = preferences.getString(KEY_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("No biometric IV stored")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher
    }

    /** Step 2 of unlock: called from `BiometricPrompt`'s success callback with the now
     * biometric-authenticated [Cipher] `prepareDecryptCipher` returned. Returns the plaintext
     * refresh token — caller is expected to immediately exchange it via
     * [MobileApiClient.refreshAccessToken], not just reuse it as-is. */
    fun completeDecrypt(cipher: Cipher): String {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("No biometric ciphertext stored")
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** Clears all bound biometric material — called on [KeyPermanentlyInvalidatedException], an
     * explicit user "disable biometric login" action (no such UI exists yet — a documented gap,
     * not implemented this pass), or account sign-out. Deliberately does **not** clear
     * [hasPromptedForEnrollment] — a user whose enrollment was invalidated or who signed out
     * shouldn't be re-asked the enable-prompt on their very next login as if this were their
     * first time; they already made that choice once. */
    fun clear() {
        preferences.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .remove(KEY_IDENTIFIER)
            .apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            // No setUserAuthenticationValidityDurationSeconds(...) call, deliberately — leaving
            // it unset means the key requires a fresh BiometricPrompt-mediated authentication
            // for every single use (encrypt or decrypt), not just once within some grace window.
            // BIOMETRIC_STRONG-only is enforced at the BiometricPrompt.PromptInfo layer (see
            // LoginScreen.kt) rather than also restated here via the API-30-only
            // setUserAuthenticationParameters overload — this Keystore spec stays identical
            // across the whole minSdk 26-36 range this way.
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "ekms_mobile_biometric"
        const val KEY_HAS_PROMPTED = "has_prompted"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_IDENTIFIER = "identifier"
        const val KEY_ALIAS = "ekms_mobile_biometric_refresh_token_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
    }
}
