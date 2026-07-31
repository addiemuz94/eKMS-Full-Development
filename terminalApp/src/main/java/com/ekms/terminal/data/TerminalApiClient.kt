package com.ekms.terminal.data

import android.content.Context
import android.content.SharedPreferences
import com.ekms.shared.api.ApiPaths
import com.ekms.shared.api.AuthClientType
import com.ekms.shared.api.CompleteCredentialEnrollmentRequest
import com.ekms.shared.api.CreateAdminUserRequest
import com.ekms.shared.api.CreateKeyCheckoutRequest
import com.ekms.shared.api.CredentialStatusDto
import com.ekms.shared.api.CredentialStatusListResponse
import com.ekms.shared.api.FobEnrollmentCompleteRequest
import com.ekms.shared.api.FobEnrollmentResponse
import com.ekms.shared.api.KeyCheckoutDto
import com.ekms.shared.api.KeyDto
import com.ekms.shared.api.KeySlotDto
import com.ekms.shared.api.KeySlotUpsertRequest
import com.ekms.shared.api.KeyUpsertRequest
import com.ekms.shared.api.LoginRequest
import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.ApproveVendorPasskeyRequestResponse
import com.ekms.shared.api.RefreshTokenRequest
import com.ekms.shared.api.RevokeCredentialEnrollmentRequest
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.SiteListResponse
import com.ekms.shared.api.SiteOfficeHoursDto
import com.ekms.shared.api.TerminalBootstrapRequest
import com.ekms.shared.api.TerminalBootstrapResponse
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.api.TerminalPairWithCodeRequest
import com.ekms.shared.api.TerminalPairingResponse
import com.ekms.shared.api.TerminalPasskeyLoginRequest
import com.ekms.shared.api.TerminalPasskeyLoginResponse
import com.ekms.shared.api.TerminalSyncAckResponse
import com.ekms.shared.api.TerminalSyncPushRequest
import com.ekms.shared.api.TerminalSyncPushResponse
import com.ekms.shared.api.UpdateKeyCheckoutRequest
import com.ekms.shared.api.UpdateSiteOfficeHoursRequest
import com.ekms.shared.api.UserDto
import com.ekms.shared.api.UserListResponse
import com.ekms.shared.api.VendorPasskeyRequestDto
import com.ekms.shared.api.VendorPasskeyRequestListResponse
import com.ekms.shared.domain.AuditEvent
import com.ekms.shared.sync.OfflineChange
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * HTTP client for the eKMS backend.
 *
 * Base URL comes from Admin Menu "Set server address"
 * (e.g. https://kms-cvt.com or http://192.168.1.10:3001).
 * Tokens are persisted so sync can run after a successful server login.
 */
class TerminalApiClient(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    /**
     * Defaults to the production VPS so a fresh terminal can reach
     * `/v1/terminal/pair-with-code` with no manual setup — Admin Menu's
     * "Set server address" still exists as a fallback/advanced override for
     * on-prem or non-default deployments. A 6-digit pairing code has no room
     * to encode a URL, so something has to supply one before pairing.
     */
    var baseUrl: String
        get() {
            val stored = preferences.getString(KEY_BASE_URL, null)?.trim().orEmpty().trimEnd('/')
            // Blank prefs (or a cleared value) must still hit production — never an empty base URL.
            return stored.ifBlank { DEFAULT_BASE_URL }
        }
        set(value) {
            val trimmed = value.trim().trimEnd('/')
            preferences.edit()
                .putString(KEY_BASE_URL, trimmed.ifBlank { DEFAULT_BASE_URL })
                .apply()
        }

    var accessToken: String?
        get() = preferences.getString(KEY_ACCESS_TOKEN, null)
        set(value) {
            preferences.edit().putString(KEY_ACCESS_TOKEN, value).apply()
        }

    var refreshToken: String?
        get() = preferences.getString(KEY_REFRESH_TOKEN, null)
        set(value) {
            preferences.edit().putString(KEY_REFRESH_TOKEN, value).apply()
        }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank()

    fun clearSession() {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    fun syncBaseUrlFromSettings(serverAddress: String) {
        val trimmed = serverAddress.trim().trimEnd('/')
        baseUrl = trimmed.ifBlank { DEFAULT_BASE_URL }
    }

    suspend fun login(identifier: String, password: String, deviceId: String): LoginResponse {
        ensureBaseUrl()
        val response = decode<LoginResponse>(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.AUTH_LOGIN,
                body = json.encodeToString(
                    LoginRequest(
                        identifier = identifier.trim(),
                        password = password,
                        clientType = AuthClientType.TERMINAL,
                        deviceId = deviceId,
                    ),
                ),
                authenticated = false,
                idempotent = false,
            ),
        )
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        return response
    }

    suspend fun refreshAccessToken(): LoginResponse {
        ensureBaseUrl()
        val token = refreshToken ?: throw TerminalApiException(401, "Not signed in to the server")
        val response = decode<LoginResponse>(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.AUTH_REFRESH,
                body = json.encodeToString(RefreshTokenRequest(refreshToken = token)),
                authenticated = false,
                idempotent = false,
            ),
        )
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        return response
    }

    /**
     * Unauthenticated by necessity (a fresh terminal has no token yet). On success stores the
     * returned TERMINAL_DEVICE-scoped tokens in the same [accessToken]/[refreshToken] slots
     * [login] uses — this terminal has no separate "device identity" vs. "signed-in operator"
     * storage today, so pairing establishes the terminal's own persistent backend session the
     * same way a Super Admin's manual sign-in used to under the old flow. Throws
     * [TerminalApiException] on an invalid/expired/already-used code or a rate limit — callers
     * must show that error as-is, never fall back silently.
     */
    suspend fun pairWithCode(code: String): TerminalPairingResponse {
        ensureBaseUrl()
        val response = decode<TerminalPairingResponse>(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.TERMINAL_PAIR_WITH_CODE,
                body = json.encodeToString(TerminalPairWithCodeRequest(code = code)),
                authenticated = false,
                idempotent = false,
            ),
        )
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        return response
    }

    /**
     * Unauthenticated by necessity, same reasoning as [pairWithCode] — a terminal-side operator
     * entering a passkey has no token yet at the login screen. Unlike [pairWithCode] or [login],
     * this does NOT store the returned token in [accessToken]/[refreshToken] — a
     * KEY_ACCESS_SESSION-scoped token represents a specific requester admitted to specific keys
     * until a specific expiry, not this terminal's own device/operator session slots. The caller
     * (`TerminalAdminApp`) resolves a normal [com.ekms.shared.domain.TerminalSession] separately
     * via `TerminalAdminStore.authenticateByUserId` and only uses this response's
     * `keyIds`/`siteId`/`expiresAtEpochMillis` to drive straight into the take flow.
     */
    suspend fun passkeyLogin(passkey: String, terminalId: String): TerminalPasskeyLoginResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.TERMINAL_PASSKEY_LOGIN,
                body = json.encodeToString(TerminalPasskeyLoginRequest(passkey = passkey, terminalId = terminalId)),
                authenticated = false,
                idempotent = false,
            ),
        )
    }

    suspend fun bootstrap(
        terminalId: String,
        localRevision: Long,
        lastSuccessfulSyncEpochMillis: Long?,
    ): TerminalBootstrapResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.SYNC_BOOTSTRAP,
                body = json.encodeToString(
                    TerminalBootstrapRequest(
                        terminalId = terminalId,
                        lastSuccessfulSyncEpochMillis = lastSuccessfulSyncEpochMillis,
                        localRevision = localRevision,
                    ),
                ),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun push(
        terminalId: String,
        changes: List<OfflineChange>,
        auditEvents: List<AuditEvent> = emptyList(),
    ): TerminalSyncPushResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.SYNC_PUSH,
                body = json.encodeToString(
                    TerminalSyncPushRequest(
                        terminalId = terminalId,
                        changes = changes,
                        auditEvents = auditEvents,
                    ),
                ),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun read(terminalId: String): TerminalSyncAckResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.TERMINAL_DATA_READ,
                body = """{"terminalId":"$terminalId"}""",
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun download(terminalId: String): TerminalSyncAckResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.TERMINAL_DATA_DOWNLOAD,
                body = """{"terminalId":"$terminalId"}""",
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun listUsers(siteId: String? = null): List<UserDto> {
        ensureBaseUrl()
        val query = if (siteId.isNullOrBlank()) "" else "?siteId=$siteId"
        return decode<UserListResponse>(
            send(
                method = HttpMethod.Get,
                path = "${ApiPaths.ADMIN_USERS}$query",
                body = null,
                authenticated = true,
                idempotent = false,
            ),
        ).items
    }

    suspend fun listSites(): List<SiteDto> {
        ensureBaseUrl()
        return decode<SiteListResponse>(
            send(
                method = HttpMethod.Get,
                path = ApiPaths.ADMIN_SITES,
                body = null,
                authenticated = true,
                idempotent = false,
            ),
        ).items
    }

    suspend fun createUser(request: CreateAdminUserRequest): UserDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_USERS,
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun getTerminal(terminalId: String): TerminalDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Get,
                path = "${ApiPaths.ADMIN_TERMINALS}/$terminalId",
                body = null,
                authenticated = true,
                idempotent = false,
            ),
        )
    }

    /** Item 15. Both roles' allowlist admits this (Super Admin unconditionally; Regional Admin
     * per `REGIONAL_ADMIN_ALLOWED_ROUTES` in backend `middleware/auth.js`, scoped server-side to
     * their own assigned sites via `isSiteAssignedToUser`) — the terminal screen only decides
     * whether to render Save, not whether the call itself is allowed. */
    suspend fun getOfficeHours(siteId: String): SiteOfficeHoursDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Get,
                path = ApiPaths.ADMIN_SITE_OFFICE_HOURS.replace("{id}", siteId),
                body = null,
                authenticated = true,
                idempotent = false,
            ),
        )
    }

    suspend fun updateOfficeHours(siteId: String, request: UpdateSiteOfficeHoursRequest): SiteOfficeHoursDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Patch,
                path = ApiPaths.ADMIN_SITE_OFFICE_HOURS.replace("{id}", siteId),
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /**
     * Phase 5. Fired on each key's successful take — see `TerminalAdminApp.handleTakeFlowOutcome`.
     * Callers must treat a failure here as non-blocking (the physical take already succeeded) and
     * fall back to logging a local `KEY_CHECKOUT_SYNC_FAILED` event instead of surfacing an error
     * to the operator.
     */
    suspend fun createKeyCheckout(request: CreateKeyCheckoutRequest): KeyCheckoutDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEY_CHECKOUTS,
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /** Phase 5. Close-out call fired on successful return — same non-blocking contract as [createKeyCheckout]. */
    suspend fun closeKeyCheckout(id: String, request: UpdateKeyCheckoutRequest): KeyCheckoutDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Patch,
                path = "${ApiPaths.ADMIN_KEY_CHECKOUTS}/$id",
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /** Item 16. `status` defaults server-side to PENDING when omitted (see `vendorPasskeyRequests.js`). */
    suspend fun listVendorPasskeyRequests(siteId: String? = null, status: String? = null): List<VendorPasskeyRequestDto> {
        ensureBaseUrl()
        val params = buildList {
            if (!siteId.isNullOrBlank()) add("siteId=$siteId")
            if (!status.isNullOrBlank()) add("status=$status")
        }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return decode<VendorPasskeyRequestListResponse>(
            send(
                method = HttpMethod.Get,
                path = "${ApiPaths.ADMIN_VENDOR_PASSKEY_REQUESTS}$query",
                body = null,
                authenticated = true,
                idempotent = false,
            ),
        ).items
    }

    /** Super Admin only, per this phase's flagged judgment call — see [com.ekms.terminal.ui.TerminalVendorPasskeyScreen]'s doc. */
    suspend fun approveVendorPasskeyRequest(id: String): ApproveVendorPasskeyRequestResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_VENDOR_PASSKEY_REQUEST_APPROVE.replace("{id}", id),
                body = "{}",
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun rejectVendorPasskeyRequest(id: String): VendorPasskeyRequestDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_VENDOR_PASSKEY_REQUEST_REJECT.replace("{id}", id),
                body = "{}",
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun listUserCredentials(userId: String): List<CredentialStatusDto> {
        ensureBaseUrl()
        return decode<CredentialStatusListResponse>(
            send(
                method = HttpMethod.Get,
                path = ApiPaths.ADMIN_USER_CREDENTIALS.replace("{userId}", userId),
                body = null,
                authenticated = true,
                idempotent = false,
            ),
        ).items
    }

    suspend fun completeCredentialEnrollment(
        userId: String,
        request: CompleteCredentialEnrollmentRequest,
    ): CredentialStatusDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_USER_CREDENTIALS_COMPLETE.replace("{userId}", userId),
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    suspend fun revokeCredentialEnrollment(
        userId: String,
        request: RevokeCredentialEnrollmentRequest,
    ): CredentialStatusDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_USER_CREDENTIALS_REVOKE.replace("{userId}", userId),
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /** Terminal-only (see backend auth.js's TERMINAL_DEVICE_ALLOWED_ROUTES). Reports an opaque
     * fob enrollment reference for a key already assigned a KeySlot — never a raw NFC UID
     * (boundary #2). Used by the background sync auto-scan (see CabinetHardwareController /
     * TerminalAdminApp's post-download sweep) and by the Key Attachment screen. */
    suspend fun completeKeyFobEnrollment(
        keyId: String,
        request: FobEnrollmentCompleteRequest,
    ): FobEnrollmentResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEY_FOB_ENROLLMENT_COMPLETE.replace("{id}", keyId),
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /** Key Attachment's new-key-registration flow (terminal-side equivalent of web's Register
     * Keys step) — same route, same request shape as `web/src/api/client.ts`'s `createKey`.
     * Requires a real per-user Super Admin/Regional Admin token (see boundary: TERMINAL_DEVICE
     * tokens cannot reach this route at all) — the caller must confirm `serverAuthenticated`
     * before attempting this, not assume `isAuthenticated` alone is sufficient. */
    suspend fun createKey(request: KeyUpsertRequest): KeyDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEYS,
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /** Pins the new key to the exact node its physical fob was already found at (not the
     * lowest-unused-node logic web's own registration flow uses) — same route/shape as
     * `web/src/api/client.ts`'s `createKeySlot`. Same per-user-token requirement as [createKey]. */
    suspend fun createKeySlot(request: KeySlotUpsertRequest): KeySlotDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEY_SLOTS,
                body = json.encodeToString(request),
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    /** Key Attachment's new-key-registration flow — cleans up a just-created key when the
     * follow-up [createKeySlot] call fails, so a failed registration attempt doesn't leave a
     * stray, un-slotted `ManagedKey` behind on the backend. Same route/shape as
     * `web/src/api/client.ts`'s `deleteKey`; the backend soft-deletes (Recycle Bin, boundary #5)
     * same as any other delete — this is not a hard purge. */
    suspend fun deleteKey(keyId: String): KeyDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Delete,
                path = "${ApiPaths.ADMIN_KEYS}/$keyId",
                body = null,
                authenticated = true,
                idempotent = true,
            ),
        )
    }

    private fun ensureBaseUrl() {
        if (!isConfigured) {
            throw TerminalApiException(
                0,
                "Set the server address in Admin Menu (e.g. http://192.168.1.10:3000).",
            )
        }
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String?,
        authenticated: Boolean,
        idempotent: Boolean,
    ): String {
        val response = http.request("$baseUrl$path") {
            this.method = method
            contentType(ContentType.Application.Json)
            if (authenticated) {
                val token = accessToken ?: throw TerminalApiException(401, "Not signed in to the server")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (idempotent) {
                header("Idempotency-Key", UUID.randomUUID().toString())
            }
            if (body != null) {
                setBody(body)
            }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val message = try {
                json.decodeFromString<ErrorBody>(text).message
                    ?: json.decodeFromString<ErrorBody>(text).error
                    ?: text
            } catch (_: Exception) {
                text.ifBlank { "HTTP ${response.status.value}" }
            }
            throw TerminalApiException(response.status.value, message)
        }
        return text
    }

    companion object {
        private const val PREFS_NAME = "ekms_terminal_api"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        const val DEFAULT_BASE_URL = "https://kms-cvt.com"
    }
}

class TerminalApiException(val status: Int, override val message: String) : Exception(message)

@Serializable
private data class ErrorBody(val message: String? = null, val error: String? = null)
