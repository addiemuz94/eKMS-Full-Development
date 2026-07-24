package com.ekms.terminal.hardware.face

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local-only SFace embedding storage, ported from `../eKMSHardwareTester`'s
 * `face/FaceProfileStore.kt` with one addition: an `enrollmentReference` field the tester never
 * needed (it has no backend concept at all). Same AES-256-GCM-via-Keystore pattern as the
 * tester — the embedding never leaves this device even locally-unencrypted, let alone reaches
 * the network — but per boundary #2 ("no raw credential material ever leaves the Terminal"),
 * the raw [FloatArray] embedding must never be sent to the backend as a credential enrollment
 * reference either. [enrollmentReference] (`faceref_<uuid>`) is the opaque value a future
 * `FaceEnrollmentScreen` would report to `completeCredentialEnrollment` instead — mirrors the
 * `fptemplate_<id>` pattern [com.ekms.terminal.hardware.FingerprintTemplateStore] already uses,
 * and the format the backend's `rejectRawMaterialReference` (backend/src/routes/credentials.js)
 * already recognizes for `credentialKind = FACE_RECOGNITION`. Designed now, per the user's
 * explicit request, ahead of the liveness decision — nothing here reports to the backend yet
 * ([save] is local-storage-only; wiring completeCredentialEnrollment stays deferred with the
 * rest of "enrollment completion").
 */
class FaceProfileStore(context: Context) {

    data class FaceProfile(
        val profileId: String,
        val embedding: FloatArray,
        val sampleCount: Int,
        val enrollmentReference: String,
        val createdAtEpochMs: Long,
    )

    companion object {
        private const val PREFERENCES_NAME = "ekms_face_profiles"
        private const val PROFILE_IDS_KEY = "profile_ids"
        private const val KEY_ALIAS = "ekms_face_profile_key_v1"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_SIZE_BYTES = 12
        private const val FLOAT_SIZE_BYTES = 4
        private const val SCHEMA_VERSION = "face-profile-v1"
    }

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun save(
        profileId: String,
        embedding: FloatArray,
        sampleCount: Int,
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): FaceProfile {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }
        require(embedding.isNotEmpty()) { "Face embedding must not be empty." }
        require(sampleCount > 0) { "Sample count must be positive." }

        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)
        val encryptedEmbedding = encrypt(plaintext = floatsToBytes(embedding), iv = iv, profileId = profileId)
        val enrollmentReference = "faceref_${UUID.randomUUID()}"

        val existingIds = profileIds().toMutableSet()
        existingIds.add(profileId)

        val prefix = storagePrefix(profileId)
        preferences.edit()
            .putString("${prefix}_iv", encode(iv))
            .putString("${prefix}_data", encode(encryptedEmbedding))
            .putInt("${prefix}_samples", sampleCount)
            .putString("${prefix}_reference", enrollmentReference)
            .putLong("${prefix}_created", createdAtEpochMs)
            .putStringSet(PROFILE_IDS_KEY, existingIds)
            .apply()

        return FaceProfile(profileId, embedding, sampleCount, enrollmentReference, createdAtEpochMs)
    }

    fun load(profileId: String): FaceProfile? {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }

        val prefix = storagePrefix(profileId)
        val encodedIv = preferences.getString("${prefix}_iv", null) ?: return null
        val encodedData = preferences.getString("${prefix}_data", null) ?: return null
        val enrollmentReference = preferences.getString("${prefix}_reference", null) ?: return null

        val decryptedEmbedding = decrypt(ciphertext = decode(encodedData), iv = decode(encodedIv), profileId = profileId)

        return FaceProfile(
            profileId = profileId,
            embedding = bytesToFloats(decryptedEmbedding),
            sampleCount = preferences.getInt("${prefix}_samples", 1),
            enrollmentReference = enrollmentReference,
            createdAtEpochMs = preferences.getLong("${prefix}_created", 0L),
        )
    }

    fun hasProfile(profileId: String): Boolean = load(profileId) != null

    fun listProfileIds(): List<String> = profileIds().sorted()

    fun delete(profileId: String) {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }

        val prefix = storagePrefix(profileId)
        val existingIds = profileIds().toMutableSet()
        existingIds.remove(profileId)

        preferences.edit()
            .remove("${prefix}_iv")
            .remove("${prefix}_data")
            .remove("${prefix}_samples")
            .remove("${prefix}_reference")
            .remove("${prefix}_created")
            .putStringSet(PROFILE_IDS_KEY, existingIds)
            .apply()
    }

    private fun profileIds(): Set<String> = preferences.getStringSet(PROFILE_IDS_KEY, emptySet())?.toSet().orEmpty()

    private fun encrypt(plaintext: ByteArray, iv: ByteArray, profileId: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(associatedData(profileId))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray, profileId: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(associatedData(profileId))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existingKey = keyStore.getKey(KEY_ALIAS, null)
        if (existingKey is SecretKey) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(parameterSpec)
        return keyGenerator.generateKey()
    }

    private fun associatedData(profileId: String): ByteArray = "$SCHEMA_VERSION:$profileId".toByteArray(Charsets.UTF_8)

    private fun storagePrefix(profileId: String): String {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        return "profile_" + Base64.encodeToString(profileId.toByteArray(Charsets.UTF_8), flags)
    }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * FLOAT_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { value -> buffer.putFloat(value) }
        return buffer.array()
    }

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % FLOAT_SIZE_BYTES == 0) { "Invalid encrypted face-template size." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / FLOAT_SIZE_BYTES) { buffer.float }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
