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
 * Stores raw card/fob UIDs only in Android Keystore AES-GCM encrypted form,
 * keyed by an arbitrary record ID. Callers receive an opaque enrollment
 * reference, never the UID itself.
 *
 * One instance covers exactly one *kind* of record — personnel cards and key
 * cards are enrolled into two separate instances (separate preferences file
 * and separate Keystore alias, via [storeName]), even though both are read
 * at the same physical public reader (protocol doc section 9) and share the
 * same UID space. Keeping them in separate stores means a lookup against one
 * can never accidentally match a record from the other; resolving a scanned
 * UID against both stores is [com.ekms.shared.domain.CardUidResolver]'s job,
 * not this class's — this class only answers "is this UID enrolled here,
 * and to which record?".
 */
class EncryptedUidEnrollmentStore(context: Context, storeName: String) {
    private val preferences = context.getSharedPreferences(
        preferencesName(storeName),
        Context.MODE_PRIVATE,
    )
    private val keyAlias = keystoreAlias(storeName)

    fun enrollmentFor(recordId: String): UidEnrollmentSummary? = readRecord(recordId)?.toSummary()

    fun enroll(recordId: String, rawUid: String, nowEpochMillis: Long): UidEnrollmentResult = try {
        val normalizedUid = normalizeUid(rawUid)
        if (!UID_PATTERN.matches(normalizedUid)) {
            UidEnrollmentResult.InvalidCard
        } else {
            val ownerRecordId = findOwnerOf(normalizedUid)
            when {
                ownerRecordId != null && ownerRecordId != recordId -> UidEnrollmentResult.AlreadyAssigned
                ownerRecordId == recordId -> UidEnrollmentResult.AlreadyEnrolledToSelectedRecord
                else -> {
                    val existing = readRecord(recordId)
                    val record = StoredEnrollment(
                        recordId = recordId,
                        enrollmentReference = "cardref_${UUID.randomUUID()}",
                        encryptedUid = encrypt(normalizedUid),
                        enrolledAtEpochMillis = nowEpochMillis,
                    )
                    writeRecord(record)
                    UidEnrollmentResult.Saved(
                        summary = record.toSummary(),
                        replacedExisting = existing != null,
                    )
                }
            }
        }
    } catch (_: Exception) {
        UidEnrollmentResult.StorageError
    }

    /** Looks up the enrolled record for a scanned UID, without exposing the UID itself. */
    fun recordIdFor(rawUid: String): String? = runCatching {
        val normalizedUid = normalizeUid(rawUid)
        if (!UID_PATTERN.matches(normalizedUid)) return@runCatching null
        findOwnerOf(normalizedUid)
    }.getOrNull()

    /** True if [rawUid] is already enrolled to some record in this store. */
    fun isEnrolled(rawUid: String): Boolean = recordIdFor(rawUid) != null

    fun revoke(recordId: String): UidEnrollmentSummary? {
        val existing = readRecord(recordId) ?: return null
        val knownRecordIds = enrolledRecordIds().toMutableSet().apply { remove(recordId) }
        preferences.edit()
            .remove(recordPreferenceKey(recordId))
            .putStringSet(KEY_ENROLLED_RECORD_IDS, knownRecordIds)
            .apply()
        return existing.toSummary()
    }

    private fun findOwnerOf(normalizedUid: String): String? = enrolledRecordIds().firstOrNull { recordId ->
        val record = readRecord(recordId) ?: return@firstOrNull false
        val decryptedUid = decrypt(record.encryptedUid) ?: return@firstOrNull false
        MessageDigest.isEqual(
            decryptedUid.toByteArray(StandardCharsets.UTF_8),
            normalizedUid.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun writeRecord(record: StoredEnrollment) {
        val knownRecordIds = enrolledRecordIds().toMutableSet().apply { add(record.recordId) }
        preferences.edit()
            .putString(recordPreferenceKey(record.recordId), record.toJson().toString())
            .putStringSet(KEY_ENROLLED_RECORD_IDS, knownRecordIds)
            .apply()
    }

    private fun readRecord(recordId: String): StoredEnrollment? {
        val serialized = preferences.getString(recordPreferenceKey(recordId), null) ?: return null
        return runCatching { StoredEnrollment.fromJson(JSONObject(serialized)) }.getOrNull()
    }

    private fun enrolledRecordIds(): Set<String> =
        preferences.getStringSet(KEY_ENROLLED_RECORD_IDS, emptySet())?.toSet().orEmpty()

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
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
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
        val recordId: String,
        val enrollmentReference: String,
        val encryptedUid: String,
        val enrolledAtEpochMillis: Long,
    ) {
        fun toSummary() = UidEnrollmentSummary(
            recordId = recordId,
            enrollmentReference = enrollmentReference,
            enrolledAtEpochMillis = enrolledAtEpochMillis,
        )

        fun toJson() = JSONObject()
            .put("recordId", recordId)
            .put("enrollmentReference", enrollmentReference)
            .put("encryptedUid", encryptedUid)
            .put("enrolledAtEpochMillis", enrolledAtEpochMillis)

        companion object {
            fun fromJson(json: JSONObject) = StoredEnrollment(
                recordId = json.getString("recordId"),
                enrollmentReference = json.getString("enrollmentReference"),
                encryptedUid = json.getString("encryptedUid"),
                enrolledAtEpochMillis = json.getLong("enrolledAtEpochMillis"),
            )
        }
    }

    private companion object {
        const val KEY_ENROLLED_RECORD_IDS = "enrolled_record_ids"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        val UID_PATTERN = Regex("[0-9A-F]{8,32}")

        fun preferencesName(storeName: String) = "ekms_encrypted_uid_enrollment_$storeName"
        fun keystoreAlias(storeName: String) = "ekms_uid_enrollment_aes_v1_$storeName"

        fun normalizeUid(rawUid: String): String = rawUid
            .filter { character ->
                character in '0'..'9' || character in 'A'..'F' || character in 'a'..'f'
            }
            .uppercase(Locale.US)
            .takeLast(32)

        fun recordPreferenceKey(recordId: String) = "record_$recordId"
    }
}

data class UidEnrollmentSummary(
    val recordId: String,
    val enrollmentReference: String,
    val enrolledAtEpochMillis: Long,
)

sealed interface UidEnrollmentResult {
    data class Saved(
        val summary: UidEnrollmentSummary,
        val replacedExisting: Boolean,
    ) : UidEnrollmentResult

    data object AlreadyAssigned : UidEnrollmentResult
    data object AlreadyEnrolledToSelectedRecord : UidEnrollmentResult
    data object InvalidCard : UidEnrollmentResult
    data object StorageError : UidEnrollmentResult
}
