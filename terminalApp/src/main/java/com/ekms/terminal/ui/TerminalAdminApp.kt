package com.ekms.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Context
import android.hardware.camera2.CameraManager
import android.provider.Settings
import android.util.Log
import com.ekms.shared.api.CreateAdminUserRequest
import com.ekms.shared.api.CreateKeyCheckoutRequest
import com.ekms.shared.api.FobEnrollmentCompleteRequest
import com.ekms.shared.api.KeyCheckoutStatus
import com.ekms.shared.api.KeySlotUpsertRequest
import com.ekms.shared.api.KeyUpsertRequest
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.UpdateKeyCheckoutRequest
import com.ekms.shared.api.UserDto
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.AuditEventType
import com.ekms.shared.domain.CardUidMatch
import com.ekms.shared.domain.CardUidResolver
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.KeySlotDemoData
import com.ekms.shared.domain.LifecycleMetadata
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.Terminal
import com.ekms.shared.domain.UserRole
import com.ekms.shared.protocol.KeyCabinetLink.Companion.MAX_KEY_NODE_ADDRESS
import com.ekms.terminal.data.AuthOutcome
import com.ekms.terminal.data.CheckoutDeadlineChoice
import com.ekms.terminal.data.StoreResult
import com.ekms.terminal.data.TerminalAccessGrant
import com.ekms.terminal.data.TerminalAdminSnapshot
import com.ekms.terminal.data.TerminalAdminStore
import com.ekms.terminal.data.TerminalApiClient
import com.ekms.terminal.data.TerminalApiException
import com.ekms.terminal.data.TerminalCheckoutRecord
import com.ekms.terminal.data.TerminalCheckoutStore
import com.ekms.terminal.data.KeySlotAssignmentTracker
import com.ekms.terminal.data.PhysicalAttachmentTracker
import com.ekms.terminal.data.TerminalKey
import com.ekms.terminal.data.TerminalSession
import com.ekms.terminal.data.TerminalServerCache
import com.ekms.terminal.data.TerminalSyncCoordinator
import com.ekms.terminal.data.TerminalSyncOutbox
import com.ekms.terminal.data.TerminalThemeMode
import com.ekms.terminal.data.TerminalThemePreferences
import com.ekms.terminal.data.TerminalUser
import com.ekms.terminal.data.TerminalUserRole
import com.ekms.terminal.hardware.AudioFeedbackController
import com.ekms.terminal.hardware.CabinetHardwareController
import com.ekms.terminal.hardware.CabinetHardwareState
import com.ekms.terminal.hardware.EncryptedUidEnrollmentStore
import com.ekms.terminal.hardware.FingerprintEnrollmentOutcome
import com.ekms.terminal.hardware.FingerprintHardwareController
import com.ekms.terminal.hardware.FingerprintHardwareState
import com.ekms.terminal.hardware.FingerprintTemplateStore
import com.ekms.terminal.hardware.KeyFobScanResult
import com.ekms.terminal.hardware.NetworkStatus
import com.ekms.terminal.hardware.NetworkStatusController
import com.ekms.terminal.hardware.face.FaceCameraController
import com.ekms.terminal.hardware.face.FaceProfileStore
import com.ekms.terminal.hardware.PublicCardReaderController
import com.ekms.terminal.hardware.PublicCardReaderState
import com.ekms.terminal.hardware.TerminalNfcReaderController
import com.ekms.terminal.hardware.TerminalNfcReaderState
import com.ekms.terminal.hardware.UidEnrollmentResult
import com.ekms.terminal.hardware.VoiceLine
import com.ekms.terminal.ui.returnflow.ReturnSession
import com.ekms.terminal.ui.returnflow.ReturnSessionController
import com.ekms.terminal.ui.theme.EkmsTerminalTheme
import com.ekms.terminal.ui.theme.StatusTone
import com.ekms.terminal.ui.theme.readout
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * eKMS Terminal bootstrap and live hardware-control milestone.
 *
 * - Only "Super Admin" is pre-provisioned.
 * - The preset account must change its initial password before administration.
 * - Only a signed-in Super Admin can reach actual cabinet serial controls.
 * - Technician/Vendor accounts can be enrolled here, but cannot yet operate
 *   cabinet hardware until their credentials and access grants are complete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalAdminApp() {
    val applicationContext = LocalContext.current.applicationContext
    val store = remember(applicationContext) { TerminalAdminStore(applicationContext) }
    val apiClient = remember(applicationContext) { TerminalApiClient(applicationContext) }
    // Migrate legacy blank serverAddress → production and keep API client in sync.
    remember(store, apiClient) {
        apiClient.syncBaseUrlFromSettings(store.ensureDefaultServerAddress().serverAddress)
    }
    val syncOutbox = remember(applicationContext) { TerminalSyncOutbox(applicationContext) }
    val serverCache = remember(applicationContext) { TerminalServerCache(applicationContext) }
    val syncCoordinator = remember(apiClient, syncOutbox, store, serverCache) {
        TerminalSyncCoordinator(apiClient, syncOutbox, store, serverCache)
    }
    val scope = rememberCoroutineScope()
    val deviceId = remember(applicationContext) {
        Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "terminal-unknown"
    }

    // Phase 9 design-system rework: device-local dark/light preference, same local-only
    // footing as serverAddress/activationCode (never backend-synced). SYSTEM is the
    // first-launch default; the corner toggle on TerminalLoginScreen sets an explicit
    // LIGHT/DARK override from then on.
    val themePreferences = remember(applicationContext) { TerminalThemePreferences(applicationContext) }
    var themeMode by remember { mutableStateOf(themePreferences.mode) }
    val systemInDarkTheme = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        TerminalThemeMode.SYSTEM -> systemInDarkTheme
        TerminalThemeMode.LIGHT -> false
        TerminalThemeMode.DARK -> true
    }
    val onToggleTheme: () -> Unit = {
        val next = if (isDarkTheme) TerminalThemeMode.LIGHT else TerminalThemeMode.DARK
        themeMode = next
        themePreferences.mode = next
    }

    // Fresh/unpaired terminal: the pairing-code screen replaces standby/login/Admin Menu
    // entirely until a Super Admin-issued 6-digit code has been redeemed — gated on cabinetId
    // alone (not apiClient.isAuthenticated) so a later personnel sign-out, which clears the
    // stored access/refresh token via signOut() below, never re-triggers this screen; "reset"
    // only ever means clearing Key Cabinet ID, which nothing here does implicitly.
    var isPaired by remember {
        mutableStateOf(store.snapshot().cabinetSettings.cabinetId.isNotBlank())
    }

    if (!isPaired) {
        var pairingServerAddress by remember { mutableStateOf(apiClient.baseUrl) }
        var pairingSubmitting by remember { mutableStateOf(false) }
        var pairingError by remember { mutableStateOf<String?>(null) }

        EkmsTerminalTheme(darkTheme = isDarkTheme) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("eKMS Terminal · Pairing") }) },
            ) { padding ->
                TerminalPairingScreen(
                    padding = padding,
                    serverAddress = pairingServerAddress,
                    onServerAddressChange = { pairingServerAddress = it },
                    isSubmitting = pairingSubmitting,
                    errorMessage = pairingError,
                    onSubmit = { code ->
                        apiClient.baseUrl = pairingServerAddress
                        scope.launch {
                            pairingSubmitting = true
                            pairingError = null
                            try {
                                val response = apiClient.pairWithCode(code)
                                val current = store.snapshot().cabinetSettings
                                store.updateCabinetSettings(
                                    current.copy(
                                        cabinetId = response.terminal.id,
                                        cabinetName = response.terminal.name.ifBlank { current.cabinetName },
                                        siteName = response.terminal.siteName?.takeIf { it.isNotBlank() }
                                            ?: current.siteName,
                                    ),
                                )
                                // Reuse the existing bootstrap pipeline (Admin Menu's own
                                // Bootstrap button calls the same syncCoordinator.bootstrap())
                                // rather than a parallel sync path. Best-effort: a failure here
                                // does not undo the pairing that already succeeded above — the
                                // Admin Menu Bootstrap button remains available to retry.
                                runCatching { syncCoordinator.bootstrap() }
                                isPaired = true
                            } catch (error: TerminalApiException) {
                                pairingError = error.message
                            } catch (error: Exception) {
                                pairingError = error.message ?: "Unable to reach the server."
                            } finally {
                                pairingSubmitting = false
                            }
                        }
                    },
                )
            }
        }
        return
    }

    var route by remember { mutableStateOf(SuperAdminRoute.LOGIN) }
    // Which personnel record PERSONNEL_DETAIL (and, scoped from there, CARD_ENROLLMENT /
    // FINGERPRINT_ENROLLMENT / FACE_ENROLLMENT) apply to. Null when CARD_ENROLLMENT is instead
    // entered from KeyAttachmentScreen's key-card sub-entry, which locks that screen to the Key
    // category rather than a personnel record.
    var selectedPersonnelId by remember { mutableStateOf<String?>(null) }
    // Phase 3 login rework: which of the 5 method screens to show under SuperAdminRoute.LOGIN.
    // null means "show the method-chooser (TerminalLoginScreen) itself."
    var loginMethod by remember { mutableStateOf<LoginMethod?>(null) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }

    // Single source of truth for post-login routing, reused across every login method (password,
    // personnel-card NFC, fingerprint, face) instead of repeating this when-block at each call
    // site — Phase 3 explicitly requires Super Admin and Regional Admin to land on the same
    // destination, and quadruplicating this decision is exactly how a future role gets missed
    // in one copy but not the others (see TerminalSession.isAdminTier's doc for why this isn't
    // just `isSuperAdmin` anymore). Declared this early (rather than near applyAuthSession,
    // where it's also used) so every closure further down in this function — including the
    // public-card-reader callback, which is defined before applyAuthSession — can reference it;
    // Kotlin resolves local function references by source order, not by when a closure actually
    // runs, so a later declaration isn't visible to an earlier-written deferred callback.
    fun postLoginRoute(authenticated: TerminalSession): SuperAdminRoute = when {
        authenticated.requiresPasswordChange -> SuperAdminRoute.CHANGE_PASSWORD
        // Admin-tier landing point as of this pass — was SuperAdminRoute.DASHBOARD directly.
        // Technician/Vendor's `else` branch below is untouched.
        authenticated.isAdminTier -> SuperAdminRoute.LANDING_CHOICE
        else -> SuperAdminRoute.KEY_MENU
    }
    var snapshot by remember { mutableStateOf(store.snapshot()) }
    var hardwareState by remember { mutableStateOf(CabinetHardwareState()) }
    var syncBusy by remember { mutableStateOf(false) }
    var pendingOutboxCount by remember { mutableStateOf(syncOutbox.pending().size) }
    val hardwareController = remember {
        CabinetHardwareController { nextState -> hardwareState = nextState }
    }
    // Personnel cards and key cards share the same physical medium and UID
    // space (protocol doc section 9) — there is no hardware distinction
    // between them. Keeping their enrollments in two separate encrypted
    // stores means a lookup against one can never accidentally match a
    // record from the other; see CardUidResolver for how a scanned UID is
    // actually resolved against both.
    val personnelCardStore = remember(applicationContext) {
        EncryptedUidEnrollmentStore(applicationContext, "personnel")
    }
    val keyCardStore = remember(applicationContext) {
        EncryptedUidEnrollmentStore(applicationContext, "key")
    }
    // Key Attachment (Part 4/Part 3 background auto-scan): a THIRD, separate instance of the
    // same reusable store — keyed by ManagedKey.id (the server/web-registered key, not the old
    // terminal-local TerminalKey [keyCardStore] above). Deliberately keyed by key id rather than
    // by KeySlot id: when a node is reassigned to a different key, the new key's id has never
    // been enrolled before, so enrollmentFor(newKeyId) == null naturally triggers a re-scan with
    // no extra "did managedKeyId change" bookkeeping needed.
    val managedKeyFobStore = remember(applicationContext) {
        EncryptedUidEnrollmentStore(applicationContext, "managed_key_fob")
    }
    val keySlotAssignmentTracker = remember(applicationContext) { KeySlotAssignmentTracker(applicationContext) }
    // Key Attachment (this pass): the second, previously-conflated fact — see
    // PhysicalAttachmentTracker's own doc comment for why this can't just be
    // managedKeyFobStore.enrollmentFor(id) != null anymore.
    val physicalAttachmentTracker = remember(applicationContext) { PhysicalAttachmentTracker(applicationContext) }
    val fingerprintTemplateStore = remember(applicationContext) { FingerprintTemplateStore(applicationContext) }
    var fingerprintHardwareState by remember { mutableStateOf(FingerprintHardwareState()) }
    val fingerprintHardwareController = remember {
        FingerprintHardwareController { nextState -> fingerprintHardwareState = nextState }
    }
    val faceProfileStore = remember(applicationContext) { FaceProfileStore(applicationContext) }
    // Return Flow rework — minimal local "who has which key" stand-in for Phase 5's fuller
    // overdue/emergency/extension + backend-synced version; see TerminalCheckoutStore's doc.
    val checkoutStore = remember(applicationContext) { TerminalCheckoutStore(applicationContext) }
    var capturedFob by remember { mutableStateOf<CapturedFob?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingPhysicalAction by remember { mutableStateOf<PendingPhysicalAction?>(null) }

    // Retrieval uses the last downloaded server snapshot when present; demo
    // fixtures remain only until the first successful Bootstrap/Download.
    val initialServerSnapshot = remember(serverCache) { serverCache.load() }
    var retrievalTerminal by remember {
        mutableStateOf(
            initialServerSnapshot?.terminal?.toManagedTerminalOption() ?: KeySlotDemoData.terminals.first(),
        )
    }
    var retrievalKeys by remember {
        mutableStateOf(initialServerSnapshot?.keys ?: KeySlotDemoData.keys())
    }
    var retrievalSlots by remember {
        mutableStateOf(
            initialServerSnapshot?.keySlots
                ?: KeySlotDemoData.slots().filter { it.terminalId == KeySlotDemoData.terminals.first().id },
        )
    }
    // Key Menu (Technician/Vendor authorization) reads this as-is via AccessGrant.keyIds — no
    // parallel authorization model, same source the Super Admin portal/backend already treats
    // as canonical (Super Admin's own access stays implicit and is never represented here).
    var retrievalAccessGrants by remember {
        mutableStateOf(initialServerSnapshot?.accessGrants ?: KeySlotDemoData.accessGrants())
    }
    var takenKeyIds by remember { mutableStateOf(emptySet<String>()) }
    /** Only B passkey session: approved key ids bypass AccessGrant filtering on KEY_MENU. */
    var passkeySessionKeyIds by remember { mutableStateOf<Set<String>?>(null) }
    var takeFlow by remember { mutableStateOf<TakeFlow?>(null) }
    var multiKeyQueue by remember { mutableStateOf<MultiKeyTakeQueue?>(null) }
    var multiKeyQueuePending by remember { mutableStateOf(false) }
    // Phase 5, mandatory-manual-return-time rework: every take session (single-key or multi-key,
    // except Only B passkey) now unconditionally requires a return-time decision (the analog clock
    // or Emergency) before its hardware sequence begins — no more office-hours fetch/Auto fast
    // path, so there is no more "resolving" wait state, only the decision itself.
    // pendingCheckoutDecision is cross-cutting the same way ReturnSessionController's own state
    // is (see ui/returnflow/ReturnSessionController.kt) — rendered ahead of `route` in the outer
    // `when` below, regardless of which route (KEY_RETRIEVAL or KEY_MENU) triggered it.
    var pendingCheckoutDecision by remember { mutableStateOf<PendingCheckoutDecision?>(null) }
    // Default is the same post-login landing point postLoginRoute() sends any other admin-tier
    // session to — the forced first-login password change is a required detour before reaching
    // it, not a separate landing decision. Explicitly overridden to ADMIN_MENU below for the
    // voluntary "change my password" entry point reached from within Admin Menu, which is
    // unrelated to post-login routing and stays untouched.
    var passwordChangeReturnRoute by remember { mutableStateOf(SuperAdminRoute.LANDING_CHOICE) }
    var serverPersonnel by remember { mutableStateOf(store.cachedPersonnel()) }
    var assignedUnitId by remember { mutableStateOf<String?>(null) }
    val serverLinked = session?.serverAuthenticated == true && apiClient.isAuthenticated
    val personnelForScreens = if (serverLinked && serverPersonnel.isNotEmpty()) {
        serverPersonnel
    } else {
        snapshot.users
    }

    // Key Menu: standing grants OR Only B passkey-approved key ids for this session.
    val authorizedKeysForCurrentUser = remember(
        retrievalAccessGrants,
        retrievalKeys,
        session?.userId,
        passkeySessionKeyIds,
    ) {
        val passkeyIds = passkeySessionKeyIds
        if (passkeyIds != null) {
            retrievalKeys.filter { key -> key.id in passkeyIds }
        } else {
            val userId = session?.userId
            if (userId == null) {
                emptyList()
            } else {
                val nowMillis = System.currentTimeMillis()
                val authorizedKeyIds = retrievalAccessGrants
                    .asSequence()
                    .filter { grant -> grant.userId == userId }
                    .filter { grant ->
                        val validFrom = grant.validFromEpochMillis
                        val validUntil = grant.validUntilEpochMillis
                        (validFrom == null || nowMillis >= validFrom) &&
                            (validUntil == null || nowMillis <= validUntil)
                    }
                    .flatMap { grant -> grant.keyIds.asSequence() }
                    .toSet()
                retrievalKeys.filter { key -> key.id in authorizedKeyIds }
            }
        }
    }

    // Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1):
    // the availability check below (no slot -> not selectable) is the same
    // one the grid already enforces before ever calling this; entering
    // takeFlow hands off to a dedicated full-screen takeover, same pattern
    // as Section 3's return flow, so the grid is not shown again until it
    // completes, fails, or is abandoned.
    //
    // Mandatory-manual-return-time rework: unconditionally requires the return-time decision
    // (analog clock or Emergency) — no office-hours fetch, no Auto fast path, so this sets
    // pendingCheckoutDecision directly and synchronously, no scope.launch needed.
    fun takeKey(key: ManagedKey) {
        val slot = retrievalSlots.firstOrNull { it.managedKeyId == key.id }
        if (slot == null) {
            notice = "${key.displayName} is not assigned to a cabinet slot."
            return
        }
        pendingCheckoutDecision = PendingCheckoutDecision(
            resume = { choice -> takeFlow = TakeFlow(key, slot, choice) },
        )
    }

    // Key Menu multi-key sequential Take Flow: confirming a selection first lights every
    // selected node's red indicator individually (never a batch command — see
    // CabinetHardwareController.beginMultiKeyRedLightSequence), then starts the queue at its
    // first node once every light is confirmed lit. multiKeyQueuePending covers exactly that
    // in-between window so the screen can show "preparing" rather than nothing.
    // Shared by beginMultiKeyTake (Key Menu's normal multi-select path) and beginPasskeyKeyTake
    // (migration 009 follow-up: a passkey-authenticated take) — the actual "light every selected
    // node, then start the queue" hardware sequence is identical either way; only how `deadline`
    // gets decided differs between the two callers.
    fun startMultiKeyQueue(pairs: List<Pair<ManagedKey, KeySlot>>, deadline: CheckoutDeadlineChoice) {
        multiKeyQueuePending = true
        hardwareController.beginMultiKeyRedLightSequence(
            nodeAddresses = pairs.map { (_, slot) -> slot.nodeAddress },
            onReady = {
                multiKeyQueuePending = false
                multiKeyQueue = MultiKeyTakeQueue(pairs, checkoutDeadline = deadline)
            },
            onFailure = { message ->
                multiKeyQueuePending = false
                notice = message
            },
        )
    }

    fun beginMultiKeyTake(selectedKeys: List<ManagedKey>) {
        val pairs = selectedKeys.mapNotNull { key ->
            retrievalSlots.firstOrNull { it.managedKeyId == key.id }?.let { slot -> key to slot }
        }
        if (pairs.isEmpty()) {
            notice = "None of the selected keys are assigned to a cabinet slot."
            return
        }

        pendingCheckoutDecision = PendingCheckoutDecision(
            resume = { choice -> startMultiKeyQueue(pairs, choice) },
        )
    }

    // TerminalKey (terminal-local, embeds box/node address) has no shared-
    // model equivalent yet — see docs/Backend_Integration_Handover.md's
    // "two incompatible key schemas" gap (ManagedKey+KeySlot, shared/demo,
    // vs TerminalKey, terminal-local). Synthesizes the pair the existing
    // return-flow plumbing expects from the one real card-swipe case that
    // needs it, rather than reworking that plumbing's type wholesale.
    // Declared above passkey/card-login callers — Kotlin local functions are not hoisted.
    fun managedKeyAndSlotFor(terminalKey: TerminalKey): Pair<ManagedKey, KeySlot> {
        val nowMillis = System.currentTimeMillis()
        val lifecycle = LifecycleMetadata(createdAtEpochMillis = nowMillis, updatedAtEpochMillis = nowMillis)
        val managedKey = ManagedKey(
            id = terminalKey.id,
            siteId = retrievalTerminal.siteId,
            displayName = terminalKey.displayName,
            lifecycle = lifecycle,
        )
        val slot = KeySlot(
            id = "slot_" + terminalKey.id,
            terminalId = retrievalTerminal.id,
            nodeAddress = terminalKey.nodeAddress,
            managedKeyId = terminalKey.id,
            lifecycle = lifecycle,
        )
        return managedKey to slot
    }

    fun refreshSnapshot() {
        snapshot = store.snapshot()
        pendingOutboxCount = syncOutbox.pending().size
        val serverSnapshot = syncCoordinator.cachedSnapshot()
        if (serverSnapshot != null) {
            retrievalTerminal = serverSnapshot.terminal.toManagedTerminalOption().copy(
                configuredSlotCount = snapshot.cabinetSettings.configuredKeyNodeCount
                    .takeIf { it > 0 }
                    ?: serverSnapshot.terminal.configuredSlotCount,
            )
            retrievalKeys = serverSnapshot.keys
            retrievalSlots = serverSnapshot.keySlots
            retrievalAccessGrants = serverSnapshot.accessGrants
        }
    }

    /**
     * Blocks Technician/Vendor standing login at cabinets outside their unit assignments.
     * Returns false when denied (sets [notice]); true when the session may proceed.
     */
    fun acceptStandingLogin(candidate: TerminalSession): Boolean {
        val siteId = retrievalTerminal.siteId
        if (candidate.mayLoginAtCabinet(siteId)) return true
        notice = "You are not assigned to this key cabinet's unit. Sign in only at your assigned location, or use an approved passkey for exception access."
        return false
    }

    /**
     * Migration 009 follow-up: entry point for a passkey-authenticated take, reached from
     * [runPasskeyLogin] on a successful `POST /v1/terminal/passkey-login`. Deliberately does
     * NOT go through `pendingCheckoutDecision` (`TerminalCloseToDeadlineScreen`) at all — the due time was
     * already fixed at request-approval time (`passkeyExpiresAtEpochMillis`), so there is no
     * decision left to make here, only [CheckoutDeadlineChoice.passkeyRequest] to construct from
     * it. Reuses [startMultiKeyQueue] (same hardware sequence Key Menu's own multi-select path
     * uses) rather than a parallel queue implementation — this only differs in how the pairs and
     * deadline are sourced (an approved [TerminalPasskeyLoginResponse], not an operator's
     * on-screen selection).
     */
    fun beginPasskeyKeyTake(approvedKeyIds: Set<String>, expiresAtEpochMillis: Long) {
        val localKeys = store.snapshot().keys
        val pairs = approvedKeyIds.mapNotNull { keyId ->
            val key = retrievalKeys.firstOrNull { it.id == keyId }
            val slot = retrievalSlots.firstOrNull { it.managedKeyId == keyId }
            if (key != null && slot != null) {
                return@mapNotNull key to slot
            }
            // Fallback: Key Attachment / local TerminalKey may know the node even when the
            // synced KeySlot row is missing managedKeyId (common after partial sync).
            val terminalKey = localKeys.firstOrNull { it.id == keyId && it.nodeAddress > 0 }
            if (terminalKey != null) {
                return@mapNotNull managedKeyAndSlotFor(terminalKey)
            }
            null
        }
        if (pairs.isEmpty()) {
            val knownNames = approvedKeyIds.mapNotNull { id ->
                retrievalKeys.firstOrNull { it.id == id }?.displayName
                    ?: localKeys.firstOrNull { it.id == id }?.displayName
            }
            notice = if (knownNames.isEmpty()) {
                "Approved keys are not on this cabinet yet. Download/sync the cabinet, attach keys to slots, then try the PIN again."
            } else {
                "Approved key(s) (${knownNames.joinToString()}) are not assigned to a slot on this cabinet. Attach them in Key Attachment, then try again."
            }
            return
        }
        startMultiKeyQueue(pairs, CheckoutDeadlineChoice.passkeyRequest(expiresAtEpochMillis))
    }

    /**
     * Migration 009 follow-up: calls the real backend passkey-login route. On success, builds a
     * [TerminalSession] from the response profile fields — **not** via
     * `store.authenticateByUserId`. Only B exception techs are outside standing site assignments,
     * so this cabinet's bootstrap snapshot never includes them; a local lookup would fail with
     * "personnel record no longer exists" even though the PIN is valid. Checkout attribution and
     * sign-out still use the same [TerminalSession] shape; we then route straight into
     * [beginPasskeyKeyTake] for the approved key(s) instead of [postLoginRoute]'s normal
     * role-based landing screen.
     */
    fun runPasskeyLogin(code: String) {
        scope.launch {
            syncBusy = true
            try {
                val response = apiClient.passkeyLogin(code, retrievalTerminal.id)
                val role = runCatching { TerminalUserRole.valueOf(response.requesterRole) }
                    .getOrDefault(TerminalUserRole.TECHNICIAN)
                // Refresh keys/slots before take so a recent portal attachment is visible.
                runCatching { refreshSnapshot() }
                passkeySessionKeyIds = response.keyIds
                session = TerminalSession(
                    userId = response.requesterUserId,
                    displayName = response.requesterDisplayName.ifBlank { "Passkey visitor" },
                    username = response.requesterEmail.ifBlank { response.requesterUserId },
                    role = role,
                    requiresPasswordChange = false,
                    assignedSiteIds = emptySet(),
                )
                notice = null
                loginMethod = null
                route = SuperAdminRoute.KEY_MENU
                beginPasskeyKeyTake(response.keyIds, response.expiresAtEpochMillis)
            } catch (error: TerminalApiException) {
                passkeySessionKeyIds = null
                notice = error.message
            } catch (error: Exception) {
                passkeySessionKeyIds = null
                notice = error.message ?: "Passkey sign-in failed."
            } finally {
                syncBusy = false
            }
        }
    }

    fun handleTakeFlowOutcome(outcome: TakeFlowOutcome, deadline: CheckoutDeadlineChoice) {
        val actorUserId = session?.userId
        when (outcome) {
            is TakeFlowOutcome.Success -> {
                val takenAtEpochMillis = System.currentTimeMillis()
                // Return Flow reads this back via checkoutStore.find(keyId) to show who has
                // the key and since when, and to know what to close out on return — the
                // minimal local stand-in described in TerminalCheckoutStore's doc.
                checkoutStore.open(
                    TerminalCheckoutRecord(
                        keyId = outcome.key.id,
                        userId = actorUserId,
                        terminalId = retrievalTerminal.id,
                        takenAtEpochMillis = takenAtEpochMillis,
                    ),
                )
                store.logEvent(AuditEventType.KEY_TAKEN, actorUserId, RecordType.KEY, outcome.key.id)

                // Phase 5: real backend key_checkouts row, fired non-blocking — the physical take
                // already succeeded above and must never be undone or delayed by this call. A
                // failure (offline, demo/non-backend key or user id, server error) is logged
                // locally only, never surfaced to the operator.
                if (actorUserId == null) {
                    store.logEvent(
                        AuditEventType.KEY_CHECKOUT_SYNC_FAILED,
                        null,
                        RecordType.KEY,
                        outcome.key.id,
                        "No signed-in user id to attribute the checkout to.",
                    )
                } else {
                    scope.launch {
                        try {
                            val created = apiClient.createKeyCheckout(
                                CreateKeyCheckoutRequest(
                                    keyId = outcome.key.id,
                                    userId = actorUserId,
                                    terminalId = retrievalTerminal.id,
                                    takenAtEpochMillis = takenAtEpochMillis,
                                    dueAtEpochMillis = deadline.dueAtEpochMillis,
                                    isEmergency = deadline.isEmergency,
                                    emergencyWindowEndsAtEpochMillis = deadline.emergencyWindowEndsAtEpochMillis,
                                    dueDateSource = deadline.source,
                                ),
                            )
                            checkoutStore.attachBackendInfo(outcome.key.id, created)
                        } catch (error: Exception) {
                            store.logEvent(
                                AuditEventType.KEY_CHECKOUT_SYNC_FAILED,
                                actorUserId,
                                RecordType.KEY,
                                outcome.key.id,
                                error.message ?: "Checkout create sync failed.",
                            )
                        }
                    }
                }
            }

            is TakeFlowOutcome.Failed ->
                store.logEvent(AuditEventType.KEY_TAKE_FAILED, actorUserId, RecordType.KEY, outcome.key.id, outcome.message)

            is TakeFlowOutcome.Abandoned ->
                store.logEvent(AuditEventType.KEY_TAKE_ABANDONED, actorUserId, RecordType.KEY, outcome.key.id)

            is TakeFlowOutcome.DoorLeftOpen ->
                store.logEvent(AuditEventType.KEY_TAKE_DOOR_LEFT_OPEN, actorUserId, RecordType.KEY, outcome.key.id)
        }
    }

    // Return Flow rewrite, Tier 2 (audio consolidation): ONE AudioFeedbackController instance now
    // exists for the whole app — this used to be three (this composable's own `returnSessionAudio`/
    // `multiKeyTakeAudio`, plus each of TerminalKeyTakeScreen/TerminalKeyReturnScreen `remember`-ing
    // a fourth per-screen instance) — see AudioFeedbackController's class doc for why that was the
    // actual voice-line-overlap bug (four independent `voiceLinePlayer` fields, not the old
    // playVoiceLine/playVoiceLineAwaitingCompletion duality). Threaded down as a parameter to both
    // Take/Return screens; used directly here for the session-level Door-Close Warning Time cycle
    // and the multi-key queue's "please take your next key" one-shot, both of which fire from this
    // composable's own body (not a screen's), same reasoning the old dedicated instances had for
    // *why* a screen's own instance couldn't be reused for these — just now satisfied by a shared
    // instance instead of two more separate ones. Declared before returnSessionController
    // (source-order matters for local vals/funs referencing each other — see Phase 3's
    // postLoginRoute note elsewhere in this file for the same Kotlin gotcha).
    val audio = remember(applicationContext) { AudioFeedbackController(applicationContext) }
    DisposableEffect(audio) {
        onDispose { audio.release() }
    }

    // Return Flow rewrite, Tier 3: everything previously spread across this composable
    // (returnFlow state, startKeyCardReturn, beginReturnNodeCycle wrapper, onNodeCycleComplete,
    // handleReturnFlowOutcome, resolveNodeActiveState, resolveKeyFobUid, checkoutSummaryFor, the
    // returnSession* vars/LaunchedEffects) now lives in ui/returnflow/ReturnSessionController.kt.
    // returnToLoginAfterSessionComplete moved up here from its old position further down, since
    // the controller's own constructor needs it as onSessionEnded — same Kotlin
    // local-declaration-ordering rule as Phase 3's postLoginRoute note elsewhere in this file (a
    // local fun must be declared before any closure that captures it, source order not execution
    // order). signOut() was NOT moved — it's declared further down, after returnSessionController
    // exists, since it needs to call returnSessionController.forceClose(...).
    fun returnToLoginAfterSessionComplete() {
        apiClient.clearSession()
        session = null
        passkeySessionKeyIds = null
        multiKeyQueue = null
        multiKeyQueuePending = false
        capturedFob = null
        notice = null
        loginMethod = null
        route = SuperAdminRoute.LOGIN
    }

    val returnSessionController = remember {
        ReturnSessionController(
            hardwareController = hardwareController,
            store = store,
            checkoutStore = checkoutStore,
            apiClient = apiClient,
            scope = scope,
            personnelCardStore = personnelCardStore,
            keyCardStore = keyCardStore,
            cabinetSettings = { snapshot.cabinetSettings },
            retrievalKeys = { retrievalKeys },
            retrievalSlots = { retrievalSlots },
            takenKeyIds = { takenKeyIds },
            actorUserId = { session?.userId },
            personnelDisplayName = { userId -> personnelForScreens.firstOrNull { it.id == userId }?.displayName },
            onKeyReturned = { keyId -> takenKeyIds = takenKeyIds - keyId },
            onNotice = { message -> notice = message },
            onSessionEnded = ::returnToLoginAfterSessionComplete,
        )
    }

    // Cyclic take/return/close-door audio pattern (session-level Phase 2 half — the per-node
    // Phase 1 half lives in TerminalKeyReturnScreen). Reads ReturnSessionController's own
    // doorCloseWarningActive state now instead of a local var this composable used to own and
    // mutate directly — the controller owns the actual Door-Close Warning Time timer now; this
    // effect's only job is reacting to it by playing the right voice line.
    val doorCloseWarningActive = returnSessionController.doorCloseWarningActive
    val currentDoorCloseWarningActive by rememberUpdatedState(doorCloseWarningActive)
    LaunchedEffect(doorCloseWarningActive) {
        if (doorCloseWarningActive) {
            audio.playCyclicUntil(
                voiceLine = VoiceLine.PLEASE_CLOSE_THE_DOOR,
                until = { !currentDoorCloseWarningActive },
            )
        }
    }

    // "More keys to return?" 5s hold: the controller flips this to a fresh non-null timestamp
    // the instant a hold starts (null on cancel/session-end) — this effect's only job is playing
    // the one-shot voice line when a hold begins; the actual 5s wait and the "elapsed -> start
    // the close-door cycle" decision are the controller's own job now (see
    // ReturnSessionController.handleNodeCycleOutcome).
    val moreKeyReturnHoldUntilEpochMillis = returnSessionController.moreKeyReturnHoldUntilEpochMillis
    LaunchedEffect(moreKeyReturnHoldUntilEpochMillis) {
        if (moreKeyReturnHoldUntilEpochMillis != null) {
            audio.playVoiceLine(VoiceLine.MORE_KEY_RETURN)
        }
    }

    // Section 9's public card-swipe reader — a wholly separate device/protocol
    // from the node-level 0x15/0x17 card reads (section 9.1/9.8/10.4: "must
    // not be mixed"). A scanned UID is meaningless on its own — personnel
    // cards and key cards share the same physical medium and UID space, so
    // which action a scan means can only be decided by looking it up against
    // both encrypted stores, never assumed from which screen is showing.
    var publicCardReaderState by remember {
        mutableStateOf<PublicCardReaderState>(PublicCardReaderState.Idle)
    }
    val cardReaderController = remember {
        PublicCardReaderController(
            onStateChanged = { nextState ->
                Log.d(LOG_TAG, "ReturnFlowDiag: publicCardReaderState -> ${nextState.javaClass.simpleName}")
                publicCardReaderState = nextState
            },
            onCardDetected = { rawUid ->
                val matchedUserId = personnelCardStore.recordIdFor(rawUid)
                val matchedKeyId = keyCardStore.recordIdFor(rawUid)
                when (val match = CardUidResolver.resolve(matchedUserId, matchedKeyId)) {
                    is CardUidMatch.User -> when (val result = store.authenticateByUserId(match.userId)) {
                        is StoreResult.Success -> {
                            if (acceptStandingLogin(result.value)) {
                                session = result.value
                                notice = null
                                loginMethod = null
                                route = postLoginRoute(result.value)
                            }
                        }

                        is StoreResult.Error -> notice = result.message
                    }

                    is CardUidMatch.Key -> {
                        // Key Attachment's auto-bind (see onSaveAttachment above) writes into
                        // keyCardStore keyed by ManagedKey.id, the current schema — checked first.
                        // Falls back to the legacy TerminalKey lookup for cards enrolled the old
                        // way via CardEnrollmentScreen's manual "Enroll a key card" sub-entry,
                        // whose ids are still TerminalKey.id. Same two-schema fallback shape
                        // beginPasskeyKeyTake already uses for the same underlying gap.
                        val managedKey = retrievalKeys.firstOrNull { it.id == match.keyId }
                        val managedSlot = managedKey?.let { key -> retrievalSlots.firstOrNull { it.managedKeyId == key.id } }
                        Log.d(
                            LOG_TAG,
                            "ReturnFlowDiag: card scan resolved to Key match; " +
                                "priorReturnState=${returnSessionController.state.javaClass.simpleName}, " +
                                "priorSessionReturnedCount=${returnSessionController.returnedKeyNames.size}, " +
                                "managedSlotFound=${managedSlot != null}",
                        )
                        if (managedKey != null && managedSlot != null) {
                            returnSessionController.onKeyCardScanned(managedKey, managedSlot)
                        } else {
                            val terminalKey = snapshot.keys.firstOrNull { it.id == match.keyId }
                            if (terminalKey != null) {
                                val (matchedKey, matchedSlot) = managedKeyAndSlotFor(terminalKey)
                                returnSessionController.onKeyCardScanned(matchedKey, matchedSlot)
                            } else {
                                notice = "This card's enrolled key no longer exists."
                            }
                        }
                    }

                    is CardUidMatch.Ambiguous ->
                        notice = "This card is enrolled to both personnel and a key. Re-enroll it before use."

                    CardUidMatch.NoMatch -> notice = "Unrecognized card. It is not enrolled to personnel or a key."
                }
            },
        )
    }
    // Return Flow rewrite, Tier 3: the reader stays active at standby (unchanged) and now also
    // through a continuous return session's idle screen, so the next scan is picked up without
    // requiring Done first — but NOT while an attempt is actively in flight (certification
    // pending, hardware mid-sequence), same as before, to avoid a second scan colliding with one
    // already in progress. The `state is Closed`/`state is Waiting` half is exactly
    // returnSessionController.sessionReaderArmed's own deny-list (see that property's doc) — ANDed
    // here with the separate, unrelated "is the login screen showing" condition that arms the same
    // physical reader for personnel-card taps, per that same doc's note about this call site.
    val cardReaderShouldBeActive = (route == SuperAdminRoute.LOGIN && returnSessionController.state is ReturnSession.Closed) ||
        returnSessionController.state is ReturnSession.Waiting

    // Key Return Flow rework, Tier 3: the 20s-from-swipe certification-abandonment race (and its
    // documented duplicate-abandonment fix, attemptId-keyed + re-check-live-state-before-acting)
    // now lives entirely inside ReturnSessionController.scheduleCertificationAbandonment — no
    // LaunchedEffect needed here any more, since the controller isn't a @Composable and owns its
    // own Handler-based timer instead of a coroutine this composable would have to host.

    /**
     * Key Attachment (Part 3) — background auto-scan-and-save. Called after Bootstrap/Download
     * bring down a fresh KeySlot list; never touched by Push/Read, which don't carry a snapshot.
     * Confirmed safe with the door CLOSED by a real-hardware probe (see CLAUDE_TERMINAL.md) — no
     * unlock/eject, this only reads whatever's already resting in each assigned node.
     *
     * A node is a scan candidate if its KeySlot is assigned to a key AND
     * [managedKeyFobStore] has no stored UID for that specific key id yet — which also covers
     * "node reused for a different key" for free, since the new key's id has never been
     * enrolled. [keySlotAssignmentTracker] additionally revokes the OLD key's stale entry when a
     * node's assignment has changed since the last scan, so a not-yet-physically-swapped fob
     * can't collide with the store's own AlreadyAssigned check.
     */
    fun triggerKeyFobAutoScan(keySlots: List<KeySlot>) {
        val candidates = keySlots.mapNotNull { slot ->
            val managedKeyId = slot.managedKeyId ?: return@mapNotNull null
            val lastKnown = keySlotAssignmentTracker.lastKnownManagedKeyId(slot.nodeAddress)
            if (lastKnown != null && lastKnown != managedKeyId) {
                managedKeyFobStore.revoke(lastKnown)
            }
            if (managedKeyFobStore.enrollmentFor(managedKeyId) != null) return@mapNotNull null
            slot
        }
        if (candidates.isEmpty()) return
        val slotByNode = candidates.associateBy { it.nodeAddress }

        hardwareController.autoScanKeyFobs(
            nodeAddresses = candidates.map { it.nodeAddress },
            onNodeResult = { nodeAddress, result ->
                val slot = slotByNode[nodeAddress] ?: return@autoScanKeyFobs
                val managedKeyId = slot.managedKeyId ?: return@autoScanKeyFobs
                if (result !is KeyFobScanResult.CardRead) return@autoScanKeyFobs

                val enrollResult = managedKeyFobStore.enroll(managedKeyId, result.uidHex, System.currentTimeMillis())
                if (enrollResult !is UidEnrollmentResult.Saved) return@autoScanKeyFobs
                keySlotAssignmentTracker.recordManagedKeyId(nodeAddress, managedKeyId)

                scope.launch {
                    try {
                        val terminalId = syncCoordinator.resolveTerminalId()
                        apiClient.completeKeyFobEnrollment(
                            managedKeyId,
                            FobEnrollmentCompleteRequest(
                                enrollmentReference = enrollResult.summary.enrollmentReference,
                                terminalId = terminalId,
                            ),
                        )
                    } catch (error: Throwable) {
                        // Local capture already succeeded and is never undone here — same
                        // non-blocking-sync shape as KEY_CHECKOUT_SYNC_FAILED.
                        store.logEvent(
                            AuditEventType.KEY_FOB_ENROLLMENT_SYNC_FAILED,
                            session?.userId,
                            RecordType.KEY,
                            managedKeyId,
                            error.message ?: "Unknown error",
                        )
                    }
                }
            },
            onSweepComplete = {},
        )
    }

    fun refreshServerPersonnel(quiet: Boolean = false) {
        if (!apiClient.isAuthenticated) return
        scope.launch {
            try {
                val siteId = try {
                    val terminalId = syncCoordinator.resolveTerminalId()
                    apiClient.getTerminal(terminalId).siteId.also { assignedUnitId = it }
                } catch (_: Exception) {
                    assignedUnitId
                }
                val users = apiClient.listUsers(siteId)
                val mapped = users.filterNot { it.role == UserRole.GOD_ADMIN }.map { it.toTerminalUser() }
                store.replaceCachedPersonnel(mapped)
                serverPersonnel = mapped
            } catch (error: Throwable) {
                if (!quiet) {
                    notice = "Could not load personnel from server: ${error.message ?: "Unknown error"}"
                }
            }
        }
    }

    fun reportPersonnelCardEnrollment(userId: String, enrollmentReference: String) {
        // Credentials stay terminal-local (policy A): do not POST complete to the server DB.
        notice = "Card enrolled on this terminal only. NFC/Fingerprint/Face are not uploaded to the server."
    }

    fun reportPersonnelCardRevoke(userId: String) {
        notice = "Card revoked on this terminal only (not synced to the server)."
    }

    fun reportFingerprintEnrollment(userId: String, enrollmentReference: String) {
        notice = "Fingerprint enrolled on this terminal only. Biometrics are not uploaded to the server."
    }

    fun reportFingerprintRevoke(userId: String) {
        notice = "Fingerprint revoked on this terminal only (not synced to the server)."
    }

    fun reportFaceEnrollment(userId: String, enrollmentReference: String) {
        notice = "Face enrolled on this terminal only. Biometrics are not uploaded to the server."
    }

    fun reportFaceRevoke(userId: String) {
        notice = "Face revoked on this terminal only (not synced to the server)."
    }

    // hardwareController.disconnect() is routed through returnSessionController.forceClose(...)
    // (Return Flow rewrite, per explicit instruction) rather than called directly, so the
    // controller's own state resets in lockstep with the hardware layer's guard instead of
    // relying solely on the render-dispatch ordering that happens to make this call site
    // unreachable while a session is genuinely open today (verified true, separately — see
    // CLAUDE_TERMINAL.md — but no longer load-bearing for correctness here).
    fun signOut() {
        returnSessionController.forceClose { hardwareController.disconnect() }
        apiClient.clearSession()
        session = null
        passkeySessionKeyIds = null
        multiKeyQueue = null
        multiKeyQueuePending = false
        capturedFob = null
        notice = null
        loginMethod = null
        route = SuperAdminRoute.LOGIN
    }

    fun applyAuthSession(outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.Server -> {
                if (!acceptStandingLogin(outcome.session)) return
                session = outcome.session
                notice = "Signed in to ${apiClient.baseUrl}."
                refreshServerPersonnel()
                route = postLoginRoute(outcome.session)
            }

            is AuthOutcome.Local -> {
                if (!acceptStandingLogin(outcome.session)) return
                session = outcome.session
                notice = outcome.serverWarning
                route = postLoginRoute(outcome.session)
            }

            is AuthOutcome.Failed -> notice = outcome.message
        }
    }

    fun runServerLogin(username: String, password: String) {
        scope.launch {
            syncBusy = true
            try {
                applyAuthSession(syncCoordinator.authenticate(username, password, deviceId))
            } finally {
                syncBusy = false
            }
        }
    }

    fun runSyncAction(label: String, block: suspend () -> String) {
        scope.launch {
            syncBusy = true
            notice = "$label…"
            try {
                notice = block()
                refreshSnapshot()
            } catch (error: Throwable) {
                notice = "$label failed: ${error.message ?: "Unknown error"}"
            } finally {
                syncBusy = false
            }
        }
    }

    fun enqueueChange(entityType: RecordType, entityId: String, payloadJson: String) {
        val actor = session?.userId ?: "local"
        syncCoordinator.enqueueLocalChange(entityType, entityId, actor, payloadJson)
        pendingOutboxCount = syncOutbox.pending().size
    }

    fun openAdmin(routeToOpen: SuperAdminRoute) {
        val activeSession = session
        if (activeSession?.isAdminTier == true) {
            notice = null
            route = routeToOpen
        } else {
            notice = "Only a signed-in Super Admin or Regional Admin may open this area."
            route = SuperAdminRoute.LOGIN
        }
    }

    fun askForPhysicalConfirmation(
        title: String,
        message: String,
        onConfirm: () -> Unit,
    ) {
        pendingPhysicalAction = PendingPhysicalAction(title, message, onConfirm)
    }

    fun captureFobFromNode(nodeAddress: Int) {
        hardwareController.readPhysicalFob(nodeAddress) { rawUid ->
            capturedFob = CapturedFob(
                boxAddress = hardwareState.boxAddress,
                nodeAddress = nodeAddress,
                rawUid = rawUid,
            )
            notice = "Physical fob captured from Box " + hardwareState.boxAddress +
                    ", Node " + nodeAddress + ". Its UID is hidden and will only be used to save this key."
        }
    }

    DisposableEffect(hardwareController) {
        onDispose { returnSessionController.forceClose { hardwareController.close() } }
    }
    DisposableEffect(fingerprintHardwareController) {
        onDispose { fingerprintHardwareController.close() }
    }

    // Section 10.3-10.4: the reader monitor starts automatically once idle at the login screen
    // or in a continuous return session, and stops automatically otherwise — including when
    // this composable (the whole app) leaves composition, i.e. app exit. See
    // cardReaderShouldBeActive's own doc for the Return Flow rework's addition to this gate.
    LaunchedEffect(cardReaderShouldBeActive) {
        Log.d(
            LOG_TAG,
            "ReturnFlowDiag: cardReaderShouldBeActive=$cardReaderShouldBeActive, route=$route, " +
                "returnState=${returnSessionController.state.javaClass.simpleName}, " +
                "readerArmed=${returnSessionController.sessionReaderArmed}",
        )
        if (cardReaderShouldBeActive) cardReaderController.start() else cardReaderController.stop()
    }
    DisposableEffect(cardReaderController) {
        onDispose { cardReaderController.close() }
    }

    // Network bring-up: runs on every launch/restart, Ethernet preferred over Wi-Fi. Local
    // hardware flows (take/return, local login) never depend on this — only network-bound
    // screens (pairing above, live sync) are affected, and those already degrade to an
    // explicit error/notice rather than blocking, per their own existing try/catch handling.
    var networkStatus by remember { mutableStateOf(NetworkStatus()) }
    val networkStatusController = remember(applicationContext) {
        NetworkStatusController(applicationContext) { nextStatus -> networkStatus = nextStatus }
    }
    DisposableEffect(networkStatusController) {
        networkStatusController.start()
        onDispose { networkStatusController.stop() }
    }

    // Live portal pull: keep keys/slots/grants/cabinet settings current while online.
    // Skips during take/return/Key Attachment so we don't fight hardware flows or the 3s slot poll.
    var liveServerConnected by remember { mutableStateOf(false) }
    var liveSyncInProgress by remember { mutableStateOf(false) }
    val syncBusyLive by rememberUpdatedState(syncBusy)
    val takeFlowLive by rememberUpdatedState(takeFlow)
    val multiKeyQueueLive by rememberUpdatedState(multiKeyQueue)
    val multiKeyQueuePendingLive by rememberUpdatedState(multiKeyQueuePending)
    val pendingCheckoutLive by rememberUpdatedState(pendingCheckoutDecision)
    // Return Flow rewrite, Tier 3: sessionReaderArmed is exactly "Closed or Waiting" (see its own
    // doc) — its negation is exactly the old `returnFlowLive != null && returnFlowLive !is Waiting`
    // check's intent ("a return session is genuinely busy with a node cycle or certification").
    val returnSessionBusyLive by rememberUpdatedState(!returnSessionController.sessionReaderArmed)
    val routeLive by rememberUpdatedState(route)
    val serverLinkedLive by rememberUpdatedState(serverLinked)
    // Diagnostic only (added to diagnose "Server: Reconnecting…" reports, not yet root-caused) —
    // classifies a live-sync failure into a coarse bucket without changing which branch runs:
    // TerminalApiException already carries a real HTTP status (send() throws it on any non-2xx
    // response), so that's a genuine server-side rejection (e.g. 401 = the refresh itself also
    // failed) — distinct from the network-level exception types OkHttp/Ktor throw when a
    // response never came back at all.
    fun classifySyncFailure(error: Throwable?): String = when (error) {
        null -> "no exception captured"
        is TerminalApiException -> "HTTP ${error.status}: ${error.message}"
        is SocketTimeoutException -> "TIMEOUT: ${error.message}"
        is ConnectException -> "CONNECTION_REFUSED: ${error.message}"
        is UnknownHostException -> "DNS_FAILURE: ${error.message}"
        is SSLException -> "TLS_ERROR: ${error.message}"
        else -> "${error::class.simpleName}: ${error.message}"
    }
    LaunchedEffect(networkStatus.hasInternet) {
        while (true) {
            val hardwareBusy = takeFlowLive != null ||
                multiKeyQueueLive != null ||
                multiKeyQueuePendingLive ||
                pendingCheckoutLive != null ||
                returnSessionBusyLive ||
                routeLive == SuperAdminRoute.KEY_ATTACHMENT ||
                syncBusyLive
            val canPull = networkStatus.hasInternet &&
                apiClient.isAuthenticated &&
                snapshot.cabinetSettings.cabinetId.isNotBlank() &&
                !hardwareBusy
            if (canPull) {
                liveSyncInProgress = true
                val firstAttempt = runCatching {
                    syncCoordinator.downloadFromServer()
                    refreshSnapshot()
                    if (serverLinkedLive) refreshServerPersonnel(quiet = true)
                }
                if (firstAttempt.isFailure) {
                    Log.d(LOG_TAG, "ServerSyncDiag: initial sync failed — ${classifySyncFailure(firstAttempt.exceptionOrNull())}")
                    val refreshResult = runCatching { apiClient.refreshAccessToken() }
                    if (refreshResult.isFailure) {
                        Log.d(LOG_TAG, "ServerSyncDiag: token refresh failed — ${classifySyncFailure(refreshResult.exceptionOrNull())}")
                    }
                    val retryResult = runCatching {
                        syncCoordinator.downloadFromServer()
                        refreshSnapshot()
                    }
                    if (retryResult.isFailure) {
                        Log.d(LOG_TAG, "ServerSyncDiag: retry after refresh failed — ${classifySyncFailure(retryResult.exceptionOrNull())}")
                    }
                    liveServerConnected = retryResult.isSuccess
                } else {
                    liveServerConnected = true
                }
                liveSyncInProgress = false
            } else if (!networkStatus.hasInternet) {
                liveServerConnected = false
            }
            delay(TerminalApiClient.LIVE_SYNC_INTERVAL_MILLIS)
        }
    }

    // Presence-only camera check for the startup diagnostic — see StartupDiagnosticsScreen's
    // cameraDiagnostic doc for why this does not instantiate the full FaceCameraController.
    val cameraManager = remember(applicationContext) {
        applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    val cameraAvailable = remember(cameraManager) {
        runCatching { cameraManager.cameraIdList.contains(FaceCameraController.RGB_CAMERA_ID) }.getOrDefault(false)
    }

    // Self-diagnostic: runs automatically on every cold launch, crash-recovery relaunch, and
    // manual restart (rememberSaveable resets on a genuinely new process, not on a mere
    // recomposition/rotation) — shown once, right after the pairing gate, before the app
    // reaches standby/login. Never blocks progression; see TerminalBootSplashScreen's doc.
    // Same guard reused for the initial probe, TerminalBootSplashScreen's Retry action, and its
    // auto-recheck-on-resume — one place decides "is it worth calling connect() again."
    fun probeStartupHardwareIfNeeded() {
        if (!hardwareState.connected && !hardwareState.busy) hardwareController.connect()
        if (!fingerprintHardwareState.connected && !fingerprintHardwareState.busy) {
            fingerprintHardwareController.connect()
        }
    }

    var showStartupDiagnostics by rememberSaveable { mutableStateOf(true) }
    var startupHardwareProbeStarted by remember { mutableStateOf(false) }
    LaunchedEffect(showStartupDiagnostics) {
        if (showStartupDiagnostics && !startupHardwareProbeStarted) {
            startupHardwareProbeStarted = true
            probeStartupHardwareIfNeeded()
        }
    }

    // Boot-time key-node self-test (visual hardware check — see
    // CabinetHardwareController.runBootKeyNodeSelfTest's doc): a second, independent gate on
    // the splash screen alongside the existing Phase 2 self-diagnostic below, not a
    // replacement of it. Started only once the cabinet link is actually connected (not just
    // once the splash is showing) — sending commands to a not-yet-open port would just fail
    // every node — and only once, guarded the same way startupHardwareProbeStarted guards the
    // diagnostic probe above. configuredSlotCount comes from the already-loaded local snapshot
    // (TerminalAdminStore's cached settings), no server round-trip needed.
    var bootNodeSelfTestStarted by remember { mutableStateOf(false) }
    var bootNodeSelfTestComplete by remember { mutableStateOf(false) }
    LaunchedEffect(showStartupDiagnostics, hardwareState.connected) {
        if (showStartupDiagnostics && hardwareState.connected && !bootNodeSelfTestStarted) {
            bootNodeSelfTestStarted = true
            hardwareController.runBootKeyNodeSelfTest(
                configuredSlotCount = snapshot.cabinetSettings.configuredKeyNodeCount,
                onNodeComplete = { nodeAddress, success ->
                    Log.d(LOG_TAG, "BootSelfTestDiag: node=$nodeAddress success=$success")
                },
                onComplete = { bootNodeSelfTestComplete = true },
            )
        }
    }

    if (showStartupDiagnostics) {
        EkmsTerminalTheme(darkTheme = isDarkTheme) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("eKMS Terminal · Starting up") }) },
            ) { padding ->
                TerminalBootSplashScreen(
                    padding = padding,
                    hardwareState = hardwareState,
                    fingerprintHardwareState = fingerprintHardwareState,
                    cameraAvailable = cameraAvailable,
                    publicCardReaderState = publicCardReaderState,
                    networkStatus = networkStatus,
                    nodeSelfTestComplete = bootNodeSelfTestComplete,
                    onRetryHardware = ::probeStartupHardwareIfNeeded,
                    onContinue = { showStartupDiagnostics = false },
                )
            }
        }
        return
    }

    EkmsTerminalTheme(darkTheme = isDarkTheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val serverChipText = when {
                            liveSyncInProgress -> "Syncing…"
                            !networkStatus.hasInternet -> "Offline"
                            liveServerConnected && apiClient.isAuthenticated -> "Connected"
                            apiClient.isAuthenticated -> "Reconnecting…"
                            else -> "Not linked"
                        }
                        // Title and the two status chips share one full-width row
                        // (Arrangement.SpaceBetween) instead of the title sitting at the
                        // TopAppBar's natural leading edge with the chips stacked in a narrow
                        // Column pinned to the trailing edge — that older layout read as two
                        // floating corner elements with dead space between them on this
                        // full-bleed kiosk tablet. The chips are now side-by-side (Row, not
                        // Column) since SpaceBetween already bounds their combined width to
                        // whatever's left of the title, so the earlier column-stacking
                        // (originally there to avoid overflowing next to a long title) is no
                        // longer needed.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (route) {
                                    // Falls back to the old plain "eKMS Terminal" until a
                                    // pairing/bootstrap response with a siteName has landed
                                    // locally (fresh device, or an older server that doesn't
                                    // join one yet) — see TerminalCabinetSettings.siteName.
                                    SuperAdminRoute.LOGIN -> snapshot.cabinetSettings.siteName
                                        .ifBlank { null }
                                        ?.let { "eKMS Cabinet · $it" }
                                        ?: "eKMS Terminal"
                                    SuperAdminRoute.CHANGE_PASSWORD -> "Secure Super Admin account"
                                    else -> "eKMS Terminal · " + (session?.displayName ?: "Session")
                                },
                            )
                            // Two labeled status chips, global (every route reaches this same
                            // TopAppBar, not just the dashboard) — "Server" is unchanged
                            // network+backend-sync logic; "Cabinet" is hardwareState.connected,
                            // relocated here from the now-removed SuperAdminDashboardScreen-only
                            // badge, so there is exactly one place an operator looks for either.
                            // Label is baked into each chip's own text ("Server: ...", "Cabinet:
                            // ...") rather than a separate label composable, matching this app's
                            // only existing labeled-chip-pair precedent (StartupDiagnosticsScreen's
                            // "Hardware: OK"/"Network: Online (...)" chips).
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SoftAssistChip(
                                    text = "Server: $serverChipText",
                                    success = liveServerConnected && networkStatus.hasInternet,
                                    attention = !networkStatus.hasInternet ||
                                        (apiClient.isAuthenticated && !liveServerConnected),
                                )
                                SoftAssistChip(
                                    text = if (hardwareState.connected) "Cabinet: Connected" else "Cabinet: Disconnected",
                                    success = hardwareState.connected,
                                    attention = !hardwareState.connected,
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            val activeReturnState = returnSessionController.state
            val activePendingCheckoutDecision = pendingCheckoutDecision
            Log.d(LOG_TAG, "ReturnFlowDiag: render dispatch activeReturnState=${activeReturnState.javaClass.simpleName}, route=$route")
            when {
                // Section 3 (key return) is reached directly from the login/home
                // screen by a key-card swipe, never through a menu — so it takes
                // over here regardless of `route`, and returns to whatever route
                // was already showing once it completes.
                activeReturnState is ReturnSession.AwaitingCertification -> TerminalPasswordLoginScreen(
                    padding = padding,
                    onAccountLogin = { username, password ->
                        returnSessionController.attemptCertificationLogin(username, password)
                    },
                    loginError = activeReturnState.loginError,
                )

                // Return Flow rewrite, Tier 3: TerminalKeyReturnScreen is now a pure renderer of
                // ReturnSessionController's own state — Unlocking (the brief "Opening door…"
                // hardware sequence), AwaitingInsertion (waiting for the fob, wrong-key tracked as
                // a field on that same state), and NodeOutcomeMessage (the 3s "Key return
                // problem"/"Key return cancelled" message after a Failed/Abandoned outcome) all
                // render through this one screen, same as the old NodeActive/Failed/Abandoned
                // stages did inside the screen's own local state before this rewrite. No callbacks
                // needed any more — the controller already reacts to the hardware layer itself;
                // this screen has zero interactive elements, same as before.
                activeReturnState is ReturnSession.Unlocking ||
                    activeReturnState is ReturnSession.AwaitingInsertion ||
                    activeReturnState is ReturnSession.NodeOutcomeMessage -> TerminalKeyReturnScreen(
                    padding = padding,
                    state = activeReturnState,
                    videoRecordingEnabled = snapshot.cabinetSettings.returnKeyVideoEnabled,
                    audio = audio,
                )

                activeReturnState is ReturnSession.Waiting -> ReturnSessionScreen(
                    padding = padding,
                    controller = returnSessionController,
                )

                // Mandatory-manual-return-time rework: the once-per-session return-time decision.
                // Cross-cutting for the same reason the ReturnSession branches above are — takeKey()
                // fires from KEY_RETRIEVAL, beginMultiKeyTake() fires from KEY_MENU, and this must
                // intercept either regardless of `route`, before that route's own
                // takeFlow/multiKeyQueue rendering ever runs.
                activePendingCheckoutDecision != null -> TerminalCloseToDeadlineScreen(
                    padding = padding,
                    nowEpochMillis = { System.currentTimeMillis() },
                    onResolved = { choice ->
                        pendingCheckoutDecision = null
                        activePendingCheckoutDecision.resume(choice)
                    },
                )

                else -> when (route) {
                SuperAdminRoute.LOGIN -> when (loginMethod) {
                    null -> TerminalLoginScreen(
                        padding = padding,
                        onSelectMethod = { method -> loginMethod = method },
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                    )

                    LoginMethod.PASSWORD -> TerminalPasswordLoginScreen(
                        padding = padding,
                        onAccountLogin = { username, password -> runServerLogin(username, password) },
                        loginError = notice,
                        onBack = { loginMethod = null },
                    )

                    LoginMethod.NFC_CARD -> TerminalNfcCardLoginScreen(
                        padding = padding,
                        onBack = { loginMethod = null },
                        onSimulateKeyCardTap = { returnSessionController.onKeyCardScanned() },
                    )

                    LoginMethod.FINGERPRINT -> TerminalFingerprintLoginScreen(
                        padding = padding,
                        onBack = { loginMethod = null },
                        onIdentify = fingerprintHardwareController::identifyFingerprint,
                        onMatched = { templateId ->
                            val userId = fingerprintTemplateStore.userIdForTemplate(templateId)
                            if (userId == null) {
                                notice = "Fingerprint matched a template with no linked personnel record."
                            } else {
                                when (val result = store.authenticateByUserId(userId)) {
                                    is StoreResult.Success -> {
                                        if (acceptStandingLogin(result.value)) {
                                            session = result.value
                                            notice = null
                                            loginMethod = null
                                            route = postLoginRoute(result.value)
                                        }
                                    }

                                    is StoreResult.Error -> notice = result.message
                                }
                            }
                        },
                    )

                    LoginMethod.FACE -> TerminalFaceLoginScreen(
                        padding = padding,
                        faceProfileStore = faceProfileStore,
                        onBack = { loginMethod = null },
                        onMatched = { userId, _, _ ->
                            when (val result = store.authenticateByUserId(userId)) {
                                is StoreResult.Success -> {
                                    if (acceptStandingLogin(result.value)) {
                                        session = result.value
                                        notice = null
                                        loginMethod = null
                                        route = postLoginRoute(result.value)
                                    }
                                }

                                is StoreResult.Error -> notice = result.message
                            }
                        },
                    )

                    LoginMethod.PASSKEY -> TerminalPasskeyLoginScreen(
                        padding = padding,
                        onSubmit = { code -> runPasskeyLogin(code) },
                        loginError = notice,
                        onBack = { loginMethod = null },
                    )
                }

                SuperAdminRoute.CHANGE_PASSWORD -> ChangePasswordScreen(
                    padding = padding,
                    onSubmit = { currentPassword, newPassword, confirmPassword ->
                        if (newPassword != confirmPassword) {
                            notice = "The new password and confirmation do not match."
                        } else {
                            when (val result = store.changeSuperAdminPassword(currentPassword, newPassword)) {
                                is StoreResult.Success -> {
                                    refreshSnapshot()
                                    session = session?.copy(requiresPasswordChange = false)
                                    notice = "Super Admin password changed. You can now manage personnel and keys."
                                    route = passwordChangeReturnRoute
                                }

                                is StoreResult.Error -> notice = result.message
                            }
                        }
                    },
                    notice = notice,
                )

                SuperAdminRoute.LANDING_CHOICE -> {
                    val activeSession = session
                    if (activeSession?.isAdminTier != true) {
                        LaunchedEffect(Unit) {
                            route = SuperAdminRoute.LOGIN
                        }
                        TerminalPage(padding) {
                            SuperAdminNoticeCard("Your session has ended. Returning to sign-in…")
                        }
                    } else {
                        TerminalLandingChoiceScreen(
                            padding = padding,
                            roleLabel = activeSession.roleLabel,
                            onTakeKey = {
                                openAdmin(
                                    if (activeSession.isSuperAdmin) {
                                        SuperAdminRoute.KEY_RETRIEVAL
                                    } else {
                                        SuperAdminRoute.KEY_MENU
                                    },
                                )
                            },
                            onAdminPage = { openAdmin(SuperAdminRoute.DASHBOARD) },
                            onSignOut = ::signOut,
                        )
                    }
                }

                SuperAdminRoute.DASHBOARD -> {
                    val activeSession = session
                    if (activeSession?.isAdminTier != true) {
                        LaunchedEffect(Unit) {
                            route = SuperAdminRoute.LOGIN
                        }
                        TerminalPage(padding) {
                            SuperAdminNoticeCard("Your session has ended. Returning to sign-in…")
                        }
                    } else {
                        SuperAdminDashboardScreen(
                            padding = padding,
                            roleLabel = activeSession.roleLabel,
                            snapshot = snapshot,
                            notice = notice,
                            onOpenPersonnel = { openAdmin(SuperAdminRoute.PERSONNEL_LIST) },
                            onOpenKeyAttachment = { openAdmin(SuperAdminRoute.KEY_ATTACHMENT) },
                            onOpenAccessGrants = { openAdmin(SuperAdminRoute.ACCESS_GRANTS) },
                            onOpenKeyRetrieval = { openAdmin(SuperAdminRoute.KEY_RETRIEVAL) },
                            onOpenAdminMenu = { openAdmin(SuperAdminRoute.ADMIN_MENU) },
                            onOpenHardware = { openAdmin(SuperAdminRoute.HARDWARE) },
                            onOpenOfficeHours = { openAdmin(SuperAdminRoute.OFFICE_HOURS) },
                            onOpenVendorPasskey = { openAdmin(SuperAdminRoute.VENDOR_PASSKEY) },
                            onSignOut = ::signOut,
                        )
                    }
                }

                SuperAdminRoute.PERSONNEL_LIST -> {
                    LaunchedEffect(serverLinked) {
                        if (serverLinked) refreshServerPersonnel()
                    }
                    PersonnelListScreen(
                        padding = padding,
                        users = personnelForScreens,
                        notice = notice,
                        onBack = { route = SuperAdminRoute.DASHBOARD },
                        onAddPersonnel = { openAdmin(SuperAdminRoute.ENROLL_USER) },
                        onOpenDetail = { user ->
                            selectedPersonnelId = user.id
                            openAdmin(SuperAdminRoute.PERSONNEL_DETAIL)
                        },
                    )
                }

                SuperAdminRoute.PERSONNEL_DETAIL -> {
                    val detailUser = personnelForScreens.firstOrNull { it.id == selectedPersonnelId }
                    if (detailUser == null) {
                        LaunchedEffect(Unit) {
                            route = SuperAdminRoute.PERSONNEL_LIST
                        }
                        TerminalPage(padding) {
                            SuperAdminNoticeCard("That personnel record is no longer available. Returning to the list…")
                        }
                    } else {
                        PersonnelDetailScreen(
                            padding = padding,
                            user = detailUser,
                            notice = notice,
                            cardStatus = personnelCardStore.enrollmentFor(detailUser.id),
                            fingerprintStatus = fingerprintTemplateStore.enrollmentFor(detailUser.id),
                            faceEnrolled = faceProfileStore.load(detailUser.id) != null,
                            onBack = { route = SuperAdminRoute.PERSONNEL_LIST },
                            onOpenCardEnrollment = { openAdmin(SuperAdminRoute.CARD_ENROLLMENT) },
                            onOpenFingerprintEnrollment = { openAdmin(SuperAdminRoute.FINGERPRINT_ENROLLMENT) },
                            onOpenFaceEnrollment = { openAdmin(SuperAdminRoute.FACE_ENROLLMENT) },
                        )
                    }
                }

                SuperAdminRoute.ENROLL_USER -> {
                    var unitSites by remember { mutableStateOf<List<com.ekms.shared.api.SiteDto>>(emptyList()) }
                    LaunchedEffect(serverLinked) {
                        if (serverLinked) {
                            refreshServerPersonnel()
                            try {
                                unitSites = apiClient.listSites()
                            } catch (error: Throwable) {
                                notice = "Could not load units from server: ${error.message ?: "Unknown error"}"
                            }
                        }
                    }
                    EnrollUserScreen(
                        padding = padding,
                        serverLinked = serverLinked,
                        assignedUnitId = assignedUnitId,
                        unitSites = unitSites,
                        notice = notice,
                        onBack = { route = SuperAdminRoute.PERSONNEL_LIST },
                        onSave = { displayName, identifier, password, role, selectedUnitId ->
                            if (serverLinked) {
                                try {
                                    val userRole = role.toUserRole()
                                    val resolvedUnitId = selectedUnitId.takeIf { it.isNotBlank() }
                                        ?: assignedUnitId?.takeIf { it.isNotBlank() }
                                    val siteIds = when {
                                        userRole == UserRole.SUPER_ADMIN -> emptySet()
                                        !resolvedUnitId.isNullOrBlank() -> setOf(resolvedUnitId)
                                        else -> emptySet()
                                    }
                                    if (userRole != UserRole.SUPER_ADMIN && siteIds.isEmpty()) {
                                        val message =
                                            "Select a Unit before adding personnel (same as Personnel Management on the web portal)."
                                        notice = message
                                        PersonnelSaveResult(ok = false, message = message)
                                    } else {
                                        val created = apiClient.createUser(
                                            CreateAdminUserRequest(
                                                displayName = displayName.trim(),
                                                email = identifier.trim(),
                                                role = userRole,
                                                assignedSiteIds = siteIds,
                                                password = password.takeIf { it.length >= 8 },
                                            ),
                                        )
                                        store.replaceCachedPersonnel(
                                            (serverPersonnel.filterNot { it.id == created.id } + created.toTerminalUser())
                                                .sortedBy { it.displayName.lowercase() },
                                        )
                                        serverPersonnel = store.cachedPersonnel()
                                        val message =
                                            "${created.displayName} was added. It should now appear under Personnel Management on the web portal."
                                        notice = message
                                        PersonnelSaveResult(ok = true, message = message)
                                    }
                                } catch (error: TerminalApiException) {
                                    val message = friendlyCreateUserError(error.message)
                                    notice = message
                                    PersonnelSaveResult(ok = false, message = message)
                                } catch (error: Throwable) {
                                    val message = friendlyCreateUserError(error.message)
                                    notice = message
                                    PersonnelSaveResult(ok = false, message = message)
                                }
                            } else {
                                when (val result = store.createUser(displayName, identifier, password, role)) {
                                    is StoreResult.Success -> {
                                        enqueueChange(
                                            RecordType.USER,
                                            result.value.id,
                                            """{"displayName":"${result.value.displayName}","username":"${result.value.username}","role":"${result.value.role.name}"}""",
                                        )
                                        refreshSnapshot()
                                        val message =
                                            result.value.displayName +
                                                " was enrolled as " +
                                                result.value.role.label +
                                                " (local only — sign in with a server account to sync to the web portal)."
                                        notice = message
                                        PersonnelSaveResult(ok = true, message = message)
                                    }

                                    is StoreResult.Error -> {
                                        notice = result.message
                                        PersonnelSaveResult(ok = false, message = result.message)
                                    }
                                }
                            }
                        },
                    )
                }

                SuperAdminRoute.KEY_ATTACHMENT -> {
                    LaunchedEffect(serverLinked) {
                        if (serverLinked) refreshServerPersonnel()
                    }
                    // Diagnostic only, not a backfill: keyCardStore was never populated from Key
                    // Attachment before the auto-bind fix above, so every already-attached (red)
                    // key predates it and is a backfill candidate. Whether/how to backfill is a
                    // product decision, not made here — this just surfaces the real count/list on
                    // a real device (SharedPreferences-backed stores have no data visible from
                    // static analysis) so that decision can be made from real numbers.
                    LaunchedEffect(retrievalKeys) {
                        val backfillCandidates = retrievalKeys.filter { key ->
                            physicalAttachmentTracker.isAttached(key.id) &&
                                keyCardStore.enrollmentFor(key.id) == null
                        }
                        if (backfillCandidates.isNotEmpty()) {
                            Log.i(
                                LOG_TAG,
                                "Key Attachment backfill candidates: ${backfillCandidates.size} " +
                                    "already-attached key(s) predate the key-card auto-bind fix " +
                                    "and are not yet mirrored into keyCardStore: " +
                                    backfillCandidates.joinToString { "${it.displayName} (${it.id})" },
                            )
                        }
                    }
                    KeyAttachmentScreen(
                        padding = padding,
                        configuredSlotCount = retrievalTerminal.configuredSlotCount,
                        keys = retrievalKeys,
                        initialSlots = retrievalSlots,
                        isUidKnown = { managedKeyId -> managedKeyFobStore.enrollmentFor(managedKeyId) != null },
                        isPhysicallyAttached = physicalAttachmentTracker::isAttached,
                        // New-key registration's actual requirement, corrected (Jul 2026): the
                        // Part 0.1-era assumption that this genuinely needed a real per-user
                        // Super Admin/Regional Admin JWT is now only half true — it needs SOME
                        // authenticated server connection, but `POST /keys`/`/key-slots` were
                        // deliberately widened onto TERMINAL_DEVICE_ALLOWED_ROUTES (see
                        // backend/src/middleware/auth.js's doc comment) so the existing device
                        // pairing token now covers it too. Plain `apiClient.isAuthenticated`
                        // (any token, device or personal) is therefore correct here — not the
                        // stricter app-wide `serverLinked` (which still means "the currently
                        // active session did a real password login" and is used elsewhere, e.g.
                        // refreshServerPersonnel above, for calls that genuinely still need that).
                        canRegisterNewKey = apiClient.isAuthenticated,
                        notice = notice,
                        onBack = {
                            returnSessionController.forceClose { hardwareController.stopMonitoring() }
                            route = SuperAdminRoute.DASHBOARD
                        },
                        onLightOverview = { needsAttachment, alreadyAttached, availableForRegistration, onReady, onFailure ->
                            hardwareController.lightKeyAttachmentOverview(
                                needsAttachment,
                                alreadyAttached,
                                availableForRegistration,
                                onReady,
                                onFailure,
                            )
                        },
                        onClearOverview = hardwareController::clearKeyAttachmentOverview,
                        onScanNodes = hardwareController::scanNodes,
                        onEnsureDoorOpen = hardwareController::ensureDoorOpen,
                        onCheckDoorStatus = hardwareController::checkDoorStatusOnly,
                        onBeginAttachment = hardwareController::beginKeyAttachment,
                        onCancelAttachment = hardwareController::cancelKeyAttachment,
                        onSaveAttachment = { nodeAddress, managedKeyId, rawUidHex ->
                            // Reinsert-save bug fix (Jul 2026, see CLAUDE_TERMINAL.md): mirrors
                            // triggerKeyFobAutoScan's own stale-entry reconciliation, which this
                            // guided flow never applied. If this node's assignment changed since
                            // the last time a fob was captured here (the tracked previous key id
                            // differs from the one being attached now), the old key's UID entry
                            // is revoked first — otherwise the still-owned-by-the-old-key entry
                            // would collide with this enroll() call below and produce
                            // AlreadyAssigned for what is, from the operator's point of view, a
                            // perfectly legitimate reattachment. This does NOT cover a genuinely
                            // different physical fob showing up here unexpectedly (e.g. reusing
                            // one spare test fob across several nodes during ad hoc testing) —
                            // that case has no tracked reassignment to justify overriding, so it
                            // still correctly blocks below with a specific message.
                            val lastKnownForNode = keySlotAssignmentTracker.lastKnownManagedKeyId(nodeAddress)
                            if (lastKnownForNode != null && lastKnownForNode != managedKeyId) {
                                managedKeyFobStore.revoke(lastKnownForNode)
                            }
                            val enrollResult = managedKeyFobStore.enroll(managedKeyId, rawUidHex, System.currentTimeMillis())
                            // AlreadyEnrolledToSelectedRecord happens when this is the reused
                            // attach-flow tail end of a brand-new registration (onRegisterNewKey
                            // below already saved this exact uid/key pair) — treated as success
                            // too, not just Saved, so that path doesn't falsely report failure.
                            val uidSaved = enrollResult is UidEnrollmentResult.Saved ||
                                enrollResult is UidEnrollmentResult.AlreadyEnrolledToSelectedRecord
                            // Real root cause of the reported "Fob captured, but saving the
                            // enrollment failed" regression report: NOT the keyCardStore write
                            // below (it runs strictly after uidSaved is already computed here and
                            // cannot affect it) — it's this pre-existing AlreadyAssigned branch,
                            // unchanged by that fix. Surfaced with a specific, actionable message
                            // instead of the generic UI failure text so it's no longer
                            // indistinguishable from an actual bug.
                            if (enrollResult is UidEnrollmentResult.AlreadyAssigned) {
                                val conflictingKeyId = managedKeyFobStore.recordIdFor(rawUidHex)
                                val conflictingKeyName = retrievalKeys
                                    .firstOrNull { it.id == conflictingKeyId }
                                    ?.displayName
                                    ?: conflictingKeyId
                                    ?: "a different key"
                                notice = "This fob is already registered to $conflictingKeyName, not this key. " +
                                    "Detach it from that key first, or attach the correct fob for this key."
                            }
                            if (enrollResult is UidEnrollmentResult.Saved) {
                                keySlotAssignmentTracker.recordManagedKeyId(nodeAddress, managedKeyId)
                                try {
                                    val terminalId = syncCoordinator.resolveTerminalId()
                                    apiClient.completeKeyFobEnrollment(
                                        managedKeyId,
                                        FobEnrollmentCompleteRequest(
                                            enrollmentReference = enrollResult.summary.enrollmentReference,
                                            terminalId = terminalId,
                                        ),
                                    )
                                } catch (error: Throwable) {
                                    store.logEvent(
                                        AuditEventType.KEY_FOB_ENROLLMENT_SYNC_FAILED,
                                        session?.userId,
                                        RecordType.KEY,
                                        managedKeyId,
                                        error.message ?: "Unknown error",
                                    )
                                }
                            }
                            // This is the fact this pass adds: the operator has now completed this
                            // screen's own guided attach sequence for this key, distinct from (and
                            // gating) uidKnown above — see PhysicalAttachmentTracker's doc comment.
                            if (uidSaved) {
                                physicalAttachmentTracker.markAttached(managedKeyId)
                                // Auto-bind: the exact fob UID just captured at the node also
                                // becomes this key's entry in the key-card namespace, so
                                // CardUidResolver-based Return Flow swipe-entry (public-reader
                                // tap) and wrong-slot detection recognize it immediately —
                                // previously this required a separate manual "Enroll a key
                                // card" pass over the same physical fob (see CardEnrollmentScreen's
                                // Key category / onEnrollKeyCard below). Best-effort and keyed by
                                // managedKeyId, not TerminalKey.id like that manual path — see
                                // onCardDetected's CardUidMatch.Key branch, which checks the
                                // ManagedKey schema first before falling back to legacy TerminalKey
                                // ids. A UID collision with an already-enrolled personnel card is a
                                // data-integrity edge case the NFC UID Resolution Rule says must
                                // never be silently resolved — skipped here rather than guessed;
                                // it simply leaves this key un-key-card-enrolled, same as before
                                // this fix, and does not fail the attach itself, which has already
                                // physically completed.
                                if (!personnelCardStore.isEnrolled(rawUidHex)) {
                                    keyCardStore.enroll(managedKeyId, rawUidHex, System.currentTimeMillis())
                                }
                            }
                            uidSaved
                        },
                        onLightAttachedNode = { nodeAddress -> hardwareController.redLight(nodeAddress, true) },
                        onLogMissingFob = { nodeAddress, managedKeyId ->
                            store.logEvent(
                                AuditEventType.KEY_FOB_MISSING,
                                session?.userId,
                                RecordType.KEY,
                                managedKeyId,
                                "Node $nodeAddress: fob detected missing on Key Attachment exit check.",
                            )
                        },
                        onSetMissingFobBlink = hardwareController::setMissingFobBlink,
                        // Called only when a missing fob is explicitly overridden at the
                        // mandatory exit gate (never on a successful resolve, where the fob is
                        // confirmed genuinely back) — clears BOTH tracked facts so the node
                        // correctly renders BLUE ("needs re-attachment") next time this screen
                        // opens, rather than still claiming a UID/attachment that's no longer true.
                        onResetMissingKeyState = { managedKeyId ->
                            managedKeyFobStore.revoke(managedKeyId)
                            physicalAttachmentTracker.clear(managedKeyId)
                        },
                        onResolveMissingFob = hardwareController::resolveMissingFob,
                        onCancelResolveMissingFob = hardwareController::cancelResolveMissingFob,
                        // Mandatory exit gate's only other way to clear a still-missing node —
                        // must be unambiguously logged, distinct from the automatic KEY_FOB_MISSING
                        // detection event. actorUserId + logEvent's own auto-stamped
                        // occurredAtEpochMillis together record who overrode it and when.
                        onOverrideMissingFob = { nodeAddress, managedKeyId ->
                            store.logEvent(
                                AuditEventType.KEY_FOB_MISSING_OVERRIDDEN,
                                session?.userId,
                                RecordType.KEY,
                                managedKeyId,
                                "Node $nodeAddress: missing fob explicitly overridden, left unresolved.",
                            )
                        },
                        // Deliberately the raw apiClient.download() call, not
                        // syncCoordinator.downloadFromServer() — this screen's polling loop only
                        // needs a fresh KeySlot list to catch deletions; going through the full
                        // sync coordinator would also update app-wide retrievalKeys/retrievalSlots
                        // state and could re-trigger the Part 3 background sweep every 2-5s for
                        // no reason.
                        onPollSlots = {
                            try {
                                apiClient.download(syncCoordinator.resolveTerminalId()).snapshot?.keySlots
                            } catch (_: Throwable) {
                                null
                            }
                        },
                        // Registers a brand-new key entirely from the terminal (new capability).
                        // Reuses the existing POST /v1/admin/keys and POST /v1/admin/key-slots
                        // routes exactly as web would call them, using whatever token apiClient
                        // currently holds — a TERMINAL_DEVICE pairing token now works here too
                        // (Jul 2026 backend widening, see auth.js's allowlist doc comment), so
                        // this no longer requires the stricter serverLinked (password-login) gate,
                        // just apiClient.isAuthenticated. No offline queue: a live failure is
                        // surfaced as-is for the operator to retry, never queued like the
                        // offline-first hardware event outbox elsewhere in this app.
                        onRegisterNewKey = { nodeAddress, uidHex, displayName ->
                            if (!apiClient.isAuthenticated) {
                                RegisterNewKeyResult.Failed(
                                    "Connect this terminal to the server (Admin Menu → server address, or re-pair) to register a new key from this terminal.",
                                )
                            } else {
                                try {
                                    val createdKey = apiClient.createKey(
                                        KeyUpsertRequest(siteId = retrievalTerminal.siteId, displayName = displayName),
                                    )
                                    var slotFailureMessage: String? = null
                                    try {
                                        // Node-capacity bug fix (Jul 2026, see
                                        // CLAUDE_TERMINAL.md): a node's KeySlot row can already
                                        // exist, ACTIVE, with managedKeyId == null — e.g. a
                                        // deleted key's slot, left unlinked-not-deleted so the
                                        // node stays reusable (see
                                        // web/src/api/keySlotAssignment.ts's identical
                                        // reasoning). key-slots.js's duplicate-node check
                                        // rejects ANY second ACTIVE row at the same node
                                        // regardless of managedKeyId, so a blind createKeySlot
                                        // POST always failed with "Node address already assigned
                                        // on this terminal" for such a node — even though web
                                        // (and this screen's own discovery sweep) both correctly
                                        // treat it as free. Mirrors web's
                                        // assignKeyToNextAvailableNode: fetch the live list,
                                        // reuse via PATCH when an unassigned row already exists,
                                        // only POST-create when none does. A live fetch (not the
                                        // app-wide retrievalSlots cache) is required — the
                                        // domain KeySlot type carries no revision field, and a
                                        // PATCH needs the real current one.
                                        val existingSlot = try {
                                            apiClient.listKeySlots(retrievalTerminal.id)
                                                .firstOrNull { it.nodeAddress == nodeAddress }
                                        } catch (_: Throwable) {
                                            // listKeySlots is not yet on
                                            // TERMINAL_DEVICE_ALLOWED_ROUTES — a
                                            // TERMINAL_DEVICE-only session 403s here. Falls back
                                            // to the original create-only behavior rather than
                                            // failing registration outright, so a genuinely-new
                                            // node (no pre-existing row) still works exactly as
                                            // before for those sessions.
                                            null
                                        }
                                        if (existingSlot != null && existingSlot.managedKeyId == null) {
                                            apiClient.updateKeySlot(
                                                existingSlot.id,
                                                KeySlotUpsertRequest(
                                                    terminalId = retrievalTerminal.id,
                                                    nodeAddress = nodeAddress,
                                                    managedKeyId = createdKey.id,
                                                    expectedRevision = existingSlot.revision,
                                                ),
                                            )
                                        } else {
                                            apiClient.createKeySlot(
                                                KeySlotUpsertRequest(
                                                    terminalId = retrievalTerminal.id,
                                                    nodeAddress = nodeAddress,
                                                    managedKeyId = createdKey.id,
                                                ),
                                            )
                                        }
                                    } catch (slotError: Throwable) {
                                        slotFailureMessage = slotError.message ?: "Unknown error"
                                    }
                                    if (slotFailureMessage != null) {
                                        // Bonus fix (hardware-tested): don't leave the now-orphaned,
                                        // never-slotted key behind on the backend — best-effort,
                                        // since the operator still needs to see the original slot
                                        // failure either way.
                                        val cleanupFailureNote = try {
                                            apiClient.deleteKey(createdKey.id)
                                            null
                                        } catch (deleteError: Throwable) {
                                            " (and it could not be automatically removed: ${deleteError.message ?: "unknown error"} — check the web portal)"
                                        }
                                        RegisterNewKeyResult.Failed(
                                            "Key \"$displayName\" was created, but binding it to node $nodeAddress " +
                                                "failed: $slotFailureMessage.${cleanupFailureNote ?: " The key was removed — try again."}",
                                        )
                                    } else {
                                        val enrollResult = managedKeyFobStore.enroll(
                                            createdKey.id,
                                            uidHex,
                                            System.currentTimeMillis(),
                                        )
                                        if (enrollResult is UidEnrollmentResult.Saved) {
                                            keySlotAssignmentTracker.recordManagedKeyId(nodeAddress, createdKey.id)
                                            try {
                                                apiClient.completeKeyFobEnrollment(
                                                    createdKey.id,
                                                    FobEnrollmentCompleteRequest(
                                                        enrollmentReference = enrollResult.summary.enrollmentReference,
                                                        terminalId = retrievalTerminal.id,
                                                    ),
                                                )
                                            } catch (reportError: Throwable) {
                                                store.logEvent(
                                                    AuditEventType.KEY_FOB_ENROLLMENT_SYNC_FAILED,
                                                    session?.userId,
                                                    RecordType.KEY,
                                                    createdKey.id,
                                                    reportError.message ?: "Unknown error",
                                                )
                                            }
                                        }
                                        // Real fix (hardware-tested): the two direct API calls
                                        // above never told syncCoordinator/retrievalKeys/
                                        // retrievalSlots anything happened, so a fresh screen
                                        // entry after navigating away re-rendered from the still-
                                        // stale pre-registration snapshot — the node looked
                                        // unregistered again even though the backend had the real
                                        // record, and re-tapping it hit key-slots.js's duplicate-
                                        // node check. Reuses the exact same
                                        // syncCoordinator.downloadFromServer() the Admin Menu's own
                                        // Download button calls, then refreshSnapshot() (the same
                                        // function every other sync entry point already uses to
                                        // repopulate retrievalKeys/retrievalSlots). Best-effort: the
                                        // key+slot already exist server-side regardless of whether
                                        // this succeeds, so a failure here doesn't fail the
                                        // registration itself — it just means the operator may need
                                        // to hit Download manually later, same recovery as any
                                        // other missed sync.
                                        try {
                                            syncCoordinator.downloadFromServer()
                                            refreshSnapshot()
                                        } catch (_: Throwable) {
                                            // Silently best-effort — see comment above.
                                        }
                                        RegisterNewKeyResult.Success(
                                            ManagedKey(
                                                id = createdKey.id,
                                                siteId = createdKey.siteId,
                                                displayName = createdKey.displayName,
                                                fobEnrollmentReference = createdKey.fobEnrollmentReference,
                                                lifecycle = LifecycleMetadata(
                                                    createdAtEpochMillis = System.currentTimeMillis(),
                                                    updatedAtEpochMillis = System.currentTimeMillis(),
                                                ),
                                            ),
                                        )
                                    }
                                } catch (error: Throwable) {
                                    RegisterNewKeyResult.Failed(
                                        "Could not register the new key — this requires an internet connection " +
                                            "to the server. (${error.message ?: "Unknown error"})",
                                    )
                                }
                            }
                        },
                        onOpenKeyCardEnrollment = {
                            returnSessionController.forceClose { hardwareController.stopMonitoring() }
                            selectedPersonnelId = null
                            openAdmin(SuperAdminRoute.CARD_ENROLLMENT)
                        },
                    )
                }

                SuperAdminRoute.CARD_ENROLLMENT -> {
                    LaunchedEffect(serverLinked) {
                        if (serverLinked) refreshServerPersonnel()
                    }
                    // Entered either from Personnel Management (scoped to one user, Personnel
                    // category locked) or from Key Attachment's key-card sub-entry (no user
                    // selected, Key category locked) — see selectedPersonnelId's doc above.
                    val cardScopedUser = personnelForScreens.firstOrNull { it.id == selectedPersonnelId }
                    CardEnrollmentScreen(
                        padding = padding,
                        users = listOfNotNull(cardScopedUser),
                        keys = if (cardScopedUser != null) emptyList() else snapshot.keys,
                        initialCategory = if (cardScopedUser != null) {
                            CardEnrollmentCategory.PERSONNEL
                        } else {
                            CardEnrollmentCategory.KEY
                        },
                        lockCategory = true,
                        notice = notice,
                        onBack = {
                            route = if (cardScopedUser != null) {
                                SuperAdminRoute.PERSONNEL_DETAIL
                            } else {
                                SuperAdminRoute.KEY_ATTACHMENT
                            }
                        },
                        onEnrollPersonnelCard = { userId, rawUid ->
                            if (keyCardStore.isEnrolled(rawUid)) {
                                UidEnrollmentResult.AlreadyAssigned
                            } else {
                                val result = personnelCardStore.enroll(userId, rawUid, System.currentTimeMillis())
                                if (result is UidEnrollmentResult.Saved) {
                                    reportPersonnelCardEnrollment(userId, result.summary.enrollmentReference)
                                }
                                result
                            }
                        },
                        onEnrollKeyCard = { keyId, rawUid ->
                            if (personnelCardStore.isEnrolled(rawUid)) {
                                UidEnrollmentResult.AlreadyAssigned
                            } else {
                                keyCardStore.enroll(keyId, rawUid, System.currentTimeMillis())
                            }
                        },
                        onRevokePersonnelCard = { userId ->
                            val summary = personnelCardStore.revoke(userId)
                            if (summary != null) reportPersonnelCardRevoke(userId)
                            summary
                        },
                        onRevokeKeyCard = keyCardStore::revoke,
                    )
                }

                SuperAdminRoute.FINGERPRINT_ENROLLMENT -> {
                    LaunchedEffect(serverLinked) {
                        if (serverLinked) refreshServerPersonnel()
                    }
                    FingerprintEnrollmentScreen(
                        padding = padding,
                        // Scoped to the Personnel Management record this was opened from. Vendor
                        // may only ever enroll NFC card (permanent rule, not a UI suggestion) —
                        // hard-excluded here too, not just by PersonnelDetailScreen hiding the row.
                        users = personnelForScreens
                            .filter { it.id == selectedPersonnelId }
                            .filterNot { it.role == TerminalUserRole.VENDOR },
                        notice = notice,
                        hardwareState = fingerprintHardwareState,
                        onBack = { route = SuperAdminRoute.PERSONNEL_DETAIL },
                        existingEnrollment = fingerprintTemplateStore::enrollmentFor,
                        onEnroll = { userId, onProgress, onOutcome ->
                            fingerprintHardwareController.enrollFingerprint(
                                onProgress = onProgress,
                                onOutcome = { outcome ->
                                    if (outcome is FingerprintEnrollmentOutcome.Success) {
                                        val summary = fingerprintTemplateStore.save(
                                            userId,
                                            outcome.templateId,
                                            System.currentTimeMillis(),
                                        )
                                        reportFingerprintEnrollment(userId, summary.enrollmentReference)
                                    }
                                    onOutcome(outcome)
                                },
                            )
                        },
                        onRevoke = { userId, templateId, onOutcome ->
                            fingerprintHardwareController.deleteTemplate(templateId) { success, message ->
                                if (success) {
                                    fingerprintTemplateStore.revoke(userId)
                                    reportFingerprintRevoke(userId)
                                }
                                onOutcome(success, message)
                            }
                        },
                    )
                }

                SuperAdminRoute.FACE_ENROLLMENT -> {
                    LaunchedEffect(serverLinked) {
                        if (serverLinked) refreshServerPersonnel()
                    }
                    FaceEnrollmentScreen(
                        padding = padding,
                        // Scoped to the Personnel Management record this was opened from. Vendor
                        // may only ever enroll NFC card (permanent rule, not a UI suggestion) —
                        // hard-excluded here too, not just by PersonnelDetailScreen hiding the row.
                        users = personnelForScreens
                            .filter { it.id == selectedPersonnelId }
                            .filterNot { it.role == TerminalUserRole.VENDOR },
                        notice = notice,
                        faceProfileStore = faceProfileStore,
                        onBack = { route = SuperAdminRoute.PERSONNEL_DETAIL },
                        onEnrollmentSaved = { userId, profile ->
                            reportFaceEnrollment(userId, profile.enrollmentReference)
                        },
                        onRevoke = { userId ->
                            faceProfileStore.delete(userId)
                            reportFaceRevoke(userId)
                        },
                    )
                }

                SuperAdminRoute.ACCESS_GRANTS -> AccessGrantsScreen(
                    padding = padding,
                    role = session?.role ?: TerminalUserRole.SUPER_ADMIN,
                    users = personnelForScreens.filterNot {
                        it.isPreset || it.role == TerminalUserRole.SUPER_ADMIN
                    },
                    keys = snapshot.keys,
                    grants = snapshot.accessGrants,
                    notice = notice,
                    onBack = { route = SuperAdminRoute.DASHBOARD },
                    onGrant = { userId, keyId ->
                        when (val result = store.grantAccess(userId, keyId)) {
                            is StoreResult.Success -> {
                                enqueueChange(
                                    RecordType.ACCESS_GRANT,
                                    result.value.id,
                                    """{"userId":"$userId","keyId":"$keyId"}""",
                                )
                                refreshSnapshot()
                                notice = "Access granted."
                            }

                            is StoreResult.Error -> notice = result.message
                        }
                    },
                    onRevoke = { grantId ->
                        when (val result = store.revokeAccess(grantId)) {
                            is StoreResult.Success -> {
                                enqueueChange(
                                    RecordType.ACCESS_GRANT,
                                    grantId,
                                    """{"revoked":true}""",
                                )
                                refreshSnapshot()
                                notice = "Access grant removed."
                            }

                            is StoreResult.Error -> notice = result.message
                        }
                    },
                )

                SuperAdminRoute.ADMIN_MENU -> TerminalAdminMenuScreen(
                    padding = padding,
                    role = session?.role ?: TerminalUserRole.SUPER_ADMIN,
                    assignedSiteIds = session?.assignedSiteIds ?: emptySet(),
                    onLoadSites = { apiClient.listSites() },
                    settings = snapshot.cabinetSettings,
                    highestRegisteredNodeAddress = retrievalSlots
                        .filter { it.managedKeyId != null }
                        .maxOfOrNull { it.nodeAddress },
                    notice = notice,
                    syncBusy = syncBusy,
                    pendingOutboxCount = pendingOutboxCount,
                    onBack = { route = SuperAdminRoute.DASHBOARD },
                    onSave = { updatedSettings ->
                        when (val result = store.updateCabinetSettings(updatedSettings)) {
                            is StoreResult.Success -> {
                                apiClient.syncBaseUrlFromSettings(result.value.serverAddress)
                                refreshSnapshot()
                                retrievalTerminal = retrievalTerminal.copy(configuredSlotCount = result.value.configuredKeyNodeCount)
                                enqueueChange(
                                    RecordType.TERMINAL,
                                    result.value.cabinetId.ifBlank { "local-cabinet" },
                                    """{"cabinetName":"${result.value.cabinetName}","configuredKeyNodeCount":${result.value.configuredKeyNodeCount}}""",
                                )
                                notice = "Admin Menu settings saved."
                            }

                            is StoreResult.Error -> notice = result.message
                        }
                    },
                    onOpenPasswordChange = {
                        passwordChangeReturnRoute = SuperAdminRoute.ADMIN_MENU
                        notice = null
                        route = SuperAdminRoute.CHANGE_PASSWORD
                    },
                    onBootstrap = {
                        runSyncAction("Bootstrap") {
                            val response = syncCoordinator.bootstrap()
                            response.snapshot?.keySlots?.let(::triggerKeyFobAutoScan)
                            val keys = response.snapshot?.keys?.size ?: 0
                            "Bootstrap OK · revision ${response.serverRevision} · $keys keys hydrated."
                        }
                    },
                    onPush = {
                        runSyncAction("Push") {
                            val actor = session?.userId ?: "local"
                            val response = syncCoordinator.pushPending(actor)
                            "Push OK · accepted ${response.acceptedOperationIds.size}, conflicts ${response.conflicts.size}."
                        }
                    },
                    onRead = {
                        runSyncAction("Read") {
                            val ack = syncCoordinator.readFromServer()
                            ack.message ?: "Read request accepted."
                        }
                    },
                    onDownload = {
                        runSyncAction("Download") {
                            val ack = syncCoordinator.downloadFromServer()
                            ack.snapshot?.keySlots?.let(::triggerKeyFobAutoScan)
                            val keys = ack.snapshot?.keys?.size ?: 0
                            ack.message ?: "Download OK · $keys keys hydrated (revision ${ack.serverRevision})."
                        }
                    },
                )

                SuperAdminRoute.HARDWARE -> HardwareControlScreen(
                    padding = padding,
                    hardwareState = hardwareState,
                    notice = notice,
                    onBack = { route = SuperAdminRoute.DASHBOARD },
                    onConnect = { portPath, baudRate, boxAddress ->
                        hardwareController.connect(portPath, baudRate, boxAddress)
                    },
                    onDisconnect = {
                        returnSessionController.forceClose { hardwareController.disconnect() }
                        capturedFob = null
                    },
                    onCheckDoor = hardwareController::checkDoorStatus,
                    onOpenDoor = {
                        askForPhysicalConfirmation(
                            title = "Eject cabinet door?",
                            message = "This sends command 0x23 and may physically open the cabinet door.",
                            onConfirm = hardwareController::ejectDoor,
                        )
                    },
                    onNodeStatus = hardwareController::readNodeStatus,
                    onReadFob = ::captureFobFromNode,
                    onBlueLight = hardwareController::blueLight,
                    onRedLight = hardwareController::redLight,
                    onEngage = { nodeAddress ->
                        askForPhysicalConfirmation(
                            title = "Engage electromagnet at node " + nodeAddress + "?",
                            message = "This sends supplier command 0x13 to the selected key peg.",
                            onConfirm = { hardwareController.engageElectromagnet(nodeAddress) },
                        )
                    },
                    onRelease = { nodeAddress ->
                        askForPhysicalConfirmation(
                            title = "Release electromagnet at node " + nodeAddress + "?",
                            message = "This sends supplier command 0x14 to the selected key peg.",
                            onConfirm = { hardwareController.releaseElectromagnet(nodeAddress) },
                        )
                    },
                )

                SuperAdminRoute.OFFICE_HOURS -> TerminalOfficeHoursScreen(
                    padding = padding,
                    role = session?.role ?: TerminalUserRole.SUPER_ADMIN,
                    siteId = retrievalTerminal.siteId,
                    onBack = { route = SuperAdminRoute.DASHBOARD },
                    onLoad = { siteId -> apiClient.getOfficeHours(siteId) },
                    onSave = { siteId, request -> apiClient.updateOfficeHours(siteId, request) },
                )

                SuperAdminRoute.VENDOR_PASSKEY -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BackButton(onBack = { route = SuperAdminRoute.DASHBOARD })
                    Text("Vendor access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    SoftCard(contentPadding = 16.dp) {
                        Text(
                            text = "Vendor passkey requests moved to the mobile app (PIC → Regional Admin → PIN).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "This terminal screen is retired. Use Key Access on mobile for new Vendor requests.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SuperAdminRoute.KEY_RETRIEVAL -> {
                    val activeSession = session
                    val activeTakeFlow = takeFlow
                    if (activeTakeFlow != null) {
                        TerminalKeyTakeScreen(
                            padding = padding,
                            key = activeTakeFlow.key,
                            slot = activeTakeFlow.slot,
                            takeWarningTimeSeconds = snapshot.cabinetSettings.takeWarningTimeSeconds,
                            videoRecordingEnabled = snapshot.cabinetSettings.keyRetrievalVideoEnabled,
                            audio = audio,
                            onBeginTake = hardwareController::beginKeyTake,
                            onPollRemoval = hardwareController::pollForKeyRemoval,
                            onWaitForDoorClose = hardwareController::waitForDoorCloseAfterTake,
                            onKeyRemoved = { takenKeyIds = takenKeyIds + activeTakeFlow.key.id },
                            onEvent = { outcome -> handleTakeFlowOutcome(outcome, activeTakeFlow.checkoutDeadline) },
                            // waitForDoorCloseAfterTake no longer releases takeMonitoring itself
                            // (Jul 2026 fix, see CabinetHardwareController.endTakeSession's doc)
                            // — the single-key path is always its own whole session, so this is
                            // the one and only place that needs to call it.
                            onCompleted = {
                                hardwareController.endTakeSession()
                                takeFlow = null
                            },
                            // Auto-return-to-login pass: the single-key path is always its own
                            // whole session (same reasoning as endTakeSession's own call site
                            // above), so a genuine door-close-confirmed success here ends the
                            // operator's session too, not just the take.
                            onSessionComplete = {
                                hardwareController.endTakeSession()
                                takeFlow = null
                                returnToLoginAfterSessionComplete()
                            },
                        )
                    } else {
                        TerminalKeyRetrievalScreen(
                            padding = padding,
                            terminal = retrievalTerminal,
                            keys = retrievalKeys,
                            slots = retrievalSlots,
                            takenKeyIds = takenKeyIds,
                            videoRecordingEnabled = snapshot.cabinetSettings.keyRetrievalVideoEnabled,
                            backLabel = if (activeSession?.isAdminTier == true) "Back to dashboard" else "Sign out",
                            onBack = {
                                if (activeSession?.isAdminTier == true) {
                                    route = SuperAdminRoute.DASHBOARD
                                } else {
                                    signOut()
                                }
                            },
                            onTakeKey = ::takeKey,
                        )
                    }
                }

                SuperAdminRoute.KEY_MENU -> {
                    val activeQueue = multiKeyQueue
                    when {
                        activeQueue != null -> {
                            val (currentKey, currentSlot) = activeQueue.current
                            // Door-stays-open-across-the-queue redesign (Jul 2026, found via ad
                            // hoc hardware testing): advancing to the next node used to happen
                            // only in onCompleted (door-close time) — meaning node 2 never even
                            // started until node 1's door had already closed, forcing a full
                            // close-then-reopen cycle between every queued key even though
                            // nothing about the hardware requires it. `nextQueue` is computed
                            // once here, from this node's own immutable queue snapshot — reused
                            // by both callbacks below rather than each calling activeQueue.advanced()
                            // independently, so there's exactly one "is there a next item" answer
                            // for this node, not two that could disagree.
                            val nextQueue = activeQueue.advanced()
                            TerminalKeyTakeScreen(
                                padding = padding,
                                key = currentKey,
                                slot = currentSlot,
                                takeWarningTimeSeconds = snapshot.cabinetSettings.takeWarningTimeSeconds,
                                videoRecordingEnabled = snapshot.cabinetSettings.keyRetrievalVideoEnabled,
                                audio = audio,
                                // isContinuingSession = true for every node after the first —
                                // takeMonitoring is held for the whole queue, not re-acquired per
                                // node (see beginQueuedKeyTake's doc), so only the first node's
                                // begin should try to freshly acquire it.
                                onBeginTake = { addr, onOpen, onFail ->
                                    hardwareController.beginQueuedKeyTake(
                                        addr,
                                        onOpen,
                                        onFail,
                                        isContinuingSession = activeQueue.currentIndex > 0,
                                    )
                                },
                                onPollRemoval = hardwareController::pollForKeyRemoval,
                                onWaitForDoorClose = hardwareController::waitForDoorCloseAfterTake,
                                // Fires at confirmed removal — the same "safe to move on" moment
                                // the electromagnet guard now releases at (see
                                // pollForKeyRemoval's own fix). If there's a next queued key,
                                // advance immediately: this mounts node 2's TerminalKeyTakeScreen
                                // right away, tearing down node 1's own composable/LaunchedEffect
                                // — but NOT node 1's actual door-close polling, which lives in
                                // CabinetHardwareController as a plain background-thread loop
                                // (waitForDoorCloseAfterTake's worker.execute), unaffected by
                                // Compose disposal. Node 1's warning-time voice line and
                                // DoorLeftOpen logging keep firing correctly whenever its door
                                // actually closes, however much later that is — only its
                                // continuous beep stops early (tied to the now-disposed
                                // composable's own beep-loop LaunchedEffect), a deliberate,
                                // accepted trade-off rather than two overlapping continuous beeps.
                                onKeyRemoved = {
                                    takenKeyIds = takenKeyIds + currentKey.id
                                    if (nextQueue != null) {
                                        // "Please take your next key" one-shot: every key
                                        // except the last one in the queue. The genuinely
                                        // last key (nextQueue == null) plays nothing here and
                                        // proceeds straight to the existing end-of-queue
                                        // Phase 2 close-door cycle in onCompleted below,
                                        // unchanged.
                                        audio.playVoiceLine(VoiceLine.PLEASE_TAKE_YOUR_NEXT_KEY)
                                        multiKeyQueue = nextQueue
                                    }
                                },
                                onEvent = { outcome -> handleTakeFlowOutcome(outcome, activeQueue.checkoutDeadline) },
                                // Door-close time. For a non-last key, multiKeyQueue already
                                // advanced above at removal — this must NOT touch it again here,
                                // since by the time this actually fires (possibly well after
                                // later queue items are already in progress), activeQueue is a
                                // stale snapshot from this node's own instantiation; re-advancing
                                // from it would silently roll the queue back. Only the genuinely
                                // last key still clears the queue here, at its own door-close, same
                                // as before this fix.
                                onCompleted = {
                                    if (nextQueue == null) {
                                        // Only the genuinely last node releases the session-wide
                                        // guard — see endTakeSession's doc: waitForDoorCloseAfterTake
                                        // no longer does this itself, since another still-pending
                                        // node's own door-close-wait may still be running.
                                        hardwareController.endTakeSession()
                                        multiKeyQueue = null
                                        notice = "All selected keys have been taken."
                                    }
                                },
                                // Auto-return-to-login pass: only the genuinely last node's own
                                // door-close reaches this callback while its owning composable
                                // is still mounted — a non-last node's TerminalKeyTakeScreen
                                // instance is already torn down (superseded by the next queued
                                // node, see onKeyRemoved above) by the time its own door
                                // physically closes, same accepted trade-off onCompleted already
                                // has above (a stray late callback there is similarly a no-op
                                // for the disposed instance). `nextQueue == null` guard kept for
                                // defense in depth even though it should be structurally implied.
                                onSessionComplete = {
                                    if (nextQueue == null) {
                                        hardwareController.endTakeSession()
                                        multiKeyQueue = null
                                        returnToLoginAfterSessionComplete()
                                    }
                                },
                            )
                        }

                        multiKeyQueuePending -> {
                            TerminalPage(padding) {
                                HeaderCard(title = "Preparing your keys", description = hardwareState.message)
                            }
                        }

                        else -> {
                            // Role-conditional back action (mirrors KEY_RETRIEVAL's existing
                            // pattern above) — added this pass because Regional Admin now also
                            // reaches this screen via TerminalLandingChoiceScreen's "Take Key"
                            // option and should return to their dashboard, same as Super Admin
                            // does from KEY_RETRIEVAL. Technician/Vendor's own observed behavior
                            // is unchanged: session?.isAdminTier is false for both, so they still
                            // see exactly "Sign out" -> signOut(), byte-for-byte as before.
                            val activeSession = session
                            TerminalKeyMenuScreen(
                                padding = padding,
                                authorizedKeys = authorizedKeysForCurrentUser,
                                slots = retrievalSlots,
                                takenKeyIds = takenKeyIds,
                                backLabel = if (activeSession?.isAdminTier == true) "Back to dashboard" else "Sign out",
                                onBack = {
                                    if (activeSession?.isAdminTier == true) {
                                        route = SuperAdminRoute.DASHBOARD
                                    } else {
                                        signOut()
                                    }
                                },
                                onConfirmSelection = ::beginMultiKeyTake,
                            )
                        }
                    }
                }
                }
            }
        }

        pendingPhysicalAction?.let { action ->
            AlertDialog(
                onDismissRequest = { pendingPhysicalAction = null },
                title = { Text(action.title) },
                text = { Text(action.message) },
                confirmButton = {
                    // Phase 9 design-system pilot: IconActionButton in a real confirm/cancel
                    // context, per CLAUDE.md Phase 9 scope (pilot screens only this pass).
                    IconActionButton(
                        type = ActionButtonType.ACCEPT,
                        label = "Confirm physical action",
                        onClick = {
                            action.onConfirm()
                            pendingPhysicalAction = null
                        },
                    )
                },
                dismissButton = {
                    IconActionButton(
                        type = ActionButtonType.CANCEL,
                        onClick = { pendingPhysicalAction = null },
                    )
                },
            )
        }
    }
}

@Composable
private fun ChangePasswordScreen(
    padding: PaddingValues,
    onSubmit: (String, String, String) -> Unit,
    notice: String?,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    TerminalPage(padding) {
        HeaderCard(
            title = "Change preset password",
            description = "This is required once before the Super Admin can manage personnel, keys or send cabinet commands.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        PasswordField("Current password", currentPassword) { currentPassword = it }
        PasswordField("New password (minimum 8 characters)", newPassword) { newPassword = it }
        PasswordField("Confirm new password", confirmPassword) { confirmPassword = it }
        Button(
            onClick = { onSubmit(currentPassword, newPassword, confirmPassword) },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentPassword.isNotBlank() && newPassword.length >= 8 && confirmPassword.isNotBlank(),
        ) {
            Text("Save Super Admin password")
        }
    }
}

@Composable
private fun SuperAdminDashboardScreen(
    padding: PaddingValues,
    roleLabel: String,
    snapshot: TerminalAdminSnapshot,
    notice: String?,
    onOpenPersonnel: () -> Unit,
    onOpenKeyAttachment: () -> Unit,
    onOpenAccessGrants: () -> Unit,
    onOpenKeyRetrieval: () -> Unit,
    onOpenAdminMenu: () -> Unit,
    onOpenHardware: () -> Unit,
    onOpenOfficeHours: () -> Unit,
    onOpenVendorPasskey: () -> Unit,
    onSignOut: () -> Unit,
) {
    TerminalPage(padding) {
        // Cabinet-connection badge relocated to the global TopAppBar (see Scaffold's topBar
        // above, alongside the "Server" chip) — this screen no longer renders its own,
        // dashboard-only copy of it.
        Column {
            Text(
                text = roleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "What do you need?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        notice?.let { message -> SuperAdminNoticeCard(message) }

        // "Take keys" demoted from SoftHeroAction to a normal SoftNavTile (an earlier pass) — the
        // hero-weight either/or choice now lives on TerminalLandingChoiceScreen, which every
        // admin-tier session already passes through before ever reaching this dashboard; leaving
        // it as a lone hero here read as visually disconnected from the rest of the grid. First
        // tile, top-left, per instruction — still the most-reached admin action even with the
        // landing chooser's own "Take Key" option, so keeping it first stays the right call.
        // Card/Fingerprint/Face enrollment tiles removed (this pass): Personnel Management is now
        // the only path to any of them, scoped per-user from its detail view. That drops the grid
        // back to an even 8 tiles / 4 rows, so the odd-row Spacer balancing hack is gone too.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SoftNavTile(label = "Take keys", onClick = onOpenKeyRetrieval, modifier = Modifier.weight(1f))
            SoftNavTile(label = "Permission Settings", onClick = onOpenAccessGrants, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SoftNavTile(label = "Personnel Management", onClick = onOpenPersonnel, modifier = Modifier.weight(1f))
            SoftNavTile(label = "Key Attachment", onClick = onOpenKeyAttachment, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SoftNavTile(label = "Admin Menu", onClick = onOpenAdminMenu, modifier = Modifier.weight(1f))
            SoftNavTile(label = "Hardware", onClick = onOpenHardware, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SoftNavTile(label = "Office hours", onClick = onOpenOfficeHours, modifier = Modifier.weight(1f))
            SoftNavTile(
                label = "Vendor access (mobile)",
                onClick = onOpenVendorPasskey,
                modifier = Modifier.weight(1f),
            )
        }

        SoftCard(contentPadding = 14.dp) {
            Text(
                text = "${snapshot.users.size} personnel · ${snapshot.keys.size} keys · ${snapshot.accessGrants.size} grants",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SoftTextButton(text = "Sign out", onClick = onSignOut)
    }
}

@Composable
/**
 * Phase 7 (item 13). [role] == SUPER_ADMIN keeps this screen exactly as it was before this
 * phase — full grant/revoke. Any other role reaching this screen (only REGIONAL_ADMIN can, per
 * [com.ekms.terminal.data.TerminalSession.isAdminTier]) gets the same lists with no grant/revoke
 * controls — a read-only "preassigned list" per the matrix. Site-scoping is structurally already
 * correct here without extra code: this screen only ever shows [keys]/[grants] belonging to
 * *this* physical terminal's own site (there is no cross-site data reachable from a single
 * terminal's local grant store to begin with — unlike the backend's `/v1/admin/access-grants`,
 * which can be queried across sites and needed `isSiteAssignedToUser` for exactly that reason).
 */
private fun AccessGrantsScreen(
    padding: PaddingValues,
    role: TerminalUserRole,
    users: List<TerminalUser>,
    keys: List<TerminalKey>,
    grants: List<TerminalAccessGrant>,
    notice: String?,
    onBack: () -> Unit,
    onGrant: (String, String) -> Unit,
    onRevoke: (String) -> Unit,
) {
    val canEdit = role == TerminalUserRole.SUPER_ADMIN
    var selectedUserId by remember(users) { mutableStateOf(users.firstOrNull()?.id.orEmpty()) }
    val selectedUser = users.firstOrNull { it.id == selectedUserId }
    val userGrants = grants.filter { it.userId == selectedUserId }
    val grantedKeyIds = userGrants.map { it.keyId }.toSet()
    val availableKeys = keys.filter { it.id !in grantedKeyIds }

    TerminalPage(padding) {
        BackButton(onBack)
        HeaderCard(
            title = "Permission Settings",
            description = if (canEdit) {
                "Bind only the exact keys enrolled personnel may take. A grant here is separate from the personnel record, matching Permission Settings on the web portal."
            } else {
                "View-only: which exact keys enrolled personnel may take. Edit permissions on the web portal."
            },
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        if (users.isEmpty()) {
            Text("Add Technician or Vendor personnel before creating a permission.")
            return@TerminalPage
        }

        Text("Selected personnel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { selectedUserId = nextUserId(selectedUserId, users) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text((selectedUser?.let { it.displayName + " · " + it.role.label } ?: "Select personnel") + " · change")
        }

        if (canEdit) {
            Text("Unauthorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (keys.isEmpty()) {
                Text("No key has been enrolled yet.")
            } else if (availableKeys.isEmpty()) {
                Text("Every enrolled key is already granted to this personnel.")
            }
            availableKeys.forEach { key ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(key.displayName + "\nBox " + key.boxAddress + " · Node " + key.nodeAddress)
                        Button(
                            onClick = { onGrant(selectedUserId, key.id) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedUserId.isNotBlank(),
                        ) {
                            Text("Bind exact key")
                        }
                    }
                }
            }
        }

        Text("Authorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (userGrants.isEmpty()) {
            Text("No exact key permission is currently assigned to this personnel.")
        }
        userGrants.forEach { grant ->
            val key = keys.firstOrNull { it.id == grant.keyId }
            if (canEdit) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(key?.displayName ?: "Unavailable key")
                        TextButton(onClick = { onRevoke(grant.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove permission")
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        key?.displayName ?: "Unavailable key",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun nextUserId(currentUserId: String, users: List<TerminalUser>): String {
    if (users.isEmpty()) return ""
    val index = users.indexOfFirst { it.id == currentUserId }
    return users[(index + 1 + users.size) % users.size].id
}

// The server-authoritative `Terminal` (full shared/API model) and the
// lighter-weight `ManagedTerminalOption` (what the retrieval grid/list and
// KeySlotDemoData's local preview fixtures use) both describe "the cabinet
// this terminal is" but are separate types with no relation — this adapts
// a downloaded server snapshot's Terminal into the option type retrieval
// screens actually consume, so retrievalTerminal always has one consistent
// type regardless of whether it came from the server or the demo fallback.
private fun Terminal.toManagedTerminalOption(): ManagedTerminalOption = ManagedTerminalOption(
    id = id,
    siteId = siteId,
    label = name,
    configuredSlotCount = configuredSlotCount,
)

private fun UserDto.toTerminalUser(): TerminalUser {
    val mappedRole = when (role) {
        UserRole.SUPER_ADMIN -> TerminalUserRole.SUPER_ADMIN
        UserRole.TECHNICIAN -> TerminalUserRole.TECHNICIAN
        UserRole.VENDOR -> TerminalUserRole.VENDOR
        UserRole.REGIONAL_ADMIN -> TerminalUserRole.REGIONAL_ADMIN
        // Unreachable in practice — backend's GET /users excludes GOD_ADMIN entirely
        // (users.js: "never appear in personnel lists"), so this UserDto is never actually
        // GOD_ADMIN; required only for exhaustiveness. Filtered defensively at both call
        // sites below too, same as TerminalAdminStore.applyServerSnapshot's user sync.
        UserRole.GOD_ADMIN -> TerminalUserRole.SUPER_ADMIN
    }
    return TerminalUser(
        id = id,
        displayName = displayName,
        username = email,
        role = mappedRole,
        isPreset = false,
        createdAtEpochMillis = 0L,
        assignedSiteIds = assignedSiteIds,
    )
}

private fun TerminalUserRole.toUserRole(): UserRole = when (this) {
    TerminalUserRole.SUPER_ADMIN -> UserRole.SUPER_ADMIN
    TerminalUserRole.TECHNICIAN -> UserRole.TECHNICIAN
    TerminalUserRole.VENDOR -> UserRole.VENDOR
    TerminalUserRole.REGIONAL_ADMIN -> UserRole.REGIONAL_ADMIN
}

private data class PersonnelSaveResult(val ok: Boolean, val message: String)

private data class PersonnelFeedback(val success: Boolean, val message: String)

private fun friendlyCreateUserError(raw: String?): String {
    val message = raw?.trim().orEmpty()
    if (message.isBlank()) {
        return "Could not add personnel. Check the fields and try again."
    }
    val lower = message.lowercase()
    return when {
        "email" in lower || "invalid_string" in lower || "invalid input" in lower ->
            "Enter a valid email address (for example name@company.com)."
        "password" in lower && ("8" in lower || "min" in lower) ->
            "Password must be at least 8 characters."
        "site" in lower || "unit" in lower || "assigned" in lower ->
            "Select a Unit before adding Technician or Vendor personnel."
        "duplicate" in lower || "already" in lower || "unique" in lower ->
            "That email is already registered."
        else -> message
    }
}

private fun looksLikeEmail(value: String): Boolean {
    val trimmed = value.trim()
    val at = trimmed.indexOf('@')
    if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
    val domain = trimmed.substring(at + 1)
    return domain.contains('.') && domain.none { it.isWhitespace() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrollUserScreen(
    padding: PaddingValues,
    serverLinked: Boolean,
    assignedUnitId: String?,
    unitSites: List<SiteDto>,
    notice: String?,
    onBack: () -> Unit,
    onSave: suspend (String, String, String, TerminalUserRole, String) -> PersonnelSaveResult,
) {
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var temporaryPassword by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(TerminalUserRole.TECHNICIAN) }
    var selectedUnitId by remember(assignedUnitId, unitSites) {
        mutableStateOf(
            assignedUnitId?.takeIf { id -> unitSites.any { it.id == id } }
                ?: unitSites.firstOrNull()?.id.orEmpty(),
        )
    }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<PersonnelFeedback?>(null) }
    var roleMenuExpanded by remember { mutableStateOf(false) }
    var unitPickerOpen by remember { mutableStateOf(false) }
    var unitSearch by remember { mutableStateOf("") }

    val selectedUnit = unitSites.firstOrNull { it.id == selectedUnitId }
    val assignableRoles = listOf(TerminalUserRole.TECHNICIAN, TerminalUserRole.VENDOR)
    val filteredUnits = remember(unitSites, unitSearch) {
        val query = unitSearch.trim()
        if (query.isEmpty()) {
            unitSites.sortedBy { it.name.lowercase() }
        } else {
            unitSites
                .filter {
                    it.name.contains(query, ignoreCase = true) ||
                        (it.city?.contains(query, ignoreCase = true) == true) ||
                        (it.province?.contains(query, ignoreCase = true) == true)
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    fun validateBeforeSave(): String? {
        if (displayName.trim().length < 2) {
            return "Enter a display name with at least 2 characters."
        }
        if (identifier.isBlank()) {
            return if (serverLinked) {
                "Email is required. Enter a work email such as name@company.com."
            } else {
                "Username is required."
            }
        }
        if (serverLinked && !looksLikeEmail(identifier)) {
            return "Enter a valid email address (for example name@company.com)."
        }
        if (!serverLinked && !identifier.trim().matches(Regex("^[A-Za-z0-9._-]{3,40}$"))) {
            return "Username must use 3–40 letters, numbers, dot, underscore or hyphen."
        }
        if (temporaryPassword.length < 8) {
            return "Password must be at least 8 characters."
        }
        if (serverLinked && selectedUnitId.isBlank()) {
            return "Select a Unit before adding personnel. Create a unit on the web portal first, or set Key Cabinet ID in Admin Menu."
        }
        return null
    }

    TerminalPage(padding) {
        BackButton(onBack, label = "Back to Personnel Management")
        HeaderCard(
            title = "Add personnel",
            description = if (serverLinked) {
                "Same as Personnel Management on the web portal: display name, email, role, unit, and password. New records appear on the website immediately."
            } else {
                "Local bootstrap only — sign in with a server account to sync with Personnel Management on the web portal."
            },
        )
        if (serverLinked) {
            SoftAssistChip(
                text = if (unitSites.isNotEmpty()) {
                    "Server linked · choose Unit below (${unitSites.size} units)"
                } else {
                    "Server linked · loading units…"
                },
                success = unitSites.isNotEmpty(),
                attention = unitSites.isEmpty(),
            )
        } else {
            SoftAssistChip(
                text = "Local only — not synced to web",
                attention = true,
            )
        }
        notice?.let { message -> SuperAdminNoticeCard(message) }
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            singleLine = true,
            enabled = !saving,
        )
        OutlinedTextField(
            value = identifier,
            onValueChange = { identifier = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (serverLinked) "Account email" else "Username") },
            supportingText = {
                Text(
                    if (serverLinked) {
                        "Required. Use a full email address (name@company.com)."
                    } else {
                        "Use letters, numbers, dot, underscore or hyphen."
                    },
                )
            },
            singleLine = true,
            enabled = !saving,
            isError = serverLinked && identifier.isNotBlank() && !looksLikeEmail(identifier),
        )
        if (serverLinked) {
            Text("Unit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (unitSites.isEmpty()) {
                SoftAssistChip(
                    text = "No units loaded. Create a unit on the web portal first, or set Key Cabinet ID.",
                    attention = true,
                )
            } else {
                OutlinedButton(
                    onClick = {
                        unitSearch = ""
                        unitPickerOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) {
                    Text(
                        (selectedUnit?.name ?: "Select a unit") +
                            if (unitSites.size > 1) " · search / change" else "",
                    )
                }
            }
        }
        PasswordField(
            if (serverLinked) "Initial password (min 8)" else "Temporary password",
            temporaryPassword,
        ) { temporaryPassword = it }
        Text("Role", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ExposedDropdownMenuBox(
            expanded = roleMenuExpanded,
            onExpandedChange = { if (!saving) roleMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = role.label,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable),
                label = { Text("Role") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleMenuExpanded) },
                enabled = !saving,
            )
            ExposedDropdownMenu(
                expanded = roleMenuExpanded,
                onDismissRequest = { roleMenuExpanded = false },
            ) {
                assignableRoles.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            role = option
                            roleMenuExpanded = false
                        },
                    )
                }
            }
        }
        Button(
            onClick = {
                val validationError = validateBeforeSave()
                if (validationError != null) {
                    feedback = PersonnelFeedback(success = false, message = validationError)
                    return@Button
                }
                saving = true
                scope.launch {
                    val result = onSave(displayName, identifier, temporaryPassword, role, selectedUnitId)
                    feedback = PersonnelFeedback(success = result.ok, message = result.message)
                    if (result.ok) {
                        displayName = ""
                        identifier = ""
                        temporaryPassword = ""
                        role = TerminalUserRole.TECHNICIAN
                    }
                    saving = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            Text(if (saving) "Saving…" else "Add personnel")
        }
        if (temporaryPassword.isNotEmpty() && temporaryPassword.length < 8) {
            SoftAssistChip(
                text = "Password must be at least 8 characters.",
                attention = true,
            )
        }
    }

    feedback?.let { result ->
        AlertDialog(
            onDismissRequest = { feedback = null },
            title = { Text(if (result.success) "Personnel added" else "Could not add personnel") },
            text = { Text(result.message) },
            confirmButton = {
                TextButton(onClick = { feedback = null }) {
                    Text("OK")
                }
            },
        )
    }

    if (unitPickerOpen) {
        AlertDialog(
            onDismissRequest = { unitPickerOpen = false },
            title = { Text("Select unit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = unitSearch,
                        onValueChange = { unitSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search units") },
                        placeholder = { Text("Name, city, or province") },
                        singleLine = true,
                    )
                    Text(
                        "${filteredUnits.size} of ${unitSites.size} units",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (filteredUnits.isEmpty()) {
                            item {
                                Text("No units match that search.")
                            }
                        } else {
                            items(filteredUnits, key = { it.id }) { site ->
                                val selected = site.id == selectedUnitId
                                TextButton(
                                    onClick = {
                                        selectedUnitId = site.id
                                        unitPickerOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        buildString {
                                            append(if (selected) "✓ " else "")
                                            append(site.name)
                                            val place = listOfNotNull(site.city, site.province)
                                                .filter { it.isNotBlank() }
                                                .joinToString(", ")
                                            if (place.isNotBlank()) append(" · ").append(place)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { unitPickerOpen = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun HardwareControlScreen(
    padding: PaddingValues,
    hardwareState: CabinetHardwareState,
    notice: String?,
    onBack: () -> Unit,
    onConnect: (String, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
    onCheckDoor: () -> Unit,
    onOpenDoor: () -> Unit,
    onNodeStatus: (Int) -> Unit,
    onReadFob: (Int) -> Unit,
    onBlueLight: (Int, Boolean) -> Unit,
    onRedLight: (Int, Boolean) -> Unit,
    onEngage: (Int) -> Unit,
    onRelease: (Int) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(HardwarePage.STATUS) }
    var portPath by remember { mutableStateOf(hardwareState.portPath) }
    var baudRateText by remember { mutableStateOf(hardwareState.baudRate.toString()) }
    var boxAddressText by remember { mutableStateOf(hardwareState.boxAddress.toString()) }
    var nodeAddressText by remember { mutableStateOf("0") }
    val baudRate = baudRateText.toIntOrNull()
    val boxAddress = boxAddressText.toIntOrNull()
    val nodeAddress = nodeAddressText.toIntOrNull()
    val validConnection = portPath.isNotBlank() && baudRate != null && baudRate > 0 &&
            boxAddress != null && boxAddress in 1..255
    val validNode = nodeAddress != null && nodeAddress in 0..MAX_KEY_NODE_ADDRESS

    TerminalPage(padding) {
        BackButton(onBack)
        Text(
            text = "Cabinet hardware",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Status shows connection health. Controls send live cabinet commands.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        HardwarePageSegment(
            statusSelected = page == HardwarePage.STATUS,
            onStatus = { page = HardwarePage.STATUS },
            onControls = { page = HardwarePage.CONTROLS },
        )

        when (page) {
            HardwarePage.STATUS -> {
                HardwareStatusPage(
                    hardwareState = hardwareState,
                    portPath = portPath,
                    onPortPathChange = { portPath = it },
                    baudRateText = baudRateText,
                    onBaudRateChange = { baudRateText = it },
                    boxAddressText = boxAddressText,
                    onBoxAddressChange = { boxAddressText = it },
                    canConnect = validConnection && !hardwareState.busy,
                    onConnect = {
                        onConnect(portPath.trim(), requireNotNull(baudRate), requireNotNull(boxAddress))
                    },
                    onDisconnect = onDisconnect,
                )
            }

            HardwarePage.CONTROLS -> {
                SoftAssistChip(
                    text = if (hardwareState.connected) "Connected" else "Disconnected — open Status to connect",
                    success = hardwareState.connected,
                    attention = !hardwareState.connected,
                )
                if (hardwareState.busy) {
                    // Readability pass: 28dp -> 34dp (x1.2).
                    CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                }
                Text(
                    text = hardwareState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!hardwareState.connected) {
                    SoftCard(contentPadding = 16.dp) {
                        Text(
                            text = "Connect the cabinet on the Status page before sending commands.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SoftPrimaryButton(
                            text = "Go to Status",
                            onClick = { page = HardwarePage.STATUS },
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !hardwareState.busy,
                    ) {
                        Text("Disconnect cabinet")
                    }
                    Button(
                        onClick = onCheckDoor,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !hardwareState.busy,
                    ) {
                        Text("Check door status (0x22)")
                    }
                    OutlinedButton(
                        onClick = onOpenDoor,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !hardwareState.busy,
                    ) {
                        Text("Eject cabinet door (0x23)")
                    }

                    Text("Selected raw node", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = nodeAddressText,
                        onValueChange = { nodeAddressText = it.filter { character -> character.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Raw Node Address (0–$MAX_KEY_NODE_ADDRESS)") },
                        supportingText = {
                            Text(
                                "Use the configured protocol address exactly. Door commands always send node 0 internally.",
                            )
                        },
                        singleLine = true,
                        isError = nodeAddressText.isNotBlank() && !validNode,
                    )
                    if (validNode) {
                        Button(
                            onClick = { onNodeStatus(requireNotNull(nodeAddress)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Read node state (0x17)")
                        }
                        OutlinedButton(
                            onClick = { onReadFob(requireNotNull(nodeAddress)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Read fob at node (0x15)")
                        }
                        OutlinedButton(
                            onClick = { onBlueLight(requireNotNull(nodeAddress), true) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Blue light ON (0x11)")
                        }
                        OutlinedButton(
                            onClick = { onBlueLight(requireNotNull(nodeAddress), false) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Blue light OFF (0x12)")
                        }
                        OutlinedButton(
                            onClick = { onRedLight(requireNotNull(nodeAddress), true) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Red light ON (0x19)")
                        }
                        OutlinedButton(
                            onClick = { onRedLight(requireNotNull(nodeAddress), false) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Red light OFF (0x1A)")
                        }
                        OutlinedButton(
                            onClick = { onEngage(requireNotNull(nodeAddress)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Engage electromagnet (0x13)")
                        }
                        OutlinedButton(
                            onClick = { onRelease(requireNotNull(nodeAddress)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !hardwareState.busy,
                        ) {
                            Text("Release electromagnet (0x14)")
                        }
                    }
                }
            }
        }
    }
}

private enum class HardwarePage {
    STATUS,
    CONTROLS,
}

@Composable
private fun HardwarePageSegment(
    statusSelected: Boolean,
    onStatus: () -> Unit,
    onControls: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HardwareSegmentOption("Status", statusSelected, onStatus, Modifier.weight(1f))
        HardwareSegmentOption("Controls", !statusSelected, onControls, Modifier.weight(1f))
    }
}

@Composable
private fun HardwareSegmentOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun TerminalPage(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 920.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun HeaderCard(
    title: String,
    description: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Box(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title + "\n\n" + description,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun SuperAdminNoticeCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun DashboardMetric(
    title: String,
    value: String,
    description: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title + "\n" + value + "\n" + description,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
internal fun BackButton(
    onBack: () -> Unit,
    enabled: Boolean = true,
    // Role-neutral: shared by Super Admin and Regional Admin admin-shell screens.
    label: String = "Back to Admin dashboard",
) {
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Text(label)
    }
}

@Composable
private fun HardwareStatusCard(state: CabinetHardwareState) {
    // Admin device health indicator — same status-ring pattern as every
    // other hardware/lifecycle state, not a one-off color check.
    StatusRingCard(tone = if (state.connected) StatusTone.NORMAL else StatusTone.INACTIVE) {
        Text(
            text = "Cabinet: " + if (state.connected) "Connected" else "Disconnected",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Port: " + state.portPath + " @ " + state.baudRate + " · Box " + state.boxAddress,
            style = MaterialTheme.typography.bodySmall.readout(),
        )
        if (state.keyReturnMonitoring) {
            Text("Key return monitor: active", style = MaterialTheme.typography.bodySmall)
        }
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        state.doorStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall.readout()) }
        state.nodeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall.readout()) }
    }
    if (state.busy) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CapturedFobCard(capturedFob: CapturedFob?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(16.dp)) {
            Text(
                if (capturedFob == null) {
                    "Physical fob: not read yet."
                } else {
                    "Physical fob: captured from Box " + capturedFob.boxAddress +
                            ", Node " + capturedFob.nodeAddress + ". UID hidden."
                },
            )
        }
    }
}

private enum class SuperAdminRoute {
    LOGIN,
    CHANGE_PASSWORD,
    /** Admin-tier (Super Admin / Regional Admin) post-login landing point — see
     * [TerminalLandingChoiceScreen]. Technician/Vendor never reach this; they route straight to
     * [KEY_MENU] as before this route existed. */
    LANDING_CHOICE,
    DASHBOARD,
    /** Personnel Management list — see [PersonnelListScreen]. */
    PERSONNEL_LIST,
    /** Personnel Management per-user detail — see [PersonnelDetailScreen]. Reads [selectedPersonnelId]. */
    PERSONNEL_DETAIL,
    ENROLL_USER,
    /** The dashboard's key-fob workflow — operates on real, server-synced KeySlot/ManagedKey
     * records. See [KeyAttachmentScreen]. Replaces the old TerminalKey-based Guided Key
     * Enrollment screen, fully removed (not just retired) once its one remaining reachable
     * feature — Key-card NFC enrollment's entry point — moved onto this screen instead. */
    KEY_ATTACHMENT,
    CARD_ENROLLMENT,
    FINGERPRINT_ENROLLMENT,
    FACE_ENROLLMENT,
    ACCESS_GRANTS,
    KEY_RETRIEVAL,
    /** Technician/Vendor's post-login landing screen — see [TerminalKeyMenuScreen]. */
    KEY_MENU,
    ADMIN_MENU,
    HARDWARE,
    /** Phase 7, item 15 — see [TerminalOfficeHoursScreen]. */
    OFFICE_HOURS,
    /** Phase 7, item 16 — see [TerminalVendorPasskeyScreen]. */
    VENDOR_PASSKEY,
}

private data class CapturedFob(
    val boxAddress: Int,
    val nodeAddress: Int,
    /** Kept only in memory until the key record is saved as a one-way hash. */
    val rawUid: String,
)

private data class PendingPhysicalAction(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

// Return Flow rewrite: Section 3 (key return) state — AwaitingCertification/Unlocking/
// AwaitingInsertion/Waiting/NodeOutcomeMessage plus the ReturnSessionController class that owns
// all the transition logic — has moved to ui/returnflow/ReturnSessionController.kt. See that
// file for the full design doc; not duplicated here.

private const val LOG_TAG = "TerminalAdminApp"

/** Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1) in-progress state; see `TerminalKeyTakeScreen`. */
private data class TakeFlow(val key: ManagedKey, val slot: KeySlot, val checkoutDeadline: CheckoutDeadlineChoice)

/**
 * Mandatory-manual-return-time rework: every single/multi-key take session is unconditionally
 * paused here for the operator's return-time decision (the analog clock or Emergency). `resume`
 * is whichever continuation closure applies (single-key take vs. the multi-key red-light
 * sequence) — carries the already-resolved key/slot(s) so `TerminalCloseToDeadlineScreen` doesn't
 * need to know which mode triggered it.
 */
private data class PendingCheckoutDecision(
    val resume: (CheckoutDeadlineChoice) -> Unit,
)

/**
 * Key Menu multi-key sequential Take Flow: the confirmed, ordered queue of keys to take one
 * at a time. [currentIndex] only ever advances — there is no "go back" once a node's take has
 * completed (succeeded, failed, or was abandoned), matching the single-key flow's own
 * one-way completion. Each node in [items] is rendered through the exact same
 * `TerminalKeyTakeScreen` the single-key flow uses, just re-keyed per node as the queue
 * advances, so all of that screen's existing per-key safeguards (Take Warning Time,
 * door-left-open logging, abandonment) apply unchanged to whichever node is current.
 */
private data class MultiKeyTakeQueue(
    val items: List<Pair<ManagedKey, KeySlot>>,
    val checkoutDeadline: CheckoutDeadlineChoice,
    val currentIndex: Int = 0,
) {
    val current: Pair<ManagedKey, KeySlot> get() = items[currentIndex]

    /** Null once the last node in the queue has completed — the caller treats that as "done". */
    fun advanced(): MultiKeyTakeQueue? =
        if (currentIndex + 1 < items.size) copy(currentIndex = currentIndex + 1) else null
}