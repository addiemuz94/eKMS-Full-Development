package com.ekms.terminal.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local bootstrap storage for the Android Terminal.
 *
 * There is exactly one built-in account. All Technician and Vendor accounts
 * are created by that Super Admin after the first sign-in. Passwords are
 * stored as PBKDF2 hashes, never as plaintext.
 *
 * This local store is intentionally a terminal bootstrap mechanism. During
 * the backend-sync milestone, the central server becomes authoritative for
 * user, key and credential data.
 */
class TerminalAdminStore(context: Context) {

    companion object {
        const val SUPER_ADMIN_USERNAME = "Super Admin"

        private const val DEFAULT_BOOTSTRAP_PASSWORD = "admin1234"
        private const val PREFERENCES_NAME = "ekms_terminal_admin"
        private const val KEY_SEEDED = "seeded"
        private const val KEY_SUPER_ADMIN_SALT = "super_admin_salt"
        private const val KEY_SUPER_ADMIN_HASH = "super_admin_hash"
        private const val KEY_FORCE_PASSWORD_CHANGE = "force_password_change"
        private const val KEY_USERS = "managed_users"
        private const val KEY_KEYS = "managed_keys"
        private const val KEY_ACCESS_GRANTS = "managed_access_grants"
        private const val KEY_CABINET_SETTINGS = "cabinet_settings"
        private const val PASSWORD_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val MIN_KEY_NODE_COUNT = 1
        /** Per docs/Key Cabinet Communication Protocol.md §7.1: key nodes are 1-127. */
        private const val MAX_KEY_NODE_COUNT = 127
        private const val DEFAULT_KEY_NODE_COUNT = 24
    }

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        ensureSeeded()
    }

    @Synchronized
    fun snapshot(): TerminalAdminSnapshot = TerminalAdminSnapshot(
        forceSuperAdminPasswordChange = preferences.getBoolean(KEY_FORCE_PASSWORD_CHANGE, true),
        users = listOf(
            TerminalUser(
                id = "super_admin",
                displayName = SUPER_ADMIN_USERNAME,
                username = SUPER_ADMIN_USERNAME,
                role = TerminalUserRole.SUPER_ADMIN,
                isPreset = true,
                createdAtEpochMillis = 0L,
            ),
        ) + readUsers(),
        keys = readKeys(),
        accessGrants = readAccessGrants(),
        cabinetSettings = readCabinetSettings(),
    )

    @Synchronized
    fun authenticate(username: String, password: String): StoreResult<TerminalSession> {
        val normalizedUsername = username.trim()
        if (normalizedUsername.equals(SUPER_ADMIN_USERNAME, ignoreCase = true)) {
            val salt = preferences.getString(KEY_SUPER_ADMIN_SALT, null)
                ?: return StoreResult.Error("The Super Admin bootstrap record is unavailable.")
            val expectedHash = preferences.getString(KEY_SUPER_ADMIN_HASH, null)
                ?: return StoreResult.Error("The Super Admin bootstrap record is unavailable.")

            if (!verifyPassword(password, salt, expectedHash)) {
                return StoreResult.Error("Username or password is incorrect.")
            }

            return StoreResult.Success(
                TerminalSession(
                    userId = "super_admin",
                    displayName = SUPER_ADMIN_USERNAME,
                    username = SUPER_ADMIN_USERNAME,
                    role = TerminalUserRole.SUPER_ADMIN,
                    requiresPasswordChange = preferences.getBoolean(KEY_FORCE_PASSWORD_CHANGE, true),
                ),
            )
        }

        val user = readUsers().firstOrNull {
            it.username.equals(normalizedUsername, ignoreCase = true)
        } ?: return StoreResult.Error("Username or password is incorrect.")

        if (!verifyPassword(password, user.passwordSalt, user.passwordHash)) {
            return StoreResult.Error("Username or password is incorrect.")
        }

        return StoreResult.Success(
            TerminalSession(
                userId = user.id,
                displayName = user.displayName,
                username = user.username,
                role = user.role,
                requiresPasswordChange = false,
            ),
        )
    }

    @Synchronized
    fun changeSuperAdminPassword(
        currentPassword: String,
        newPassword: String,
    ): StoreResult<Unit> {
        if (newPassword.length < 8) {
            return StoreResult.Error("Use a new password with at least 8 characters.")
        }

        val currentSalt = preferences.getString(KEY_SUPER_ADMIN_SALT, null)
            ?: return StoreResult.Error("The Super Admin bootstrap record is unavailable.")
        val currentHash = preferences.getString(KEY_SUPER_ADMIN_HASH, null)
            ?: return StoreResult.Error("The Super Admin bootstrap record is unavailable.")

        if (!verifyPassword(currentPassword, currentSalt, currentHash)) {
            return StoreResult.Error("The current password is incorrect.")
        }

        val credential = createPasswordCredential(newPassword)
        preferences.edit()
            .putString(KEY_SUPER_ADMIN_SALT, credential.salt)
            .putString(KEY_SUPER_ADMIN_HASH, credential.hash)
            .putBoolean(KEY_FORCE_PASSWORD_CHANGE, false)
            .apply()

        return StoreResult.Success(Unit)
    }

    @Synchronized
    fun createUser(
        displayName: String,
        username: String,
        temporaryPassword: String,
        role: TerminalUserRole,
    ): StoreResult<TerminalUser> {
        val trimmedName = displayName.trim()
        val trimmedUsername = username.trim()

        if (trimmedName.length < 2) {
            return StoreResult.Error("Enter a name with at least 2 characters.")
        }
        if (!trimmedUsername.matches(Regex("^[A-Za-z0-9._-]{3,40}$"))) {
            return StoreResult.Error("Username must use 3–40 letters, numbers, dot, underscore or hyphen.")
        }
        if (temporaryPassword.length < 8) {
            return StoreResult.Error("Temporary password must contain at least 8 characters.")
        }
        if (role == TerminalUserRole.SUPER_ADMIN) {
            return StoreResult.Error("Only the single preset Super Admin account may use this role.")
        }
        if (trimmedUsername.equals(SUPER_ADMIN_USERNAME, ignoreCase = true) ||
            readUsers().any { it.username.equals(trimmedUsername, ignoreCase = true) }
        ) {
            return StoreResult.Error("That username is already in use.")
        }

        val credential = createPasswordCredential(temporaryPassword)
        val user = TerminalUser(
            id = "user_" + UUID.randomUUID().toString(),
            displayName = trimmedName,
            username = trimmedUsername,
            role = role,
            isPreset = false,
            createdAtEpochMillis = System.currentTimeMillis(),
            passwordSalt = credential.salt,
            passwordHash = credential.hash,
        )

        saveUsers(readUsers() + user)
        return StoreResult.Success(user)
    }

    /**
     * Stores an irreversible fingerprint of the node-read fob UID. The raw
     * UID is never written to this store or displayed by the Terminal UI.
     */
    @Synchronized
    fun createKey(
        displayName: String,
        boxAddress: Int,
        nodeAddress: Int,
        rawFobUid: String,
    ): StoreResult<TerminalKey> {
        val trimmedName = displayName.trim()
        if (trimmedName.length < 2) {
            return StoreResult.Error("Enter a key name with at least 2 characters.")
        }
        if (boxAddress !in 1..255) {
            return StoreResult.Error("Box Address must be from 1 to 255.")
        }
        if (nodeAddress !in 0..MAX_KEY_NODE_COUNT) {
            return StoreResult.Error("Raw Node Address must be from 0 to $MAX_KEY_NODE_COUNT.")
        }

        val normalizedUid = rawFobUid
            .filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
            .uppercase(Locale.US)
        if (normalizedUid.length < 8) {
            return StoreResult.Error("Read a physical key fob from the selected node before saving.")
        }

        val keyRecords = readKeys()
        val fobFingerprint = sha256(normalizedUid)
        if (keyRecords.any { it.boxAddress == boxAddress && it.nodeAddress == nodeAddress }) {
            return StoreResult.Error("A key is already registered at Box " + boxAddress + ", Node " + nodeAddress + ".")
        }
        if (keyRecords.any { it.fobFingerprint == fobFingerprint }) {
            return StoreResult.Error("This physical fob is already enrolled to another key.")
        }

        val key = TerminalKey(
            id = "key_" + UUID.randomUUID().toString(),
            displayName = trimmedName,
            boxAddress = boxAddress,
            nodeAddress = nodeAddress,
            fobFingerprint = fobFingerprint,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        saveKeys(keyRecords + key)
        return StoreResult.Success(key)
    }

    /**
     * Binds one enrolled user to one enrolled key. This is a local bootstrap
     * grant only; the Super Admin's own access is implicit and is never
     * represented as a grant. The central backend becomes authoritative for
     * access grants during the sync milestone, following the same
     * user-to-exact-key model as the Website (shared AccessGrant contract).
     */
    @Synchronized
    fun grantAccess(userId: String, keyId: String): StoreResult<TerminalAccessGrant> {
        if (readUsers().none { it.id == userId }) {
            return StoreResult.Error("Select an enrolled user before granting a key.")
        }
        if (readKeys().none { it.id == keyId }) {
            return StoreResult.Error("Select an enrolled key before granting access.")
        }

        val existingGrants = readAccessGrants()
        if (existingGrants.any { it.userId == userId && it.keyId == keyId }) {
            return StoreResult.Error("That exact key is already granted to this user.")
        }

        val grant = TerminalAccessGrant(
            id = "grant_" + UUID.randomUUID().toString(),
            userId = userId,
            keyId = keyId,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        saveAccessGrants(existingGrants + grant)
        return StoreResult.Success(grant)
    }

    @Synchronized
    fun revokeAccess(grantId: String): StoreResult<Unit> {
        val existingGrants = readAccessGrants()
        if (existingGrants.none { it.id == grantId }) {
            return StoreResult.Error("This access grant no longer exists.")
        }
        saveAccessGrants(existingGrants.filterNot { it.id == grantId })
        return StoreResult.Success(Unit)
    }

    /**
     * Section 4 (Admin Menu) settings for this exact cabinet: name, ID,
     * server address, activation code, configured key node count, and the
     * three toggles Sections 3/4 read (Key Return Certification, return/
     * retrieval video). Ethernet MAC address is display-only and is not
     * part of this stored settings record; see [readEthernetMacAddress].
     */
    @Synchronized
    fun updateCabinetSettings(settings: TerminalCabinetSettings): StoreResult<TerminalCabinetSettings> {
        if (settings.configuredKeyNodeCount !in MIN_KEY_NODE_COUNT..MAX_KEY_NODE_COUNT) {
            return StoreResult.Error("Key node setting must be from $MIN_KEY_NODE_COUNT to $MAX_KEY_NODE_COUNT.")
        }

        val normalized = settings.copy(
            cabinetName = settings.cabinetName.trim(),
            cabinetId = settings.cabinetId.trim(),
            serverAddress = settings.serverAddress.trim(),
            activationCode = settings.activationCode.trim(),
        )
        saveCabinetSettings(normalized)
        return StoreResult.Success(normalized)
    }

    private fun ensureSeeded() {
        if (preferences.getBoolean(KEY_SEEDED, false)) return

        val credential = createPasswordCredential(DEFAULT_BOOTSTRAP_PASSWORD)
        preferences.edit()
            .putBoolean(KEY_SEEDED, true)
            .putString(KEY_SUPER_ADMIN_SALT, credential.salt)
            .putString(KEY_SUPER_ADMIN_HASH, credential.hash)
            .putBoolean(KEY_FORCE_PASSWORD_CHANGE, true)
            .putString(KEY_USERS, "[]")
            .putString(KEY_KEYS, "[]")
            .putString(KEY_ACCESS_GRANTS, "[]")
            .apply()
    }

    private fun readUsers(): List<TerminalUser> = decodeArray(KEY_USERS) { item ->
        TerminalUser(
            id = item.getString("id"),
            displayName = item.getString("displayName"),
            username = item.getString("username"),
            role = TerminalUserRole.valueOf(item.getString("role")),
            isPreset = false,
            createdAtEpochMillis = item.getLong("createdAtEpochMillis"),
            passwordSalt = item.getString("passwordSalt"),
            passwordHash = item.getString("passwordHash"),
        )
    }

    private fun saveUsers(users: List<TerminalUser>) {
        val items = JSONArray()
        users.forEach { user ->
            items.put(
                JSONObject()
                    .put("id", user.id)
                    .put("displayName", user.displayName)
                    .put("username", user.username)
                    .put("role", user.role.name)
                    .put("createdAtEpochMillis", user.createdAtEpochMillis)
                    .put("passwordSalt", user.passwordSalt)
                    .put("passwordHash", user.passwordHash),
            )
        }
        preferences.edit().putString(KEY_USERS, items.toString()).apply()
    }

    private fun readKeys(): List<TerminalKey> = decodeArray(KEY_KEYS) { item ->
        TerminalKey(
            id = item.getString("id"),
            displayName = item.getString("displayName"),
            boxAddress = item.getInt("boxAddress"),
            nodeAddress = item.getInt("nodeAddress"),
            fobFingerprint = item.getString("fobFingerprint"),
            createdAtEpochMillis = item.getLong("createdAtEpochMillis"),
        )
    }

    private fun saveKeys(keys: List<TerminalKey>) {
        val items = JSONArray()
        keys.forEach { key ->
            items.put(
                JSONObject()
                    .put("id", key.id)
                    .put("displayName", key.displayName)
                    .put("boxAddress", key.boxAddress)
                    .put("nodeAddress", key.nodeAddress)
                    .put("fobFingerprint", key.fobFingerprint)
                    .put("createdAtEpochMillis", key.createdAtEpochMillis),
            )
        }
        preferences.edit().putString(KEY_KEYS, items.toString()).apply()
    }

    private fun readAccessGrants(): List<TerminalAccessGrant> = decodeArray(KEY_ACCESS_GRANTS) { item ->
        TerminalAccessGrant(
            id = item.getString("id"),
            userId = item.getString("userId"),
            keyId = item.getString("keyId"),
            createdAtEpochMillis = item.getLong("createdAtEpochMillis"),
        )
    }

    private fun saveAccessGrants(grants: List<TerminalAccessGrant>) {
        val items = JSONArray()
        grants.forEach { grant ->
            items.put(
                JSONObject()
                    .put("id", grant.id)
                    .put("userId", grant.userId)
                    .put("keyId", grant.keyId)
                    .put("createdAtEpochMillis", grant.createdAtEpochMillis),
            )
        }
        preferences.edit().putString(KEY_ACCESS_GRANTS, items.toString()).apply()
    }

    private fun readCabinetSettings(): TerminalCabinetSettings {
        val encoded = preferences.getString(KEY_CABINET_SETTINGS, null)
            ?: return TerminalCabinetSettings(configuredKeyNodeCount = DEFAULT_KEY_NODE_COUNT)
        return runCatching {
            val item = JSONObject(encoded)
            TerminalCabinetSettings(
                cabinetName = item.optString("cabinetName", ""),
                cabinetId = item.optString("cabinetId", ""),
                serverAddress = item.optString("serverAddress", ""),
                activationCode = item.optString("activationCode", ""),
                configuredKeyNodeCount = if (item.has("configuredKeyNodeCount")) {
                    item.getInt("configuredKeyNodeCount")
                } else {
                    DEFAULT_KEY_NODE_COUNT
                },
                keyReturnCertificationEnabled = item.optBoolean("keyReturnCertificationEnabled", false),
                returnKeyVideoEnabled = item.optBoolean("returnKeyVideoEnabled", false),
                keyRetrievalVideoEnabled = item.optBoolean("keyRetrievalVideoEnabled", false),
            )
        }.getOrElse { TerminalCabinetSettings(configuredKeyNodeCount = DEFAULT_KEY_NODE_COUNT) }
    }

    private fun saveCabinetSettings(settings: TerminalCabinetSettings) {
        val item = JSONObject()
            .put("cabinetName", settings.cabinetName)
            .put("cabinetId", settings.cabinetId)
            .put("serverAddress", settings.serverAddress)
            .put("activationCode", settings.activationCode)
            .put("configuredKeyNodeCount", settings.configuredKeyNodeCount)
            .put("keyReturnCertificationEnabled", settings.keyReturnCertificationEnabled)
            .put("returnKeyVideoEnabled", settings.returnKeyVideoEnabled)
            .put("keyRetrievalVideoEnabled", settings.keyRetrievalVideoEnabled)
        preferences.edit().putString(KEY_CABINET_SETTINGS, item.toString()).apply()
    }

    private fun <T> decodeArray(
        preferenceKey: String,
        decode: (JSONObject) -> T,
    ): List<T> {
        val encoded = preferences.getString(preferenceKey, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    add(decode(array.getJSONObject(index)))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun createPasswordCredential(password: String): PasswordCredential {
        val saltBytes = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hashBytes = passwordHash(password, saltBytes)
        return PasswordCredential(
            salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP),
            hash = Base64.encodeToString(hashBytes, Base64.NO_WRAP),
        )
    }

    private fun verifyPassword(password: String, encodedSalt: String, encodedHash: String): Boolean =
        runCatching {
            val actual = passwordHash(password, Base64.decode(encodedSalt, Base64.NO_WRAP))
            val expected = Base64.decode(encodedHash, Base64.NO_WRAP)
            MessageDigest.isEqual(actual, expected)
        }.getOrDefault(false)

    private fun passwordHash(password: String, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
        } finally {
            specification.clearPassword()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
            }

    private data class PasswordCredential(
        val salt: String,
        val hash: String,
    )
}

data class TerminalAdminSnapshot(
    val forceSuperAdminPasswordChange: Boolean,
    val users: List<TerminalUser>,
    val keys: List<TerminalKey>,
    val accessGrants: List<TerminalAccessGrant>,
    val cabinetSettings: TerminalCabinetSettings,
)

/** Smart Key Cabinet User Manual V2.1, Section 4 (Admin Menu) settings for this cabinet. */
data class TerminalCabinetSettings(
    val cabinetName: String = "",
    val cabinetId: String = "",
    val serverAddress: String = "",
    val activationCode: String = "",
    val configuredKeyNodeCount: Int = 24,
    val keyReturnCertificationEnabled: Boolean = false,
    val returnKeyVideoEnabled: Boolean = false,
    val keyRetrievalVideoEnabled: Boolean = false,
)

data class TerminalUser(
    val id: String,
    val displayName: String,
    val username: String,
    val role: TerminalUserRole,
    val isPreset: Boolean,
    val createdAtEpochMillis: Long,
    internal val passwordSalt: String = "",
    internal val passwordHash: String = "",
)

enum class TerminalUserRole(val label: String) {
    SUPER_ADMIN("Super Admin"),
    TECHNICIAN("Technician"),
    VENDOR("Vendor"),
}

data class TerminalKey(
    val id: String,
    val displayName: String,
    val boxAddress: Int,
    val nodeAddress: Int,
    /** SHA-256 of the raw fob UID; the raw UID is not retained. */
    val fobFingerprint: String,
    val createdAtEpochMillis: Long,
)

/** Binds one enrolled user to one enrolled key. Separate from both records, matching the shared AccessGrant model. */
data class TerminalAccessGrant(
    val id: String,
    val userId: String,
    val keyId: String,
    val createdAtEpochMillis: Long,
)

data class TerminalSession(
    val userId: String,
    val displayName: String,
    val username: String,
    val role: TerminalUserRole,
    val requiresPasswordChange: Boolean,
) {
    val isSuperAdmin: Boolean
        get() = role == TerminalUserRole.SUPER_ADMIN
}

sealed class StoreResult<out T> {
    data class Success<T>(val value: T) : StoreResult<T>()
    data class Error(val message: String) : StoreResult<Nothing>()
}
