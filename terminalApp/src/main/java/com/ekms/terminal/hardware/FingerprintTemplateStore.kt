package com.ekms.terminal.hardware

import android.content.Context
import org.json.JSONObject

/**
 * Local `(userId -> R503 template slot)` mapping. Unlike [EncryptedUidEnrollmentStore], this
 * holds no raw credential material at all — only an integer template ID (0-199); the actual
 * fingerprint template lives exclusively inside the R503 module's own on-device library and
 * never leaves it (see [FingerprintHardwareController]), so Keystore-level encryption of this
 * mapping isn't warranted the way it is for a raw NFC UID.
 *
 * [enrollmentReference] follows the `fptemplate_<id>` format the backend's
 * `rejectRawMaterialReference` (backend/src/routes/credentials.js) recognizes as an opaque
 * reference for `credentialKind = FINGERPRINT` — the template ID is exactly what's reported to
 * `POST /v1/admin/users/{userId}/credentials/complete`, since the biometric data itself never
 * leaves the sensor module to report anything else.
 */
class FingerprintTemplateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun enrollmentFor(userId: String): FingerprintEnrollmentSummary? = readRecord(userId)

    fun save(userId: String, templateId: Int, nowEpochMillis: Long): FingerprintEnrollmentSummary {
        val summary = FingerprintEnrollmentSummary(
            userId = userId,
            templateId = templateId,
            enrollmentReference = "fptemplate_$templateId",
            enrolledAtEpochMillis = nowEpochMillis,
        )
        preferences.edit()
            .putString(recordKey(userId), summary.toJson().toString())
            .putStringSet(KEY_ENROLLED_USER_IDS, enrolledUserIds().toMutableSet().apply { add(userId) })
            .apply()
        return summary
    }

    fun revoke(userId: String): FingerprintEnrollmentSummary? {
        val existing = readRecord(userId) ?: return null
        preferences.edit()
            .remove(recordKey(userId))
            .putStringSet(KEY_ENROLLED_USER_IDS, enrolledUserIds().toMutableSet().apply { remove(userId) })
            .apply()
        return existing
    }

    private fun readRecord(userId: String): FingerprintEnrollmentSummary? {
        val serialized = preferences.getString(recordKey(userId), null) ?: return null
        return runCatching { FingerprintEnrollmentSummary.fromJson(JSONObject(serialized)) }.getOrNull()
    }

    private fun enrolledUserIds(): Set<String> =
        preferences.getStringSet(KEY_ENROLLED_USER_IDS, emptySet())?.toSet().orEmpty()

    private fun recordKey(userId: String) = "record_$userId"

    private companion object {
        const val PREFERENCES_NAME = "ekms_fingerprint_templates"
        const val KEY_ENROLLED_USER_IDS = "enrolled_user_ids"
    }
}

data class FingerprintEnrollmentSummary(
    val userId: String,
    val templateId: Int,
    val enrollmentReference: String,
    val enrolledAtEpochMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("userId", userId)
        .put("templateId", templateId)
        .put("enrollmentReference", enrollmentReference)
        .put("enrolledAtEpochMillis", enrolledAtEpochMillis)

    companion object {
        fun fromJson(json: JSONObject) = FingerprintEnrollmentSummary(
            userId = json.getString("userId"),
            templateId = json.getInt("templateId"),
            enrollmentReference = json.getString("enrollmentReference"),
            enrolledAtEpochMillis = json.getLong("enrolledAtEpochMillis"),
        )
    }
}
