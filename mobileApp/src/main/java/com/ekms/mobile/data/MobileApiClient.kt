package com.ekms.mobile.data

import android.content.Context
import android.content.SharedPreferences
import com.ekms.shared.api.AccessGrantDto
import com.ekms.shared.api.AccessGrantListResponse
import com.ekms.shared.api.ApiPaths
import com.ekms.shared.api.ApproveKeyAccessRequestResponse
import com.ekms.shared.api.AuthClientType
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.api.CreateKeyAccessRequestRequest
import com.ekms.shared.api.KeyAccessRequestDto
import com.ekms.shared.api.KeyAccessRequestListResponse
import com.ekms.shared.api.KeyDto
import com.ekms.shared.api.KeyListResponse
import com.ekms.shared.api.LoginRequest
import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.RefreshTokenRequest
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.SiteKeyAccessPolicyDto
import com.ekms.shared.api.SiteListResponse
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.api.TerminalListResponse
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

/**
 * HTTP client for the eKMS backend — mobileApp's foundation piece for real Super Admin
 * companion auth (Super Admin / Regional Admin / Technician / Vendor, all four roles; the
 * backend's `/v1/auth/login` route resolves role from the account itself, not from
 * [AuthClientType]). Mirrors `terminalApp/.../data/TerminalApiClient.kt`'s shape/conventions
 * (same Ktor + OkHttp + kotlinx.serialization stack, same SharedPreferences token storage, same
 * default base URL) deliberately, so the two Android apps share one HTTP approach rather than
 * inventing a second one.
 *
 * Auth (login/refresh/session-storage) was this class's whole job in the foundation pass; this
 * pass adds exactly the Key Access Request feature's data needs on top — access grants, keys,
 * a site's Region-derived duration ceiling, and key-access-request list/create/approve/reject.
 * Still no methods for dashboard map or FCM push — those remain later phases.
 */
class MobileApiClient(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Do not emit `"field": null` — backend Zod `.optional()` rejects null (login deviceId).
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    /** Defaults to the production VPS, matching terminalApp's own `DEFAULT_BASE_URL` — this app
     * has no pairing-code-style onboarding, so there's no separate "fresh device" base URL case
     * to consider; a Super Admin can still override via a future settings screen if needed. */
    var baseUrl: String
        get() = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trim().orEmpty().trimEnd('/')
        set(value) {
            preferences.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
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

    /** The signed-in profile from the most recent successful login/refresh — kept alongside the
     * tokens so the UI can show role/displayName without a separate profile fetch. Cleared on
     * [clearSession]. */
    var profile: AuthUserProfile?
        get() = preferences.getString(KEY_PROFILE, null)?.let {
            runCatching { json.decodeFromString<AuthUserProfile>(it) }.getOrNull()
        }
        private set(value) {
            preferences.edit().putString(KEY_PROFILE, value?.let { json.encodeToString(it) }).apply()
        }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank()

    fun clearSession() {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_PROFILE)
            .apply()
    }

    suspend fun login(identifier: String, password: String, deviceId: String? = null): LoginResponse {
        ensureBaseUrl()
        val response = decode<LoginResponse>(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.AUTH_LOGIN,
                body = json.encodeToString(
                    LoginRequest(
                        identifier = identifier.trim(),
                        password = password,
                        clientType = AuthClientType.MOBILE,
                        // Always send a string — matches web's `web-react` / terminal's device id.
                        deviceId = deviceId?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_ID,
                    ),
                ),
                authenticated = false,
            ),
        )
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        profile = response.profile
        return response
    }

    suspend fun refreshAccessToken(): LoginResponse {
        ensureBaseUrl()
        val token = refreshToken ?: throw MobileApiException(401, "Not signed in to the server")
        val response = decode<LoginResponse>(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.AUTH_REFRESH,
                body = json.encodeToString(RefreshTokenRequest(refreshToken = token)),
                authenticated = false,
            ),
        )
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        profile = response.profile
        return response
    }

    /**
     * For TECHNICIAN/VENDOR this is always self-scoped server-side to the caller's own grants
     * (see `accessGrants.js`'s TECHNICIAN/VENDOR branch). Kept for other screens; Only B Apply
     * uses [listExceptionSites] / [listExceptionSiteKeys] instead.
     */
    suspend fun listMyAccessGrants(): List<AccessGrantDto> {
        ensureBaseUrl()
        return decode<AccessGrantListResponse>(
            send(method = HttpMethod.Get, path = ApiPaths.ADMIN_ACCESS_GRANTS, body = null, authenticated = true),
        ).items
    }

    /** Self-scoped standing-site keys — not used by Only B Apply (exception keys endpoint). */
    suspend fun listKeys(): List<KeyDto> {
        ensureBaseUrl()
        return decode<KeyListResponse>(
            send(method = HttpMethod.Get, path = ApiPaths.ADMIN_KEYS, body = null, authenticated = true),
        ).items
    }

    /** Sites visible to the caller (server-scoped by role). */
    suspend fun listSites(): List<SiteDto> {
        ensureBaseUrl()
        return decode<SiteListResponse>(
            send(method = HttpMethod.Get, path = ApiPaths.ADMIN_SITES, body = null, authenticated = true),
        ).items
    }

    /** Terminals / key cabinets visible to the caller (server-scoped by role). */
    suspend fun listTerminals(): List<TerminalDto> {
        ensureBaseUrl()
        return decode<TerminalListResponse>(
            send(method = HttpMethod.Get, path = ApiPaths.ADMIN_TERMINALS, body = null, authenticated = true),
        ).items
    }

    /** Only B: ACTIVE sites outside the caller's standing assignments. */
    suspend fun listExceptionSites(): List<SiteDto> {
        ensureBaseUrl()
        return decode<SiteListResponse>(
            send(
                method = HttpMethod.Get,
                path = ApiPaths.ADMIN_KEY_ACCESS_EXCEPTION_SITES,
                body = null,
                authenticated = true,
            ),
        ).items
    }

    /** Only B: keys at an exception-eligible site. */
    suspend fun listExceptionSiteKeys(siteId: String): List<KeyDto> {
        ensureBaseUrl()
        return decode<KeyListResponse>(
            send(
                method = HttpMethod.Get,
                path = ApiPaths.ADMIN_KEY_ACCESS_EXCEPTION_SITE_KEYS.replace("{siteId}", siteId),
                body = null,
                authenticated = true,
            ),
        ).items
    }

    /** Legacy Region duration ceiling — unused by Only B calendar Apply (no clamp). */
    suspend fun getSiteKeyAccessPolicy(siteId: String): SiteKeyAccessPolicyDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Get,
                path = ApiPaths.ADMIN_KEY_ACCESS_REQUEST_SITE_POLICY.replace("{siteId}", siteId),
                body = null,
                authenticated = true,
            ),
        )
    }

    /** For TECHNICIAN/VENDOR the backend always ignores [requesterUserId]/[requesterRole] fields
     * in [request] and uses the caller's own identity instead (self-service) — see
     * `keyAccessRequests.js`'s POST handler. */
    suspend fun createKeyAccessRequest(request: CreateKeyAccessRequestRequest): KeyAccessRequestDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEY_ACCESS_REQUESTS,
                body = json.encodeToString(request),
                authenticated = true,
            ),
        )
    }

    /**
     * `status` defaults server-side to PENDING when omitted; pass "ALL" to see every status.
     * Scoping is entirely server-side and role-dependent, with no client-side branching needed:
     * TECHNICIAN/VENDOR always get their own requests only, REGIONAL_ADMIN gets every request at
     * a site inside their assigned Region(s), SUPER_ADMIN gets everything.
     */
    suspend fun listKeyAccessRequests(status: String? = null): List<KeyAccessRequestDto> {
        ensureBaseUrl()
        val query = if (status.isNullOrBlank()) "" else "?status=$status"
        return decode<KeyAccessRequestListResponse>(
            send(method = HttpMethod.Get, path = "${ApiPaths.ADMIN_KEY_ACCESS_REQUESTS}$query", body = null, authenticated = true),
        ).items
    }

    /** Regional Admin/Super Admin only — enforced server-side, not just by omission here. */
    suspend fun approveKeyAccessRequest(id: String): ApproveKeyAccessRequestResponse {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEY_ACCESS_REQUEST_APPROVE.replace("{id}", id),
                body = "{}",
                authenticated = true,
            ),
        )
    }

    suspend fun rejectKeyAccessRequest(id: String): KeyAccessRequestDto {
        ensureBaseUrl()
        return decode(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.ADMIN_KEY_ACCESS_REQUEST_REJECT.replace("{id}", id),
                body = "{}",
                authenticated = true,
            ),
        )
    }

    private fun ensureBaseUrl() {
        if (!isConfigured) {
            throw MobileApiException(0, "No server address configured.")
        }
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String?,
        authenticated: Boolean,
    ): String {
        val response = http.request("$baseUrl$path") {
            this.method = method
            contentType(ContentType.Application.Json)
            if (authenticated) {
                val token = accessToken ?: throw MobileApiException(401, "Not signed in to the server")
                header(HttpHeaders.Authorization, "Bearer $token")
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
            throw MobileApiException(response.status.value, message)
        }
        return text
    }

    companion object {
        private const val PREFS_NAME = "ekms_mobile_api"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_PROFILE = "profile"
        const val DEFAULT_BASE_URL = "https://kms-cvt.com"
        private const val DEFAULT_DEVICE_ID = "mobile-android"
    }
}

class MobileApiException(val status: Int, override val message: String) : Exception(message)

@Serializable
private data class ErrorBody(val message: String? = null, val error: String? = null)
