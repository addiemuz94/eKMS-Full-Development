package com.ekms.terminal.hardware

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores raw fob identifiers only in Android Keystore AES-GCM encrypted form.
 * UI and audit code receive an opaque enrollment reference, never the UID.
 */
class EncryptedFobEnrollmentStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun enrollmentFor(keyId: String): FobEnrollmentSummary? = readRecord(keyId)?.toSummary()

    fun enroll(keyId: String, rawUid: String, nowEpochMillis: Long): FobEnrollmentResult = try {
        val normalizedUid = normalizeUid(rawUid)
        if (!UID_PATTERN.matches(normalizedUid)) {
            FobEnrollmentResult.InvalidCard
        } else {
            val ownerKeyId = findOwnerOf(normalizedUid)
            when {
                ownerKeyId != null && ownerKeyId != keyId -> FobEnrollmentResult.AlreadyAssigned
                ownerKeyId == keyId -> FobEnrollmentResult.AlreadyEnrolledToSelectedKey
                else -> {
                    val existing = readRecord(keyId)
                    val record = StoredEnrollment(
                        keyId = keyId,
                        enrollmentReference = "fobref_${UUID.randomUUID()}",
                        encryptedUid = encrypt(normalizedUid),
                        enrolledAtEpochMillis = nowEpochMillis,
                    )
                    writeRecord(record)
                    FobEnrollmentResult.Saved(
                        summary = record.toSummary(),
                        replacedExisting = existing != null,
                    )
                }
            }
        }
    } catch (_: Exception) {
        FobEnrollmentResult.StorageError
    }

    /** Finds a registered key for a returned physical fob without exposing its UID. */
    fun keyIdFor(rawUid: String): String? = runCatching {
        val normalizedUid = normalizeUid(rawUid)
        if (!UID_PATTERN.matches(normalizedUid)) return@runCatching null
        findOwnerOf(normalizedUid)
    }.getOrNull()

    fun revoke(keyId: String): FobEnrollmentSummary? {
        val existing = readRecord(keyId) ?: return null
        val knownKeyIds = enrolledKeyIds().toMutableSet().apply { remove(keyId) }
        preferences.edit()
            .remove(recordPreferenceKey(keyId))
            .putStringSet(KEY_ENROLLED_KEY_IDS_V2, knownKeyIds)
            .putStringSet(KEY_ENROLLED_KEY_IDS_V1, knownKeyIds)
            .apply()
        return existing.toSummary()
    }

    private fun findOwnerOf(normalizedUid: String): String? = enrolledKeyIds().firstOrNull { keyId ->
        val record = readRecord(keyId) ?: return@firstOrNull false
        val decryptedUid = decrypt(record.encryptedUid) ?: return@firstOrNull false
        MessageDigest.isEqual(
            decryptedUid.toByteArray(StandardCharsets.UTF_8),
            normalizedUid.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun writeRecord(record: StoredEnrollment) {
        val knownKeyIds = enrolledKeyIds().toMutableSet().apply { add(record.keyId) }
        preferences.edit()
            .putString(recordPreferenceKey(record.keyId), record.toJson().toString())
            .putStringSet(KEY_ENROLLED_KEY_IDS_V2, knownKeyIds)
            .apply()
    }

    private fun readRecord(keyId: String): StoredEnrollment? {
        val serialized = preferences.getString(recordPreferenceKey(keyId), null) ?: return null
        return runCatching { StoredEnrollment.fromJson(JSONObject(serialized)) }.getOrNull()
    }

    /** Reads both the old Step 5 set and this workflow rework's set. */
    private fun enrolledKeyIds(): Set<String> = buildSet {
        addAll(preferences.getStringSet(KEY_ENROLLED_KEY_IDS_V1, emptySet())?.toSet().orEmpty())
        addAll(preferences.getStringSet(KEY_ENROLLED_KEY_IDS_V2, emptySet())?.toSet().orEmpty())
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$iv.$payload"
    }

    private fun decrypt(payload: String): String? = runCatching {
        val parts = payload.split('.', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null, null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private data class StoredEnrollment(
        val keyId: String,
        val enrollmentReference: String,
        val encryptedUid: String,
        val enrolledAtEpochMillis: Long,
    ) {
        fun toSummary() = FobEnrollmentSummary(
            keyId = keyId,
            enrollmentReference = enrollmentReference,
            enrolledAtEpochMillis = enrolledAtEpochMillis,
        )

        fun toJson() = JSONObject()
            .put("keyId", keyId)
            .put("enrollmentReference", enrollmentReference)
            // Retain the former field name so a prior Step 5 installation can
            // continue reading the local protected reference during migration.
            .put("reference", enrollmentReference)
            .put("encryptedUid", encryptedUid)
            .put("enrolledAtEpochMillis", enrolledAtEpochMillis)

        companion object {
            fun fromJson(json: JSONObject) = StoredEnrollment(
                keyId = json.getString("keyId"),
                enrollmentReference = json.optString("enrollmentReference")
                    .ifBlank { json.getString("reference") },
                encryptedUid = json.getString("encryptedUid"),
                enrolledAtEpochMillis = json.getLong("enrolledAtEpochMillis"),
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "ekms_protected_fob_enrollment"
        const val KEY_ENROLLED_KEY_IDS_V1 = "enrolled_key_ids_v1"
        const val KEY_ENROLLED_KEY_IDS_V2 = "enrolled_key_ids_v2"
        // Keep the original alias so existing protected Step 5 UID records can
        // still be decrypted and used for return-key matching.
        const val KEY_ALIAS = "ekms_fob_enrollment_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        val UID_PATTERN = Regex("[0-9A-F]{8,32}")

        fun normalizeUid(rawUid: String): String = rawUid
            .filter { character ->
                character in '0'..'9' || character in 'A'..'F' || character in 'a'..'f'
            }
            .uppercase(Locale.US)
            .takeLast(32)

        fun recordPreferenceKey(keyId: String) = "record_$keyId"
    }
}

data class FobEnrollmentSummary(
    val keyId: String,
    val enrollmentReference: String,
    val enrolledAtEpochMillis: Long,
)

sealed interface FobEnrollmentResult {
    data class Saved(
        val summary: FobEnrollmentSummary,
        val replacedExisting: Boolean,
    ) : FobEnrollmentResult

    data object AlreadyAssigned : FobEnrollmentResult
    data object AlreadyEnrolledToSelectedKey : FobEnrollmentResult
    data object InvalidCard : FobEnrollmentResult
    data object StorageError : FobEnrollmentResult
}