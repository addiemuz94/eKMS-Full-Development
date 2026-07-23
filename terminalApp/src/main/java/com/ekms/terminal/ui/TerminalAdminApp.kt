package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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
import com.ekms.shared.protocol.KeyCabinetLink.Companion.MAX_KEY_NODE_ADDRESS
import com.ekms.terminal.data.AuthOutcome
import com.ekms.terminal.data.StoreResult
import com.ekms.terminal.data.TerminalAccessGrant
import com.ekms.terminal.data.TerminalAdminSnapshot
import com.ekms.terminal.data.TerminalAdminStore
import com.ekms.terminal.data.TerminalApiClient
import com.ekms.terminal.data.TerminalKey
import com.ekms.terminal.data.TerminalSession
import com.ekms.terminal.data.TerminalServerCache
import com.ekms.terminal.data.TerminalSyncCoordinator
import com.ekms.terminal.data.TerminalSyncOutbox
import com.ekms.terminal.data.TerminalUser
import com.ekms.terminal.data.TerminalUserRole
import com.ekms.terminal.hardware.CabinetHardwareController
import com.ekms.terminal.hardware.CabinetHardwareState
import com.ekms.terminal.hardware.EncryptedUidEnrollmentStore
import com.ekms.terminal.hardware.PublicCardReaderController
import com.ekms.terminal.hardware.TerminalNfcReaderController
import com.ekms.terminal.hardware.TerminalNfcReaderState
import com.ekms.terminal.hardware.UidEnrollmentResult
import com.ekms.terminal.ui.theme.DataReadoutTextStyle
import com.ekms.terminal.ui.theme.EkmsTerminalTheme
import com.ekms.terminal.ui.theme.StatusTone
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.provider.Settings

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

    var route by remember { mutableStateOf(SuperAdminRoute.LOGIN) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
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
    var takenKeyIds by remember { mutableStateOf(emptySet<String>()) }
    var takeFlow by remember { mutableStateOf<TakeFlow?>(null) }
    var returnFlow by remember { mutableStateOf<ReturnFlow?>(null) }
    var passwordChangeReturnRoute by remember { mutableStateOf(SuperAdminRoute.DASHBOARD) }

    // Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1):
    // the availability check below (no slot -> not selectable) is the same
    // one the grid already enforces before ever calling this; entering
    // takeFlow hands off to a dedicated full-screen takeover, same pattern
    // as Section 3's return flow, so the grid is not shown again until it
    // completes, fails, or is abandoned.
    fun takeKey(key: ManagedKey) {
        val slot = retrievalSlots.firstOrNull { it.managedKeyId == key.id }
        if (slot == null) {
            notice = "${key.displayName} is not assigned to a cabinet slot."
            return
        }
        takeFlow = TakeFlow(key, slot)
    }

    fun handleTakeFlowOutcome(outcome: TakeFlowOutcome) {
        val actorUserId = session?.userId
        when (outcome) {
            is TakeFlowOutcome.Success ->
                store.logEvent(AuditEventType.KEY_TAKEN, actorUserId, RecordType.KEY, outcome.key.id)

            is TakeFlowOutcome.Failed ->
                store.logEvent(AuditEventType.KEY_TAKE_FAILED, actorUserId, RecordType.KEY, outcome.key.id, outcome.message)

            is TakeFlowOutcome.Abandoned ->
                store.logEvent(AuditEventType.KEY_TAKE_ABANDONED, actorUserId, RecordType.KEY, outcome.key.id)

            is TakeFlowOutcome.DoorLeftOpen ->
                store.logEvent(AuditEventType.KEY_TAKE_DOOR_LEFT_OPEN, actorUserId, RecordType.KEY, outcome.key.id)
        }
    }

    // TerminalKey (terminal-local, embeds box/node address) has no shared-
    // model equivalent yet — see docs/Backend_Integration_Handover.md's
    // "two incompatible key schemas" gap (ManagedKey+KeySlot, shared/demo,
    // vs TerminalKey, terminal-local). Synthesizes the pair the existing
    // return-flow plumbing expects from the one real card-swipe case that
    // needs it, rather than reworking that plumbing's type wholesale.
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

    fun resolveDoorOpenState(
        matchedKey: ManagedKey?,
        matchedSlot: KeySlot?,
        abandonAtEpochMillis: Long?,
    ): ReturnFlow.DoorOpen {
        // A real card-UID match (matchedKey/matchedSlot) is authoritative and
        // always preferred; the "only key currently taken" heuristic (no
        // deadline — never a timed flow) only fires for the login screen's
        // UID-less manual key-card tap.
        if (matchedKey != null && matchedSlot != null) {
            return ReturnFlow.DoorOpen(matchedKey, matchedSlot, abandonAtEpochMillis)
        }
        val key = resolveReturningKey(takenKeyIds, retrievalKeys)
        val slot = key?.let { returningKey -> retrievalSlots.firstOrNull { it.managedKeyId == returningKey.id } }
        return ReturnFlow.DoorOpen(key, slot, null)
    }

    // Key Return Flow (CLAUDE.md "Terminal App UX Baseline (Production)"
    // §2): the 20s no-insert abandonment ceiling is computed once, here, at
    // the moment of the original card swipe — it is threaded unchanged
    // through AwaitingCertification and into DoorOpen, so a slow
    // certification login eats into the same window rather than getting
    // its own separate clock.
    fun startKeyCardReturn(matchedKey: ManagedKey? = null, matchedSlot: KeySlot? = null) {
        val abandonAtEpochMillis = if (matchedKey != null && matchedSlot != null) {
            System.currentTimeMillis() + CabinetHardwareController.RETURN_FLOW_ABANDONMENT_TIMEOUT_MILLIS
        } else {
            null
        }
        // "Key Return Certification" is Section 4's Admin Menu toggle, persisted
        // in TerminalAdminStore; Section 3's return flow only reacts to it.
        returnFlow = if (snapshot.cabinetSettings.keyReturnCertificationEnabled) {
            ReturnFlow.AwaitingCertification(matchedKey, matchedSlot, abandonAtEpochMillis)
        } else {
            resolveDoorOpenState(matchedKey, matchedSlot, abandonAtEpochMillis)
        }
    }

    fun handleReturnFlowOutcome(outcome: ReturnFlowOutcome) {
        val actorUserId = session?.userId
        when (outcome) {
            is ReturnFlowOutcome.Success -> {
                outcome.key?.let { returnedKey -> takenKeyIds = takenKeyIds - returnedKey.id }
                store.logEvent(AuditEventType.KEY_RETURNED, actorUserId, RecordType.KEY, outcome.key?.id)
            }

            is ReturnFlowOutcome.Failed ->
                store.logEvent(
                    AuditEventType.KEY_RETURN_FAILED,
                    actorUserId,
                    RecordType.KEY,
                    outcome.key?.id,
                    outcome.message,
                )

            // Deliberate asymmetry with the Key Take Flow's abandonment: a
            // Return abandonment additionally implies a two-party alert (the
            // terminal user and Super Admin), not just a log entry — see
            // CLAUDE.md "Terminal App UX Baseline (Production)" §2. The
            // actual two-party delivery mechanism (mobileApp/webApp) is the
            // same deferred follow-up as the Take Flow's standing-alert UI;
            // this records the intent in the outbox record's detail so a
            // future consumer knows to alert both, not just Super Admin.
            is ReturnFlowOutcome.Abandoned ->
                store.logEvent(
                    AuditEventType.KEY_RETURN_ABANDONED,
                    actorUserId,
                    RecordType.KEY,
                    outcome.key?.id,
                    "Alert both the terminal user and Super Admin.",
                )

            is ReturnFlowOutcome.DoorLeftOpen ->
                store.logEvent(AuditEventType.KEY_RETURN_DOOR_LEFT_OPEN, actorUserId, RecordType.KEY, outcome.key?.id)
        }
    }

    // Section 9's public card-swipe reader — a wholly separate device/protocol
    // from the node-level 0x15/0x17 card reads (section 9.1/9.8/10.4: "must
    // not be mixed"). A scanned UID is meaningless on its own — personnel
    // cards and key cards share the same physical medium and UID space, so
    // which action a scan means can only be decided by looking it up against
    // both encrypted stores, never assumed from which screen is showing.
    val cardReaderController = remember {
        PublicCardReaderController(
            onStateChanged = {},
            onCardDetected = { rawUid ->
                val matchedUserId = personnelCardStore.recordIdFor(rawUid)
                val matchedKeyId = keyCardStore.recordIdFor(rawUid)
                when (val match = CardUidResolver.resolve(matchedUserId, matchedKeyId)) {
                    is CardUidMatch.User -> when (val result = store.authenticateByUserId(match.userId)) {
                        is StoreResult.Success -> {
                            session = result.value
                            notice = null
                            route = when {
                                result.value.requiresPasswordChange -> SuperAdminRoute.CHANGE_PASSWORD
                                result.value.isSuperAdmin -> SuperAdminRoute.DASHBOARD
                                else -> SuperAdminRoute.KEY_RETRIEVAL
                            }
                        }

                        is StoreResult.Error -> notice = result.message
                    }

                    is CardUidMatch.Key -> {
                        val terminalKey = snapshot.keys.firstOrNull { it.id == match.keyId }
                        if (terminalKey != null) {
                            val (matchedKey, matchedSlot) = managedKeyAndSlotFor(terminalKey)
                            startKeyCardReturn(matchedKey, matchedSlot)
                        } else {
                            notice = "This card's enrolled key no longer exists."
                        }
                    }

                    is CardUidMatch.Ambiguous ->
                        notice = "This card is enrolled to both a person and a key. Re-enroll it before use."

                    CardUidMatch.NoMatch -> notice = "Unrecognized card. It is not enrolled to a person or a key."
                }
            },
        )
    }
    val isIdleAtLogin = route == SuperAdminRoute.LOGIN && returnFlow == null

    // Key Return Flow (CLAUDE.md "Terminal App UX Baseline (Production)"
    // §2): races the same 20s-from-swipe deadline while a Key Return
    // Certification login is pending. Cancelled automatically (Compose
    // key-change semantics) the moment `returnFlow` moves past this exact
    // AwaitingCertification instance — login success, login retry, or the
    // user backing out — so reaching the code after delay() always means
    // this specific swipe's certification never completed in time. No
    // hardware was ever engaged at this stage, so there is nothing to
    // re-lock or turn off — only the log entry and clearing the flow.
    LaunchedEffect(returnFlow) {
        val flow = returnFlow
        if (flow is ReturnFlow.AwaitingCertification && flow.abandonAtEpochMillis != null) {
            val remainingMillis = flow.abandonAtEpochMillis - System.currentTimeMillis()
            if (remainingMillis > 0) delay(remainingMillis)
            store.logEvent(
                AuditEventType.KEY_RETURN_ABANDONED,
                session?.userId,
                RecordType.KEY,
                flow.matchedKey?.id,
                "Key Return Certification did not complete before the 20s ceiling. Alert both the terminal user and Super Admin.",
            )
            returnFlow = null
        }
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
        }
    }

    fun signOut() {
        hardwareController.disconnect()
        apiClient.clearSession()
        session = null
        capturedFob = null
        notice = null
        route = SuperAdminRoute.LOGIN
    }

    fun applyAuthSession(outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.Server -> {
                session = outcome.session
                notice = "Signed in to ${apiClient.baseUrl}."
                route = when {
                    outcome.session.requiresPasswordChange -> SuperAdminRoute.CHANGE_PASSWORD
                    outcome.session.isSuperAdmin -> SuperAdminRoute.DASHBOARD
                    else -> SuperAdminRoute.KEY_RETRIEVAL
                }
            }

            is AuthOutcome.Local -> {
                session = outcome.session
                notice = outcome.serverWarning
                route = when {
                    outcome.session.requiresPasswordChange -> SuperAdminRoute.CHANGE_PASSWORD
                    outcome.session.isSuperAdmin -> SuperAdminRoute.DASHBOARD
                    else -> SuperAdminRoute.KEY_RETRIEVAL
                }
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
        if (activeSession?.isSuperAdmin == true) {
            notice = null
            route = routeToOpen
        } else {
            notice = "Only the signed-in Super Admin may open this area."
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
        onDispose { hardwareController.close() }
    }

    // Section 10.3-10.4: the reader monitor starts automatically once idle at
    // the login screen and stops automatically otherwise — including when
    // this composable (the whole app) leaves composition, i.e. app exit.
    LaunchedEffect(isIdleAtLogin) {
        if (isIdleAtLogin) cardReaderController.start() else cardReaderController.stop()
    }
    DisposableEffect(cardReaderController) {
        onDispose { cardReaderController.close() }
    }

    EkmsTerminalTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                SuperAdminRoute.LOGIN -> "eKMS Terminal"
                                SuperAdminRoute.CHANGE_PASSWORD -> "Secure Super Admin account"
                                else -> "eKMS Terminal · " + (session?.displayName ?: "Session")
                            },
                        )
                    },
                )
            },
        ) { padding ->
            val activeReturnFlow = returnFlow
            when {
                // Section 3 (key return) is reached directly from the login/home
                // screen by a key-card swipe, never through a menu — so it takes
                // over here regardless of `route`, and returns to whatever route
                // was already showing once it completes.
                activeReturnFlow is ReturnFlow.AwaitingCertification -> TerminalLoginScreen(
                    padding = padding,
                    onAccountLogin = { username, password ->
                        when (val result = store.authenticate(username, password)) {
                            is StoreResult.Success -> returnFlow = resolveDoorOpenState(
                                activeReturnFlow.matchedKey,
                                activeReturnFlow.matchedSlot,
                                activeReturnFlow.abandonAtEpochMillis,
                            )
                            is StoreResult.Error -> returnFlow = ReturnFlow.AwaitingCertification(
                                matchedKey = activeReturnFlow.matchedKey,
                                matchedSlot = activeReturnFlow.matchedSlot,
                                abandonAtEpochMillis = activeReturnFlow.abandonAtEpochMillis,
                                loginError = result.message,
                            )
                        }
                    },
                    loginError = activeReturnFlow.loginError,
                )

                activeReturnFlow is ReturnFlow.DoorOpen -> TerminalKeyReturnScreen(
                    padding = padding,
                    key = activeReturnFlow.matchedKey,
                    slot = activeReturnFlow.matchedSlot,
                    abandonAtEpochMillis = activeReturnFlow.abandonAtEpochMillis,
                    doorCloseWarningTimeSeconds = snapshot.cabinetSettings.doorCloseWarningTimeSeconds,
                    videoRecordingEnabled = snapshot.cabinetSettings.returnKeyVideoEnabled,
                    onBeginReturnFlow = hardwareController::beginKeyReturnFlow,
                    onPollInsertion = hardwareController::pollForKeyInsertion,
                    onWaitForDoorClose = hardwareController::waitForDoorCloseAfterReturn,
                    onEvent = ::handleReturnFlowOutcome,
                    onCompleted = { returnFlow = null },
                )

                else -> when (route) {
                SuperAdminRoute.LOGIN -> TerminalLoginScreen(
                    padding = padding,
                    onAccountLogin = { username, password -> runServerLogin(username, password) },
                    loginError = notice,
                    onKeyCardSwiped = { startKeyCardReturn() },
                )

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
                                    notice = "Super Admin password changed. You can now enroll users and keys."
                                    route = passwordChangeReturnRoute
                                }

                                is StoreResult.Error -> notice = result.message
                            }
                        }
                    },
                    notice = notice,
                )

                SuperAdminRoute.DASHBOARD -> {
                    val activeSession = session
                    if (activeSession?.isSuperAdmin != true) {
                        LaunchedEffect(Unit) {
                            route = SuperAdminRoute.LOGIN
                        }
                        TerminalPage(padding) {
                            SuperAdminNoticeCard("Your Super Admin session has ended. Returning to sign-in…")
                        }
                    } else {
                        SuperAdminDashboardScreen(
                            padding = padding,
                            snapshot = snapshot,
                            hardwareState = hardwareState,
                            notice = notice,
                            onEnrollUser = { openAdmin(SuperAdminRoute.ENROLL_USER) },
                            onEnrollKey = { openAdmin(SuperAdminRoute.ENROLL_KEY) },
                            onOpenCardEnrollment = { openAdmin(SuperAdminRoute.CARD_ENROLLMENT) },
                            onOpenAccessGrants = { openAdmin(SuperAdminRoute.ACCESS_GRANTS) },
                            onOpenKeyRetrieval = { openAdmin(SuperAdminRoute.KEY_RETRIEVAL) },
                            onOpenAdminMenu = { openAdmin(SuperAdminRoute.ADMIN_MENU) },
                            onOpenHardware = { openAdmin(SuperAdminRoute.HARDWARE) },
                            onSignOut = ::signOut,
                        )
                    }
                }

                SuperAdminRoute.ENROLL_USER -> EnrollUserScreen(
                    padding = padding,
                    users = snapshot.users,
                    notice = notice,
                    onBack = { route = SuperAdminRoute.DASHBOARD },
                    onSave = { displayName, username, password, role ->
                        when (val result = store.createUser(displayName, username, password, role)) {
                            is StoreResult.Success -> {
                                enqueueChange(
                                    RecordType.USER,
                                    result.value.id,
                                    """{"displayName":"${result.value.displayName}","username":"${result.value.username}","role":"${result.value.role.name}"}""",
                                )
                                refreshSnapshot()
                                notice = result.value.displayName + " was enrolled as " + result.value.role.label + "."
                                true
                            }

                            is StoreResult.Error -> {
                                notice = result.message
                                false
                            }
                        }
                    },
                )

                SuperAdminRoute.ENROLL_KEY -> EnrollKeyScreen(
                    padding = padding,
                    keys = snapshot.keys,
                    hardwareState = hardwareState,
                    notice = notice,
                    onBack = {
                        hardwareController.stopMonitoring()
                        route = SuperAdminRoute.DASHBOARD
                    },
                    onOpenEnrollmentSession = hardwareController::openKeyEnrollmentSession,
                    onPrepareKeyFob = hardwareController::prepareKeyFobForEnrollment,
                    onSaveKey = { displayName, nodeAddress, rawFobUid ->
                        val result = store.createKey(
                            displayName = displayName,
                            boxAddress = hardwareState.boxAddress,
                            nodeAddress = nodeAddress,
                            rawFobUid = rawFobUid,
                        )
                        if (result is StoreResult.Success) {
                            enqueueChange(
                                RecordType.KEY,
                                result.value.id,
                                """{"displayName":"${result.value.displayName}","nodeAddress":${result.value.nodeAddress},"boxAddress":${result.value.boxAddress}}""",
                            )
                            refreshSnapshot()
                        }
                        result
                    },
                    onMonitorReturnedFob = hardwareController::waitForReturnedKeyFob,
                    onStopMonitoring = hardwareController::stopMonitoring,
                )

                SuperAdminRoute.CARD_ENROLLMENT -> CardEnrollmentScreen(
                    padding = padding,
                    users = snapshot.users,
                    keys = snapshot.keys,
                    notice = notice,
                    onBack = { route = SuperAdminRoute.DASHBOARD },
                    onEnrollPersonnelCard = { userId, rawUid ->
                        if (keyCardStore.isEnrolled(rawUid)) {
                            UidEnrollmentResult.AlreadyAssigned
                        } else {
                            personnelCardStore.enroll(userId, rawUid, System.currentTimeMillis())
                        }
                    },
                    onEnrollKeyCard = { keyId, rawUid ->
                        if (personnelCardStore.isEnrolled(rawUid)) {
                            UidEnrollmentResult.AlreadyAssigned
                        } else {
                            keyCardStore.enroll(keyId, rawUid, System.currentTimeMillis())
                        }
                    },
                    onRevokePersonnelCard = personnelCardStore::revoke,
                    onRevokeKeyCard = keyCardStore::revoke,
                )

                SuperAdminRoute.ACCESS_GRANTS -> AccessGrantsScreen(
                    padding = padding,
                    users = snapshot.users.filterNot { it.isPreset },
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
                        hardwareController.disconnect()
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
                            onBeginTake = hardwareController::beginKeyTake,
                            onPollRemoval = hardwareController::pollForKeyRemoval,
                            onWaitForDoorClose = hardwareController::waitForDoorCloseAfterTake,
                            onKeyRemoved = { takenKeyIds = takenKeyIds + activeTakeFlow.key.id },
                            onEvent = ::handleTakeFlowOutcome,
                            onCompleted = { takeFlow = null },
                        )
                    } else {
                        TerminalKeyRetrievalScreen(
                            padding = padding,
                            terminal = retrievalTerminal,
                            keys = retrievalKeys,
                            slots = retrievalSlots,
                            takenKeyIds = takenKeyIds,
                            videoRecordingEnabled = snapshot.cabinetSettings.keyRetrievalVideoEnabled,
                            backLabel = if (activeSession?.isSuperAdmin == true) "Back to dashboard" else "Sign out",
                            onBack = {
                                if (activeSession?.isSuperAdmin == true) {
                                    route = SuperAdminRoute.DASHBOARD
                                } else {
                                    signOut()
                                }
                            },
                            onTakeKey = ::takeKey,
                        )
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
                    Button(
                        onClick = {
                            action.onConfirm()
                            pendingPhysicalAction = null
                        },
                    ) {
                        Text("Confirm physical action")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPhysicalAction = null }) {
                        Text("Cancel")
                    }
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
            description = "This is required once before the Super Admin can enroll users, keys or send cabinet commands.",
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
    snapshot: TerminalAdminSnapshot,
    hardwareState: CabinetHardwareState,
    notice: String?,
    onEnrollUser: () -> Unit,
    onEnrollKey: () -> Unit,
    onOpenCardEnrollment: () -> Unit,
    onOpenAccessGrants: () -> Unit,
    onOpenKeyRetrieval: () -> Unit,
    onOpenAdminMenu: () -> Unit,
    onOpenHardware: () -> Unit,
    onSignOut: () -> Unit,
) {
    TerminalPage(padding) {
        HeaderCard(
            title = "Super Admin dashboard",
            description = "You are the only pre-provisioned account. Enroll users and keys from here, then use the protected hardware controls for actual cabinet actions.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        DashboardMetric("Users", snapshot.users.size.toString(), "1 preset Super Admin · " + (snapshot.users.size - 1) + " enrolled")
        DashboardMetric("Keys", snapshot.keys.size.toString(), "All keys must be enrolled from a verified cabinet node")
        DashboardMetric("Access grants", snapshot.accessGrants.size.toString(), "Exact user-to-key bindings")
        StatusRingCard(tone = if (hardwareState.connected) StatusTone.NORMAL else StatusTone.INACTIVE) {
            Text("Cabinet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (hardwareState.connected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(hardwareState.message, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onEnrollUser, modifier = Modifier.fillMaxWidth()) {
            Text("Enroll new user")
        }
        Button(onClick = onEnrollKey, modifier = Modifier.fillMaxWidth()) {
            Text("Enroll new key")
        }
        Button(onClick = onOpenCardEnrollment, modifier = Modifier.fillMaxWidth()) {
            Text("Card enrolment")
        }
        Button(onClick = onOpenAccessGrants, modifier = Modifier.fillMaxWidth()) {
            Text("Manage access grants")
        }
        Button(onClick = onOpenKeyRetrieval, modifier = Modifier.fillMaxWidth()) {
            Text("Take keys")
        }
        OutlinedButton(onClick = onOpenAdminMenu, modifier = Modifier.fillMaxWidth()) {
            Text("Admin Menu")
        }
        OutlinedButton(onClick = onOpenHardware, modifier = Modifier.fillMaxWidth()) {
            Text("Cabinet hardware control")
        }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun AccessGrantsScreen(
    padding: PaddingValues,
    users: List<TerminalUser>,
    keys: List<TerminalKey>,
    grants: List<TerminalAccessGrant>,
    notice: String?,
    onBack: () -> Unit,
    onGrant: (String, String) -> Unit,
    onRevoke: (String) -> Unit,
) {
    var selectedUserId by remember(users) { mutableStateOf(users.firstOrNull()?.id.orEmpty()) }
    val selectedUser = users.firstOrNull { it.id == selectedUserId }
    val userGrants = grants.filter { it.userId == selectedUserId }
    val grantedKeyIds = userGrants.map { it.keyId }.toSet()
    val availableKeys = keys.filter { it.id !in grantedKeyIds }

    TerminalPage(padding) {
        BackButton(onBack)
        HeaderCard(
            title = "Access grants",
            description = "Bind only the exact keys an enrolled user may take. A grant here is separate from the user's own record, matching the shared AccessGrant model used by the Website.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        if (users.isEmpty()) {
            Text("Enroll a Technician or Vendor user before creating an access grant.")
            return@TerminalPage
        }

        Text("Selected user", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { selectedUserId = nextUserId(selectedUserId, users) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text((selectedUser?.let { it.displayName + " · " + it.role.label } ?: "Select a user") + " · change")
        }

        Text("Unauthorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (keys.isEmpty()) {
            Text("No key has been enrolled yet.")
        } else if (availableKeys.isEmpty()) {
            Text("Every enrolled key is already granted to this user.")
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

        Text("Authorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (userGrants.isEmpty()) {
            Text("No exact key permission is currently assigned to this user.")
        }
        userGrants.forEach { grant ->
            val key = keys.firstOrNull { it.id == grant.keyId }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(key?.displayName ?: "Unavailable key")
                    TextButton(onClick = { onRevoke(grant.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove permission")
                    }
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

@Composable
private fun EnrollUserScreen(
    padding: PaddingValues,
    users: List<TerminalUser>,
    notice: String?,
    onBack: () -> Unit,
    onSave: (String, String, String, TerminalUserRole) -> Boolean,
) {
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var temporaryPassword by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(TerminalUserRole.TECHNICIAN) }

    TerminalPage(padding) {
        BackButton(onBack)
        HeaderCard(
            title = "Enroll user",
            description = "Only the Super Admin can create accounts. No other account is preset. " +
                    "Credential enrollment (NFC, fingerprint, Digital Key and Face) is attached to these user records in the next access-control step.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Full name") },
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            supportingText = { Text("Use letters, numbers, dot, underscore or hyphen.") },
            singleLine = true,
        )
        PasswordField("Temporary password", temporaryPassword) { temporaryPassword = it }
        Text("Role", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = {
                role = if (role == TerminalUserRole.TECHNICIAN) {
                    TerminalUserRole.VENDOR
                } else {
                    TerminalUserRole.TECHNICIAN
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Selected: " + role.label + " · change")
        }
        Button(
            onClick = {
                if (onSave(displayName, username, temporaryPassword, role)) {
                    displayName = ""
                    username = ""
                    temporaryPassword = ""
                    role = TerminalUserRole.TECHNICIAN
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = displayName.isNotBlank() && username.isNotBlank() && temporaryPassword.length >= 8,
        ) {
            Text("Create user")
        }

        Text("Enrolled accounts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        users.forEach { user ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Text(
                        user.displayName + "\n" + user.username + " · " + user.role.label +
                                (if (user.isPreset) "\nPreset account" else ""),
                    )
                }
            }
        }
    }
}

@Composable
private fun EnrollKeyScreen(
    padding: PaddingValues,
    keys: List<TerminalKey>,
    hardwareState: CabinetHardwareState,
    notice: String?,
    onBack: () -> Unit,
    onOpenEnrollmentSession: ((() -> Unit), (String) -> Unit) -> Unit,
    onPrepareKeyFob: (Int, (String) -> Unit, (String) -> Unit) -> Unit,
    onSaveKey: (String, Int, String) -> StoreResult<TerminalKey>,
    onMonitorReturnedFob: (Int, String, () -> Unit, (String) -> Unit) -> Unit,
    onStopMonitoring: () -> Unit,
) {
    var keyName by remember { mutableStateOf("") }
    var nodeAddressText by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(GuidedKeyEnrollmentPhase.OPENING_DOOR) }
    var statusMessage by remember {
        mutableStateOf("Opening the cabinet and ejecting the door for this key-enrolment session…")
    }
    var cabinetFobUid by remember { mutableStateOf<String?>(null) }
    var terminalFobUid by remember { mutableStateOf<String?>(null) }
    var terminalReaderState by remember { mutableStateOf<TerminalNfcReaderState>(TerminalNfcReaderState.Idle) }
    var scanGeneration by remember { mutableStateOf(0) }
    var mismatchCount by remember { mutableStateOf(0) }
    val nodeAddress = nodeAddressText.toIntOrNull()
    val canUseSelectedNode = nodeAddress != null && nodeAddress in 0..MAX_KEY_NODE_ADDRESS
    val slotAlreadyRegistered = canUseSelectedNode && keys.any { key ->
        key.boxAddress == hardwareState.boxAddress && key.nodeAddress == nodeAddress
    }
    val terminalReader = remember {
        TerminalNfcReaderController(
            onStateChanged = { state -> terminalReaderState = state },
            onFobDetected = { rawUid -> terminalFobUid = rawUid },
        )
    }

    fun resetForNextKey(message: String) {
        terminalReader.stopScan()
        keyName = ""
        nodeAddressText = ""
        cabinetFobUid = null
        terminalFobUid = null
        mismatchCount = 0
        phase = GuidedKeyEnrollmentPhase.READY_FOR_DETAILS
        statusMessage = message
    }

    fun monitorReturnedFob(expectedUid: String, afterSecureMessage: String) {
        val selectedNode = nodeAddress ?: return
        phase = GuidedKeyEnrollmentPhase.WAITING_FOR_RETURN
        statusMessage = "Key details are saved. Return the fob to the red-lit node; the slot will lock automatically."
        onMonitorReturnedFob(
            selectedNode,
            expectedUid,
            {
                resetForNextKey(afterSecureMessage)
            },
            { error ->
                phase = GuidedKeyEnrollmentPhase.RETURN_RECOVERY
                statusMessage = "$error Return the fob to the selected node, then retry the return check."
            },
        )
    }

    fun prepareSelectedFob() {
        val selectedNode = nodeAddress
        if (!canUseSelectedNode || selectedNode == null) {
            statusMessage = "Enter a raw node address from 0 to $MAX_KEY_NODE_ADDRESS."
            return
        }
        if (slotAlreadyRegistered) {
            statusMessage = "This Box/Node already has a registered key. Choose an unused node."
            return
        }

        mismatchCount = 0
        cabinetFobUid = null
        terminalFobUid = null
        phase = GuidedKeyEnrollmentPhase.PREPARING_FOB
        statusMessage = "Blue light is turning on while the key fob is released and read…"
        onPrepareKeyFob(
            selectedNode,
            { nodeFobUid ->
                cabinetFobUid = nodeFobUid
                phase = GuidedKeyEnrollmentPhase.AWAITING_TERMINAL_SCAN
                statusMessage = "Take the released fob and scan it at the Terminal NFC reader."
                scanGeneration += 1
            },
            { error ->
                phase = GuidedKeyEnrollmentPhase.READY_FOR_DETAILS
                statusMessage = "$error Check the physical fob and start this key again."
            },
        )
    }

    DisposableEffect(terminalReader) {
        onDispose {
            terminalReader.close()
            onStopMonitoring()
        }
    }

    LaunchedEffect(Unit) {
        onOpenEnrollmentSession(
            {
                phase = GuidedKeyEnrollmentPhase.READY_FOR_DETAILS
                statusMessage = "Cabinet door ejected. Enter the key name and its raw node address."
            },
            { error ->
                phase = GuidedKeyEnrollmentPhase.OPEN_FAILURE
                statusMessage = error
            },
        )
    }

    LaunchedEffect(phase, scanGeneration, cabinetFobUid) {
        if (phase == GuidedKeyEnrollmentPhase.AWAITING_TERMINAL_SCAN && cabinetFobUid != null) {
            terminalReader.startScan()
        } else {
            terminalReader.stopScan()
        }
    }

    LaunchedEffect(terminalFobUid) {
        val scannedUid = terminalFobUid ?: return@LaunchedEffect
        val expectedUid = cabinetFobUid
        if (phase != GuidedKeyEnrollmentPhase.AWAITING_TERMINAL_SCAN || expectedUid == null) {
            terminalFobUid = null
            return@LaunchedEffect
        }

        terminalFobUid = null
        if (!sameFobUid(expectedUid, scannedUid)) {
            mismatchCount += 1
            statusMessage = "This fob does not match the selected node. Scan the released fob again. " +
                    "Attempt $mismatchCount."
            scanGeneration += 1
            return@LaunchedEffect
        }

        val selectedNode = nodeAddress
        if (selectedNode == null) {
            phase = GuidedKeyEnrollmentPhase.READY_FOR_DETAILS
            statusMessage = "The selected node is no longer valid. Start again."
            return@LaunchedEffect
        }

        phase = GuidedKeyEnrollmentPhase.SAVING_KEY
        statusMessage = "Fob verified. Saving the protected key record…"
        when (val result = onSaveKey(keyName, selectedNode, expectedUid)) {
            is StoreResult.Success -> {
                monitorReturnedFob(
                    expectedUid,
                    "Key ${result.value.displayName} is enrolled and its slot is secured. Ready for the next key.",
                )
            }

            is StoreResult.Error -> {
                monitorReturnedFob(
                    expectedUid,
                    "No key record was created: ${result.message} The fob was returned and the slot is secured. Ready for the next key.",
                )
            }
        }
    }

    TerminalPage(padding) {
        val canLeaveScreen = phase == GuidedKeyEnrollmentPhase.READY_FOR_DETAILS ||
                phase == GuidedKeyEnrollmentPhase.OPEN_FAILURE
        BackButton(onBack = onBack, enabled = canLeaveScreen)
        HeaderCard(
            title = "Guided key enrolment",
            description = "The cabinet door is ejected when this screen opens. The Terminal then guides one key fob through release, protected NFC comparison, save, return and automatic slot secure.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        HardwareStatusCard(hardwareState)

        SuperAdminNoticeCard(statusMessage)

        when (phase) {
            GuidedKeyEnrollmentPhase.OPENING_DOOR -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                Text("Opening the cabinet and ejecting its door…")
            }

            GuidedKeyEnrollmentPhase.OPEN_FAILURE -> {
                Button(
                    onClick = {
                        phase = GuidedKeyEnrollmentPhase.OPENING_DOOR
                        statusMessage = "Retrying cabinet connection and door eject…"
                        onOpenEnrollmentSession(
                            {
                                phase = GuidedKeyEnrollmentPhase.READY_FOR_DETAILS
                                statusMessage = "Cabinet door ejected. Enter the key name and its raw node address."
                            },
                            { error ->
                                phase = GuidedKeyEnrollmentPhase.OPEN_FAILURE
                                statusMessage = error
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !hardwareState.busy,
                ) {
                    Text("Retry door eject")
                }
            }

            GuidedKeyEnrollmentPhase.READY_FOR_DETAILS -> {
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Key name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = nodeAddressText,
                    onValueChange = { value ->
                        nodeAddressText = value.filter { character -> character.isDigit() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Raw Node Address (0–$MAX_KEY_NODE_ADDRESS)") },
                    singleLine = true,
                    isError = nodeAddressText.isNotBlank() && (!canUseSelectedNode || slotAlreadyRegistered),
                    supportingText = {
                        Text(
                            when {
                                slotAlreadyRegistered -> "This Box/Node already has an enrolled key."
                                else -> "Box ${hardwareState.boxAddress} · use the supplier raw address; eKMS does not subtract 1."
                            },
                        )
                    },
                )
                Button(
                    onClick = ::prepareSelectedFob,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hardwareState.connected && !hardwareState.busy &&
                            !hardwareState.keyReturnMonitoring && keyName.trim().length >= 2 &&
                            canUseSelectedNode && !slotAlreadyRegistered,
                ) {
                    Text("Start guided enrolment")
                }
                Text(
                    "After this one action, blue light, fob release, cabinet UID read, Terminal NFC comparison, key save, red-light return detection and slot secure are automatic.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            GuidedKeyEnrollmentPhase.PREPARING_FOB -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                Text("Locating the selected slot and reading its fob…")
            }

            GuidedKeyEnrollmentPhase.AWAITING_TERMINAL_SCAN -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                Text(
                    when (val readerState = terminalReaderState) {
                        TerminalNfcReaderState.Connecting -> "Connecting the Terminal NFC reader…"
                        TerminalNfcReaderState.WaitingForFob -> "Scan the released fob at the Terminal NFC reader."
                        TerminalNfcReaderState.FobCaptured -> "Comparing the scanned fob…"
                        is TerminalNfcReaderState.Error -> readerState.message
                        else -> "Preparing the Terminal NFC reader…"
                    },
                )
                if (terminalReaderState is TerminalNfcReaderState.Error) {
                    OutlinedButton(
                        onClick = { scanGeneration += 1 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retry Terminal NFC scan")
                    }
                }
                if (mismatchCount > 0) {
                    Text("The fob must match the selected cabinet node before it can be saved.")
                }
            }

            GuidedKeyEnrollmentPhase.SAVING_KEY -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                Text("Saving the verified key details…")
            }

            GuidedKeyEnrollmentPhase.WAITING_FOR_RETURN -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                Text("Return the same fob to the red-lit node. The Terminal is detecting it automatically.")
                Text(
                    "Do not leave this screen until the slot reports that it is secured.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            GuidedKeyEnrollmentPhase.RETURN_RECOVERY -> {
                val expectedUid = cabinetFobUid
                OutlinedButton(
                    onClick = {
                        val selectedNode = nodeAddress
                        if (expectedUid != null && selectedNode != null) {
                            phase = GuidedKeyEnrollmentPhase.WAITING_FOR_RETURN
                            statusMessage = "Retrying detection of the returned fob…"
                            onMonitorReturnedFob(
                                selectedNode,
                                expectedUid,
                                {
                                    resetForNextKey("The fob was returned and the slot is secured. Ready for the next key.")
                                },
                                { error ->
                                    phase = GuidedKeyEnrollmentPhase.RETURN_RECOVERY
                                    statusMessage = "$error Return the fob to the selected node, then retry the return check."
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = cabinetFobUid != null && nodeAddress != null && !hardwareState.busy,
                ) {
                    Text("Retry returned-fob detection")
                }
            }
        }

        Text("Enrolled keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (keys.isEmpty()) {
            Text("No physical key has been enrolled yet.")
        }
        keys.forEach { key ->
            KeyCard(key)
        }
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
        HeaderCard(
            title = "Cabinet hardware control",
            description = "Android Terminal-only live serial control. Commands run one at a time on the confirmed cabinet bus. Door eject and electromagnet actions always require a second confirmation.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        HardwareStatusCard(hardwareState)

        if (!hardwareState.connected) {
            OutlinedTextField(
                value = portPath,
                onValueChange = { portPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cabinet serial port") },
                singleLine = true,
            )
            OutlinedTextField(
                value = baudRateText,
                onValueChange = { baudRateText = it.filter { character -> character.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Baud rate") },
                singleLine = true,
            )
            OutlinedTextField(
                value = boxAddressText,
                onValueChange = { boxAddressText = it.filter { character -> character.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Box Address (1–255)") },
                singleLine = true,
            )
            Button(
                onClick = {
                    onConnect(portPath.trim(), requireNotNull(baudRate), requireNotNull(boxAddress))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = validConnection && !hardwareState.busy,
            ) {
                Text("Connect cabinet")
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
) {
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Text("Back to Super Admin dashboard")
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
            style = MaterialTheme.typography.bodySmall.merge(DataReadoutTextStyle),
        )
        if (state.keyReturnMonitoring) {
            Text("Key return monitor: active", style = MaterialTheme.typography.bodySmall)
        }
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        state.doorStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall.merge(DataReadoutTextStyle)) }
        state.nodeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall.merge(DataReadoutTextStyle)) }
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

@Composable
private fun KeyCard(key: TerminalKey) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(16.dp)) {
            Text(
                key.displayName + "\nBox " + key.boxAddress + " · Node " + key.nodeAddress +
                        "\nPhysical fob enrolled",
            )
        }
    }
}

private enum class GuidedKeyEnrollmentPhase {
    OPENING_DOOR,
    OPEN_FAILURE,
    READY_FOR_DETAILS,
    PREPARING_FOB,
    AWAITING_TERMINAL_SCAN,
    SAVING_KEY,
    WAITING_FOR_RETURN,
    RETURN_RECOVERY,
}

/** Constant-time comparison for two raw UIDs kept only during this session. */
private fun sameFobUid(first: String, second: String): Boolean =
    MessageDigest.isEqual(
        first.toByteArray(Charsets.US_ASCII),
        second.toByteArray(Charsets.US_ASCII),
    )

private enum class SuperAdminRoute {
    LOGIN,
    CHANGE_PASSWORD,
    DASHBOARD,
    ENROLL_USER,
    ENROLL_KEY,
    CARD_ENROLLMENT,
    ACCESS_GRANTS,
    KEY_RETRIEVAL,
    ADMIN_MENU,
    HARDWARE,
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

/**
 * Section 3 (key return) state, driven by a key-card swipe rather than
 * `route`. [matchedKey]/[matchedSlot] carry a real card-UID match through
 * the certification-login step, so it is used directly once certification
 * succeeds instead of being lost and re-resolved by the "only key
 * currently taken" heuristic. [abandonAtEpochMillis] is the Key Return
 * Flow's 20s-from-swipe abandonment deadline (CLAUDE.md "Terminal App UX
 * Baseline (Production)" §2) — computed once at the original swipe and
 * threaded unchanged through both states, null only for the login
 * screen's UID-less manual key-card tap (never a timed flow).
 */
private sealed interface ReturnFlow {
    val matchedKey: ManagedKey?
    val matchedSlot: KeySlot?
    val abandonAtEpochMillis: Long?

    data class AwaitingCertification(
        override val matchedKey: ManagedKey? = null,
        override val matchedSlot: KeySlot? = null,
        override val abandonAtEpochMillis: Long? = null,
        val loginError: String? = null,
    ) : ReturnFlow

    data class DoorOpen(
        override val matchedKey: ManagedKey?,
        override val matchedSlot: KeySlot?,
        override val abandonAtEpochMillis: Long?,
    ) : ReturnFlow
}

/** Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1) in-progress state; see `TerminalKeyTakeScreen`. */
private data class TakeFlow(val key: ManagedKey, val slot: KeySlot)