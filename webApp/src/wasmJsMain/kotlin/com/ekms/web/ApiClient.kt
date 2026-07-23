package com.ekms.web

import com.ekms.shared.api.AccessGrantDto
import com.ekms.shared.api.AccessGrantListResponse
import com.ekms.shared.api.AccessGrantUpsertRequest
import com.ekms.shared.api.ApiPaths
import com.ekms.shared.api.AuthClientType
import com.ekms.shared.api.CreateAdminUserRequest
import com.ekms.shared.api.KeyDto
import com.ekms.shared.api.KeyListResponse
import com.ekms.shared.api.KeySlotDto
import com.ekms.shared.api.KeySlotListResponse
import com.ekms.shared.api.KeySlotUpsertRequest
import com.ekms.shared.api.KeyUpsertRequest
import com.ekms.shared.api.LoginRequest
import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.SiteListResponse
import com.ekms.shared.api.SiteUpsertRequest
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.api.TerminalListResponse
import com.ekms.shared.api.TerminalUpsertRequest
import com.ekms.shared.api.UserDto
import com.ekms.shared.api.UserListResponse
import com.ekms.shared.domain.UserRole
import io.ktor.client.HttpClient
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val API_BASE_URL: String = "http://localhost:3000"

internal class ApiException(val status: Int, override val message: String) : Exception(message)

internal object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    var accessToken: String? = null
    var refreshToken: String? = null

    fun clearSession() {
        accessToken = null
        refreshToken = null
    }

    suspend fun login(identifier: String, password: String): LoginResponse {
        val login = decode<LoginResponse>(
            send(
                method = HttpMethod.Post,
                path = ApiPaths.AUTH_LOGIN,
                body = json.encodeToString(
                    LoginRequest(
                        identifier = identifier.trim(),
                        password = password,
                        clientType = AuthClientType.WEB,
                        deviceId = "web-preview",
                    ),
                ),
                authenticated = false,
                idempotent = false,
            ),
        )
        accessToken = login.accessToken
        refreshToken = login.refreshToken
        return login
    }

    suspend fun listSites(): List<SiteDto> =
        decode<SiteListResponse>(get(ApiPaths.ADMIN_SITES)).items

    suspend fun createSite(name: String, address: String?): SiteDto =
        decode(
            post(
                ApiPaths.ADMIN_SITES,
                json.encodeToString(SiteUpsertRequest(name = name, address = address)),
            ),
        )

    suspend fun listTerminals(): List<TerminalDto> =
        decode<TerminalListResponse>(get(ApiPaths.ADMIN_TERMINALS)).items

    suspend fun createTerminal(request: TerminalUpsertRequest): TerminalDto =
        decode(post(ApiPaths.ADMIN_TERMINALS, json.encodeToString(request)))

    suspend fun listUsers(): List<UserDto> =
        decode<UserListResponse>(get(ApiPaths.ADMIN_USERS)).items

    suspend fun createUser(
        displayName: String,
        email: String,
        role: UserRole,
        assignedSiteIds: Set<String>,
    ): UserDto =
        decode(
            post(
                ApiPaths.ADMIN_USERS,
                json.encodeToString(
                    CreateAdminUserRequest(
                        displayName = displayName,
                        email = email,
                        role = role,
                        assignedSiteIds = assignedSiteIds,
                    ),
                ),
            ),
        )

    suspend fun listKeys(): List<KeyDto> =
        decode<KeyListResponse>(get(ApiPaths.ADMIN_KEYS)).items

    suspend fun createKey(request: KeyUpsertRequest): KeyDto =
        decode(post(ApiPaths.ADMIN_KEYS, json.encodeToString(request)))

    suspend fun listKeySlots(): List<KeySlotDto> =
        decode<KeySlotListResponse>(get(ApiPaths.ADMIN_KEY_SLOTS)).items

    suspend fun createKeySlot(request: KeySlotUpsertRequest): KeySlotDto =
        decode(post(ApiPaths.ADMIN_KEY_SLOTS, json.encodeToString(request)))

    suspend fun listAccessGrants(): List<AccessGrantDto> =
        decode<AccessGrantListResponse>(get(ApiPaths.ADMIN_ACCESS_GRANTS)).items

    suspend fun createAccessGrant(request: AccessGrantUpsertRequest): AccessGrantDto =
        decode(post(ApiPaths.ADMIN_ACCESS_GRANTS, json.encodeToString(request)))

    private suspend fun get(path: String): String =
        send(HttpMethod.Get, path, body = null, authenticated = true, idempotent = false)

    private suspend fun post(path: String, body: String): String =
        send(HttpMethod.Post, path, body = body, authenticated = true, idempotent = true)

    private inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String?,
        authenticated: Boolean,
        idempotent: Boolean,
    ): String {
        val response = http.request("$API_BASE_URL$path") {
            this.method = method
            contentType(ContentType.Application.Json)
            if (authenticated) {
                val token = accessToken ?: throw ApiException(401, "Not signed in")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (idempotent) {
                header("Idempotency-Key", Uuid.random().toString())
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
            throw ApiException(response.status.value, message)
        }
        return text
    }
}

@Serializable
private data class ErrorBody(val message: String? = null, val error: String? = null)
