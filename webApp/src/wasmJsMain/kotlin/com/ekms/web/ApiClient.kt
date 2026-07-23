package com.ekms.web

import com.ekms.shared.api.AccessGrantDto
import com.ekms.shared.api.AccessGrantListResponse
import com.ekms.shared.api.AccessGrantUpsertRequest
import com.ekms.shared.api.ApiPaths
import com.ekms.shared.api.AuditEventListResponse
import com.ekms.shared.api.AuthClientType
import com.ekms.shared.api.CreateAdminUserRequest
import com.ekms.shared.api.CredentialStatusDto
import com.ekms.shared.api.CredentialStatusListResponse
import com.ekms.shared.api.KeyDto
import com.ekms.shared.api.KeyListResponse
import com.ekms.shared.api.KeySlotDto
import com.ekms.shared.api.KeySlotListResponse
import com.ekms.shared.api.KeySlotUpsertRequest
import com.ekms.shared.api.KeyUpsertRequest
import com.ekms.shared.api.LoginRequest
import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.RecycleBinListResponse
import com.ekms.shared.api.RecycleBinPurgeRequest
import com.ekms.shared.api.RecycleBinRestoreRequest
import com.ekms.shared.api.RequestCredentialEnrollmentRequest
import com.ekms.shared.api.ResolveSyncConflictRequest
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.SiteListResponse
import com.ekms.shared.api.SiteUpsertRequest
import com.ekms.shared.api.SyncConflictListResponse
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.api.TerminalListResponse
import com.ekms.shared.api.TerminalSyncAckResponse
import com.ekms.shared.api.TerminalUpsertRequest
import com.ekms.shared.api.UpdateAdminUserAccountStatusRequest
import com.ekms.shared.api.UpdateAdminUserRequest
import com.ekms.shared.api.UserDto
import com.ekms.shared.api.UserListResponse
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.AuditEvent
import com.ekms.shared.domain.CredentialKind
import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.UserRole
import com.ekms.shared.sync.ConflictResolutionStrategy
import com.ekms.shared.sync.SyncConflict
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
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Production: same origin as the portal (path /v1 on kms-cvt.com). */
internal const val PRODUCTION_API_BASE_URL: String = "https://kms-cvt.com"
/** Local Wasm preview. Port 3000 is often Fortress Control on this host — eKMS uses 3001. */
internal const val LOCAL_API_BASE_URL: String = "http://localhost:3001"

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => (typeof window !== 'undefined' && window.location && window.location.hostname) ? window.location.hostname : ''")
private external fun browserHostname(): String

/** On kms-cvt.com use same-origin API; local Wasm run keeps localhost:3001. */
internal val API_BASE_URL: String =
    if (browserHostname().endsWith("kms-cvt.com", ignoreCase = true)) {
        PRODUCTION_API_BASE_URL
    } else {
        LOCAL_API_BASE_URL
    }

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

    suspend fun createSite(request: SiteUpsertRequest): SiteDto =
        decode(post(ApiPaths.ADMIN_SITES, json.encodeToString(request)))

    suspend fun updateSite(id: String, request: SiteUpsertRequest): SiteDto =
        decode(patch("${ApiPaths.ADMIN_SITES}/$id", json.encodeToString(request)))

    suspend fun deleteSite(id: String): SiteDto =
        decode(delete("${ApiPaths.ADMIN_SITES}/$id"))

    suspend fun listTerminals(): List<TerminalDto> =
        decode<TerminalListResponse>(get(ApiPaths.ADMIN_TERMINALS)).items

    suspend fun createTerminal(request: TerminalUpsertRequest): TerminalDto =
        decode(post(ApiPaths.ADMIN_TERMINALS, json.encodeToString(request)))

    suspend fun updateTerminal(id: String, request: TerminalUpsertRequest): TerminalDto =
        decode(patch("${ApiPaths.ADMIN_TERMINALS}/$id", json.encodeToString(request)))

    suspend fun deleteTerminal(id: String): TerminalDto =
        decode(delete("${ApiPaths.ADMIN_TERMINALS}/$id"))

    suspend fun listUsers(): List<UserDto> =
        decode<UserListResponse>(get(ApiPaths.ADMIN_USERS)).items

    suspend fun createUser(
        displayName: String,
        email: String,
        role: UserRole,
        assignedSiteIds: Set<String>,
        password: String? = null,
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
                        password = password,
                    ),
                ),
            ),
        )

    suspend fun updateUser(id: String, request: UpdateAdminUserRequest): UserDto =
        decode(patch("${ApiPaths.ADMIN_USERS}/$id", json.encodeToString(request)))

    suspend fun updateUserAccountStatus(
        id: String,
        accountStatus: AccountStatus,
        expectedRevision: Long,
    ): UserDto =
        decode(
            post(
                "${ApiPaths.ADMIN_USERS}/$id/account-status",
                json.encodeToString(
                    UpdateAdminUserAccountStatusRequest(
                        accountStatus = accountStatus,
                        expectedRevision = expectedRevision,
                    ),
                ),
            ),
        )

    suspend fun deleteUser(id: String): UserDto =
        decode(delete("${ApiPaths.ADMIN_USERS}/$id"))

    suspend fun listKeys(): List<KeyDto> =
        decode<KeyListResponse>(get(ApiPaths.ADMIN_KEYS)).items

    suspend fun createKey(request: KeyUpsertRequest): KeyDto =
        decode(post(ApiPaths.ADMIN_KEYS, json.encodeToString(request)))

    suspend fun updateKey(id: String, request: KeyUpsertRequest): KeyDto =
        decode(patch("${ApiPaths.ADMIN_KEYS}/$id", json.encodeToString(request)))

    suspend fun deleteKey(id: String): KeyDto =
        decode(delete("${ApiPaths.ADMIN_KEYS}/$id"))

    suspend fun listKeySlots(): List<KeySlotDto> =
        decode<KeySlotListResponse>(get(ApiPaths.ADMIN_KEY_SLOTS)).items

    suspend fun createKeySlot(request: KeySlotUpsertRequest): KeySlotDto =
        decode(post(ApiPaths.ADMIN_KEY_SLOTS, json.encodeToString(request)))

    suspend fun updateKeySlot(id: String, request: KeySlotUpsertRequest): KeySlotDto =
        decode(patch("${ApiPaths.ADMIN_KEY_SLOTS}/$id", json.encodeToString(request)))

    suspend fun deleteKeySlot(id: String): KeySlotDto =
        decode(delete("${ApiPaths.ADMIN_KEY_SLOTS}/$id"))

    suspend fun listAccessGrants(): List<AccessGrantDto> =
        decode<AccessGrantListResponse>(get(ApiPaths.ADMIN_ACCESS_GRANTS)).items

    suspend fun createAccessGrant(request: AccessGrantUpsertRequest): AccessGrantDto =
        decode(post(ApiPaths.ADMIN_ACCESS_GRANTS, json.encodeToString(request)))

    suspend fun updateAccessGrant(id: String, request: AccessGrantUpsertRequest): AccessGrantDto =
        decode(patch("${ApiPaths.ADMIN_ACCESS_GRANTS}/$id", json.encodeToString(request)))

    suspend fun deleteAccessGrant(id: String): AccessGrantDto =
        decode(delete("${ApiPaths.ADMIN_ACCESS_GRANTS}/$id"))

    suspend fun listRecycleBin(): RecycleBinListResponse =
        decode(get(ApiPaths.RECYCLE_BIN))

    suspend fun restoreRecycleBinEntry(
        recordType: RecordType,
        recordId: String,
        expectedRevision: Long? = null,
    ) {
        post(
            "${ApiPaths.RECYCLE_BIN}/restore",
            json.encodeToString(
                RecycleBinRestoreRequest(
                    recordType = recordType,
                    recordId = recordId,
                    expectedRevision = expectedRevision,
                ),
            ),
        )
    }

    suspend fun purgeRecycleBinEntry(recordType: RecordType, recordId: String) {
        post(
            "${ApiPaths.RECYCLE_BIN}/purge",
            json.encodeToString(
                RecycleBinPurgeRequest(recordType = recordType, recordId = recordId),
            ),
        )
    }

    suspend fun purgeExpiredRecycleBin() {
        post("${ApiPaths.RECYCLE_BIN}/purge-expired", "{}")
    }

    suspend fun listAuditEvents(
        siteId: String? = null,
        actorUserId: String? = null,
        limit: Int = 100,
    ): List<AuditEvent> {
        val query = buildString {
            append("?limit=$limit")
            if (siteId != null) append("&siteId=$siteId")
            if (actorUserId != null) append("&actorUserId=$actorUserId")
        }
        return decode<AuditEventListResponse>(get("${ApiPaths.AUDIT_EVENTS}$query")).items
    }

    suspend fun listUserCredentials(userId: String): List<CredentialStatusDto> =
        decode<CredentialStatusListResponse>(
            get(ApiPaths.ADMIN_USER_CREDENTIALS.replace("{userId}", userId)),
        ).items

    suspend fun requestCredentialEnrollment(
        userId: String,
        credentialKind: CredentialKind,
        terminalId: String? = null,
        note: String? = null,
    ): CredentialStatusDto =
        decode(
            post(
                ApiPaths.ADMIN_USER_CREDENTIALS.replace("{userId}", userId),
                json.encodeToString(
                    RequestCredentialEnrollmentRequest(
                        credentialKind = credentialKind,
                        terminalId = terminalId,
                        note = note,
                    ),
                ),
            ),
        )

    suspend fun terminalSyncRead(terminalId: String): TerminalSyncAckResponse =
        decode(
            post(
                ApiPaths.TERMINAL_DATA_READ,
                """{"terminalId":"$terminalId"}""",
            ),
        )

    suspend fun terminalSyncDownload(terminalId: String): TerminalSyncAckResponse =
        decode(
            post(
                ApiPaths.TERMINAL_DATA_DOWNLOAD,
                """{"terminalId":"$terminalId"}""",
            ),
        )

    suspend fun listSyncConflicts(): List<SyncConflict> =
        decode<SyncConflictListResponse>(get(ApiPaths.SYNC_CONFLICTS)).items

    suspend fun resolveSyncConflict(
        conflictId: String,
        strategy: ConflictResolutionStrategy,
        mergedPayloadJson: String? = null,
    ) {
        post(
            "${ApiPaths.SYNC_CONFLICTS}/$conflictId/resolve",
            json.encodeToString(
                ResolveSyncConflictRequest(
                    strategy = strategy,
                    mergedPayloadJson = mergedPayloadJson,
                ),
            ),
        )
    }

    private suspend fun get(path: String): String =
        send(HttpMethod.Get, path, body = null, authenticated = true, idempotent = false)

    private suspend fun post(path: String, body: String): String =
        send(HttpMethod.Post, path, body = body, authenticated = true, idempotent = true)

    private suspend fun patch(path: String, body: String): String =
        send(HttpMethod.Patch, path, body = body, authenticated = true, idempotent = true)

    private suspend fun delete(path: String): String =
        send(HttpMethod.Delete, path, body = null, authenticated = true, idempotent = true)

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
