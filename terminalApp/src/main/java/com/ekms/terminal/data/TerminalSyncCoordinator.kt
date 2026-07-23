package com.ekms.terminal.data

import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.TerminalBootstrapResponse
import com.ekms.shared.api.TerminalDownloadSnapshot
import com.ekms.shared.api.TerminalSyncAckResponse
import com.ekms.shared.api.TerminalSyncPushResponse
import com.ekms.shared.domain.UserRole

/**
 * Coordinates server login and terminal sync against the existing backend routes.
 *
 * [Key Cabinet ID] in Admin Menu must be the backend terminal UUID for sync calls.
 */
class TerminalSyncCoordinator(
    private val api: TerminalApiClient,
    private val outbox: TerminalSyncOutbox,
    private val adminStore: TerminalAdminStore,
    private val serverCache: TerminalServerCache,
) {

    fun resolveTerminalId(): String {
        val cabinetId = adminStore.snapshot().cabinetSettings.cabinetId.trim()
        if (cabinetId.isBlank()) {
            throw TerminalApiException(
                0,
                "Set Key Cabinet ID in Admin Menu to the backend terminal UUID before syncing.",
            )
        }
        return cabinetId
    }

    /**
     * Prefer server login when a server address is configured; otherwise use local bootstrap.
     * Local Super Admin remains available as an offline fallback when the network fails.
     */
    suspend fun authenticate(identifier: String, password: String, deviceId: String): AuthOutcome {
        val settings = adminStore.snapshot().cabinetSettings
        api.syncBaseUrlFromSettings(settings.serverAddress)

        if (api.isConfigured) {
            try {
                val login = api.login(mapIdentifier(identifier), password, deviceId)
                return AuthOutcome.Server(login.toTerminalSession())
            } catch (error: TerminalApiException) {
                val local = adminStore.authenticate(identifier, password)
                if (local is StoreResult.Success) {
                    return AuthOutcome.Local(
                        session = local.value,
                        serverWarning = "Server login failed (${error.message}). Signed in with local bootstrap.",
                    )
                }
                return AuthOutcome.Failed(error.message)
            } catch (error: Exception) {
                val local = adminStore.authenticate(identifier, password)
                if (local is StoreResult.Success) {
                    return AuthOutcome.Local(
                        session = local.value,
                        serverWarning = "Server unreachable. Signed in with local bootstrap.",
                    )
                }
                return AuthOutcome.Failed(error.message ?: "Unable to sign in.")
            }
        }

        return when (val local = adminStore.authenticate(identifier, password)) {
            is StoreResult.Success -> AuthOutcome.Local(local.value)
            is StoreResult.Error -> AuthOutcome.Failed(local.message)
        }
    }

    suspend fun bootstrap(): TerminalBootstrapResponse {
        ensureReady()
        val response = api.bootstrap(
            terminalId = resolveTerminalId(),
            localRevision = outbox.localRevision(),
            lastSuccessfulSyncEpochMillis = outbox.lastSuccessfulSyncEpochMillis(),
        )
        outbox.markSynced(response.serverRevision, response.issuedAtEpochMillis)
        response.snapshot?.let { applySnapshot(it) }
        return response
    }

    suspend fun pushPending(submittedByUserId: String): TerminalSyncPushResponse {
        ensureReady()
        val pending = outbox.pending()
        val auditEvents = adminStore.eventOutbox()
        val response = api.push(
            terminalId = resolveTerminalId(),
            changes = pending,
            auditEvents = auditEvents,
        )
        outbox.removeAccepted(response.acceptedOperationIds)
        if (response.conflicts.isEmpty()) {
            outbox.markSynced(outbox.localRevision(), System.currentTimeMillis())
        }
        if (auditEvents.isNotEmpty()) {
            adminStore.clearEventOutbox(auditEvents.map { it.id })
        }
        return response
    }

    suspend fun readFromServer(): TerminalSyncAckResponse {
        ensureReady()
        return api.read(resolveTerminalId())
    }

    suspend fun downloadFromServer(): TerminalSyncAckResponse {
        ensureReady()
        val response = api.download(resolveTerminalId())
        val snapshot = response.snapshot
            ?: throw TerminalApiException(0, "Download succeeded but server returned no snapshot.")
        applySnapshot(snapshot)
        response.serverRevision?.let { rev ->
            outbox.markSynced(rev, response.issuedAtEpochMillis ?: System.currentTimeMillis())
        }
        return response
    }

    fun cachedSnapshot(): TerminalDownloadSnapshot? = serverCache.load()

    private fun applySnapshot(snapshot: TerminalDownloadSnapshot) {
        serverCache.save(snapshot)
        adminStore.applyServerSnapshot(snapshot)
    }

    fun enqueueLocalChange(
        entityType: com.ekms.shared.domain.RecordType,
        entityId: String,
        submittedByUserId: String,
        payloadJson: String,
    ) {
        outbox.enqueue(entityType, entityId, submittedByUserId, payloadJson)
    }

    private fun ensureReady() {
        api.syncBaseUrlFromSettings(adminStore.snapshot().cabinetSettings.serverAddress)
        if (!api.isConfigured) {
            throw TerminalApiException(0, "Set the server address in Admin Menu before syncing.")
        }
        if (!api.isAuthenticated) {
            throw TerminalApiException(0, "Sign in with a server account before syncing.")
        }
    }

    private fun mapIdentifier(identifier: String): String {
        val trimmed = identifier.trim()
        // Do not rewrite "Super Admin" to a seed email — production uses
        // admin@kms-cvt.com (or SUPER_ADMIN_EMAIL). Local bootstrap still
        // accepts "Super Admin" / admin1234 when server login fails.
        return trimmed
    }

    private fun LoginResponse.toTerminalSession(): TerminalSession {
        val role = when (profile.role) {
            UserRole.SUPER_ADMIN -> TerminalUserRole.SUPER_ADMIN
            UserRole.TECHNICIAN -> TerminalUserRole.TECHNICIAN
            UserRole.VENDOR -> TerminalUserRole.VENDOR
        }
        return TerminalSession(
            userId = profile.id,
            displayName = profile.displayName,
            username = profile.email,
            role = role,
            requiresPasswordChange = false,
            serverAuthenticated = true,
        )
    }
}

sealed interface AuthOutcome {
    data class Server(val session: TerminalSession) : AuthOutcome
    data class Local(val session: TerminalSession, val serverWarning: String? = null) : AuthOutcome
    data class Failed(val message: String) : AuthOutcome
}
