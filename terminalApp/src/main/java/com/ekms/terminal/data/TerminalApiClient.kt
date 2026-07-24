package com.ekms.terminal.data

import android.content.Context
import android.content.SharedPreferences
import com.ekms.shared.api.ApiPaths
import com.ekms.shared.api.AuthClientType
import com.ekms.shared.api.CompleteCredentialEnrollmentRequest
import com.ekms.shared.api.CreateAdminUserRequest
import com.ekms.shared.api.CredentialStatusDto
import com.ekms.shared.api.CredentialStatusListResponse
import com.ekms.shared.api.LoginRequest
import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.RefreshTokenRequest
import com.ekms.shared.api.RevokeCredentialEnrollmentRequest
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.SiteListResponse
import com.ekms.shared.api.TerminalBootstrapRequest
import com.ekms.shared.api.TerminalBootstrapResponse
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.api.TerminalSyncAckResponse
import com.ekms.shared.api.TerminalSyncPushRequest
import com.ekms.shared.api.TerminalSyncPushResponse
import com.ekms.shared.api.UserDto
import com.ekms.shared.api.UserListResponse
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

    var baseUrl: String
        get() = preferences.getString(KEY_BASE_URL, "")?.trim().orEmpty().trimEnd('/')
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
        if (trimmed.isNotBlank()) {
            baseUrl = trimmed
        }
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
    }
}

class TerminalApiException(val status: Int, override val message: String) : Exception(message)

@Serializable
private data class ErrorBody(val message: String? = null, val error: String? = null)
