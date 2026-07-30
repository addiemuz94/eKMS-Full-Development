package com.ekms.terminal.hardware

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ekms.shared.protocol.KeyCabinetLink
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes all physical cabinet operations and publishes only safe status
 * text to the Compose UI. It never logs or exposes a raw physical fob UID.
 *
 * Frame assembly, timeout/retry, and the one-electromagnet-at-a-time safety
 * guard all live in the shared [KeyCabinetLink] (phase 6/7); this class only
 * owns the real Android serial port, the background executor that
 * serializes commands onto it, and the guided key-enrolment/return flows
 * built on top of the raw command set.
 */
class CabinetHardwareController(
    private val onStateChanged: (CabinetHardwareState) -> Unit,
) {
    companion object {
        const val DEFAULT_PORT_PATH = "/dev/ttyS1"
        const val DEFAULT_BAUD_RATE = 19_200
        const val DEFAULT_BOX_ADDRESS = 1
        private const val LOG_TAG = "CabinetHardwareController"

        // Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1).
        private const val LOUDER_BEEP_THRESHOLD_MILLIS = 5_000L
        private const val ABANDONMENT_TIMEOUT_MILLIS = 20_000L
        private const val KEY_REMOVAL_POLL_INTERVAL_MILLIS = 400L
        private const val DOOR_CLOSE_POLL_INTERVAL_MILLIS = 700L

        // Key Return Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §2).
        /** Measured from the initial card swipe, not from door-open — the caller computes the deadline at swipe time. */
        const val RETURN_FLOW_ABANDONMENT_TIMEOUT_MILLIS = 20_000L
        private const val INSERTION_LOUDER_BEEP_THRESHOLD_MILLIS = 5_000L
        private const val KEY_INSERTION_POLL_INTERVAL_MILLIS = 400L
        private const val RETURN_DOOR_CLOSE_POLL_INTERVAL_MILLIS = 700L

        // Key Attachment background auto-scan (Part 3): long enough to be clearly visible at a
        // glance, short enough not to look like the node is stuck on — an easy constant to retune.
        private const val FOB_SCAN_FLASH_MILLIS = 600L

        // Key Attachment (Part 4) guided flow: how long the poll-for-attachment loop waits for a
        // fob to be physically clipped onto the unlocked slot before giving up. Generous —
        // unlike Take/Return Flow's operator-facing abandonment ceilings (spec'd, hardware-
        // verified timings), this is a first-pass guess for a screen with no hardware pass yet.
        private const val ATTACHMENT_TIMEOUT_MILLIS = 60_000L
        private const val ATTACHMENT_POLL_INTERVAL_MILLIS = 400L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val transport = AndroidSerialTransport()
    private val returnMonitoring = AtomicBoolean(false)
    /** Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1): guards the whole take, door-open through door-close. */
    private val takeMonitoring = AtomicBoolean(false)
    /** Key Attachment (Part 4): guards the guided attach-a-new-key flow, unlock through secure. */
    private val attachmentMonitoring = AtomicBoolean(false)

    @Volatile
    private var currentState = CabinetHardwareState()
    private var link: KeyCabinetLink? = null

    fun connect(
        portPath: String = DEFAULT_PORT_PATH,
        baudRate: Int = DEFAULT_BAUD_RATE,
        boxAddress: Int = DEFAULT_BOX_ADDRESS,
    ) {
        publish(currentState.copy(busy = true, message = "Opening cabinet serial port…"))
        worker.execute {
            try {
                transport.open(portPath, baudRate)
                link = KeyCabinetLink(transport, boxAddress, onCommandFailure = ::logCommandFailure)
                publish(
                    currentState.copy(
                        connected = true,
                        busy = false,
                        message = "Connected to cabinet at " + portPath + " @ " + baudRate + " baud.",
                        portPath = portPath,
                        baudRate = baudRate,
                        boxAddress = boxAddress,
                    ),
                )
            } catch (error: Exception) {
                transport.close()
                link = null
                publish(
                    currentState.copy(
                        connected = false,
                        busy = false,
                        message = "Cabinet connection failed: " + error.detail(),
                    ),
                )
            }
        }
    }

    fun disconnect() {
        returnMonitoring.set(false)
        takeMonitoring.set(false)
        publish(currentState.copy(busy = true, message = "Closing cabinet serial port…"))
        worker.execute {
            transport.close()
            link = null
            publish(
                currentState.copy(
                    connected = false,
                    busy = false,
                    message = "Cabinet disconnected.",
                    doorStatus = null,
                    nodeStatus = null,
                    keyReturnMonitoring = false,
                ),
            )
        }
    }

    fun checkDoorStatus() = runCommand("Checking cabinet door status…") { link ->
        val response = link.checkDoorStatus().data
        val status = when {
            response.isFourBytesOf(0x00) -> "Door status: engaged / locked."
            response.isFourBytesOf(0xFF) -> "Door status: closed / not engaged."
            else -> "Door status: unexpected response."
        }
        currentState.copy(doorStatus = status, message = status)
    }

    fun ejectDoor() = runCommand("Sending door eject command (0x23)…") { link ->
        link.ejectDoor()
        currentState.copy(message = "Door eject (0x23) was acknowledged. Inspect the door before continuing.")
    }

    /**
     * Opens one guided key-enrolment session. It connects the cabinet with the
     * saved/default Terminal settings when needed, then ejects the cabinet
     * door exactly once for this screen entry.
     */
    fun openKeyEnrollmentSession(
        onDoorEjected: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (currentState.busy || returnMonitoring.get()) {
            notifyCommandFailure("Wait for the current cabinet action to finish.", onFailure)
            return
        }

        publish(
            currentState.copy(
                busy = true,
                message = "Opening the cabinet and ejecting its door for key enrolment…",
                keyReturnMonitoring = false,
            ),
        )
        worker.execute {
            try {
                ensureConnectedOnWorker()
                requireNotNull(link) { "Cabinet protocol is unavailable." }.ejectDoor()
                publish(
                    currentState.copy(
                        connected = true,
                        busy = false,
                        message = "Cabinet door was ejected. Enter the key name and raw node address.",
                        keyReturnMonitoring = false,
                    ),
                )
                mainHandler.post(onDoorEjected)
            } catch (error: Exception) {
                transport.close()
                link = null
                reportCommandFailure("Unable to open the key-enrolment session", error, onFailure)
            }
        }
    }

    /**
     * Key Take Flow, step 1 (CLAUDE.md "Terminal App UX Baseline
     * (Production)" §1 — the production TAKE side): Blue Light On (0x11) -> Unlock (0x13,
     * field-verified) -> Eject Door (0x23), then confirms the door is
     * physically open via Check Door Status (0x22). The 500 ms/3-attempt
     * retry for that confirmation already lives in
     * [com.ekms.shared.protocol.KeyCabinetLink.sendCommand] — this does not
     * add a second retry loop on top of it.
     *
     * A door-open-confirmation failure re-locks the node (0x14) and turns
     * its light back off before reporting, so a hardware fault here never
     * leaves the electromagnet engaged or the light on — see
     * [pollForKeyRemoval]/[waitForDoorCloseAfterTake] for the rest of the
     * exit-cleanup guarantee. [takeMonitoring] guards the whole flow from
     * this call through whichever of those two ends it.
     */
    fun beginKeyTake(
        nodeAddress: Int,
        onDoorOpenConfirmed: () -> Unit,
        onFailure: (String) -> Unit,
    ) = beginKeyTakeInternal(nodeAddress, precedingRedLightOff = false, onDoorOpenConfirmed, onFailure)

    /**
     * Multi-key sequential Take Flow (Key Menu): the exact same sequence as [beginKeyTake],
     * preceded by turning this node's red "waiting in queue" indicator off as one atomic
     * worker-thread operation — the red -> blue "your turn" transition. Sequential, not
     * simultaneous: the red command completes before [beginKeyTakeInternal] sends its own
     * blue-on — nothing in the protocol doc implies both lit at once is meaningful, so this
     * is the conservative default. Every other node still waiting in the queue keeps its red
     * light untouched; nothing here polls to hold it on, it already latches on its own.
     */
    fun beginQueuedKeyTake(
        nodeAddress: Int,
        onDoorOpenConfirmed: () -> Unit,
        onFailure: (String) -> Unit,
    ) = beginKeyTakeInternal(nodeAddress, precedingRedLightOff = true, onDoorOpenConfirmed, onFailure)

    private fun beginKeyTakeInternal(
        nodeAddress: Int,
        precedingRedLightOff: Boolean,
        onDoorOpenConfirmed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure)) return
        if (!takeMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("A key take is already in progress.", onFailure)
            return
        }
        publish(currentState.copy(busy = true, message = "Unlocking and ejecting the door for node $nodeAddress…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                if (precedingRedLightOff) activeLink.redLightOff(nodeAddress)
                activeLink.blueLightOn(nodeAddress)
                activeLink.engageElectromagnet(nodeAddress)
                activeLink.ejectDoor()

                val doorStatus = activeLink.checkDoorStatus().data
                if (!isDoorOpen(doorStatus)) {
                    throw IllegalStateException("The cabinet door did not confirm open for node $nodeAddress.")
                }

                publish(
                    currentState.copy(
                        busy = false,
                        doorStatus = "Door status: engaged / locked.",
                        message = "Door open for node $nodeAddress. Waiting for the key to be removed…",
                    ),
                )
                mainHandler.post(onDoorOpenConfirmed)
            } catch (error: Exception) {
                runCatching { link?.releaseElectromagnet(nodeAddress) }
                runCatching { link?.blueLightOff(nodeAddress) }
                takeMonitoring.set(false)
                reportCommandFailure("Unable to begin the key take at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Key Menu (multi-key sequential Take Flow), step 0: lights every selected node's red
     * "waiting in queue" indicator (0x19) — one command per node, individually, never a
     * batch/group command since the protocol has none — before the sequential per-node take
     * loop in [beginQueuedKeyTake] begins. Runs as one worker-thread operation so the calls
     * are naturally serialized one after another without needing [runCommand]'s single-
     * command busy-gate to be re-entered per node. The light latches on its own once set; no
     * polling/keep-alive loop is added to hold it on.
     *
     * On failure partway through, best-effort turns back off whichever lights this call did
     * manage to set, so a partial failure never leaves stray red lights on with no active
     * queue behind them.
     */
    fun beginMultiKeyRedLightSequence(
        nodeAddresses: List<Int>,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (nodeAddresses.isEmpty()) {
            notifyCommandFailure("No key was selected.", onFailure)
            return
        }
        if (!canStartOperatorCommand(onFailure)) return
        publish(currentState.copy(busy = true, message = "Lighting ${nodeAddresses.size} selected key(s)…"))
        worker.execute {
            val lit = mutableListOf<Int>()
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                nodeAddresses.forEach { nodeAddress ->
                    activeLink.redLightOn(nodeAddress)
                    lit += nodeAddress
                }
                publish(currentState.copy(busy = false, message = "${nodeAddresses.size} key(s) lit. Starting with node ${nodeAddresses.first()}…"))
                mainHandler.post(onReady)
            } catch (error: Exception) {
                lit.forEach { nodeAddress -> runCatching { link?.redLightOff(nodeAddress) } }
                reportCommandFailure("Unable to light the selected keys", error, onFailure)
            }
        }
    }

    /**
     * Key Attachment screen (Part 5), entry: lights every KeySlot-assigned node at once — blue
     * for [needsAttachmentNodeAddresses] (no stored fob UID yet), red for
     * [alreadyAttachedNodeAddresses] — one command per node, individually, same
     * never-a-batch-command reasoning as [beginMultiKeyRedLightSequence]. Auto-connects if not
     * already connected, same as every other operator-reachable entry point.
     */
    fun lightKeyAttachmentOverview(
        needsAttachmentNodeAddresses: List<Int>,
        alreadyAttachedNodeAddresses: List<Int>,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure)) return
        publish(currentState.copy(busy = true, message = "Lighting cabinet overview…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                needsAttachmentNodeAddresses.forEach { activeLink.blueLightOn(it) }
                alreadyAttachedNodeAddresses.forEach { activeLink.redLightOn(it) }
                publish(currentState.copy(busy = false, message = "Cabinet overview lit."))
                mainHandler.post(onReady)
            } catch (error: Exception) {
                reportCommandFailure("Unable to light the cabinet overview", error, onFailure)
            }
        }
    }

    /** Best-effort, fire-and-forget cleanup for [lightKeyAttachmentOverview] — turns both colors
     * off for every node in [nodeAddresses] regardless of which one was lit, so leaving the
     * screen never leaves a stray light on. No callback: this is exit-path cleanup only. */
    fun clearKeyAttachmentOverview(nodeAddresses: List<Int>) {
        if (nodeAddresses.isEmpty()) return
        worker.execute {
            nodeAddresses.forEach { nodeAddress ->
                runCatching { link?.blueLightOff(nodeAddress) }
                runCatching { link?.redLightOff(nodeAddress) }
            }
        }
    }

    /**
     * Key Take Flow, step 2: polls Test Micro Switch (0x16) for bolt
     * removal from the moment the door was confirmed open. Two independent
     * timers, both measured from this call's start, never from each other:
     * - [LOUDER_BEEP_THRESHOLD_MILLIS] (5 s): if the key is still present,
     *   [onLouderBeepThreshold] fires exactly once — volume only, this
     *   never resets or extends the abandonment ceiling below.
     * - [ABANDONMENT_TIMEOUT_MILLIS] (20 s): the hard ceiling for the
     *   whole no-removal state. If the key is still present here, the node
     *   is re-locked (0x14) and its light turned off before [onAbandoned]
     *   fires — the flow ends here and the guard is released; no caller-
     *   side cleanup is required.
     * Removal at any point before the 20 s ceiling cancels both timers and
     * calls [onRemoved] — including when the 5 s threshold already fired.
     */
    fun pollForKeyRemoval(
        nodeAddress: Int,
        onRemoved: () -> Unit,
        onLouderBeepThreshold: () -> Unit,
        onAbandoned: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                val startedAtMillis = System.currentTimeMillis()
                var louderBeepFired = false
                while (takeMonitoring.get() && transport.isOpen) {
                    val elapsedMillis = System.currentTimeMillis() - startedAtMillis
                    val status = activeLink.testMicroSwitch(nodeAddress).data
                    if (status.isFourBytesOf(0xFF)) {
                        publish(
                            currentState.copy(
                                busy = false,
                                nodeStatus = "Node $nodeAddress: key removed and confirmed.",
                                message = "Key removed. Waiting for the door to close.",
                            ),
                        )
                        mainHandler.post(onRemoved)
                        return@execute
                    }

                    if (!louderBeepFired && elapsedMillis >= LOUDER_BEEP_THRESHOLD_MILLIS) {
                        louderBeepFired = true
                        mainHandler.post(onLouderBeepThreshold)
                    }

                    if (elapsedMillis >= ABANDONMENT_TIMEOUT_MILLIS) {
                        activeLink.releaseElectromagnet(nodeAddress)
                        activeLink.blueLightOff(nodeAddress)
                        takeMonitoring.set(false)
                        publish(
                            currentState.copy(
                                busy = false,
                                nodeStatus = "Node $nodeAddress: key take abandoned.",
                                message = "Key take abandoned — the key was never removed.",
                            ),
                        )
                        mainHandler.post(onAbandoned)
                        return@execute
                    }

                    Thread.sleep(KEY_REMOVAL_POLL_INTERVAL_MILLIS)
                }

                if (!transport.isOpen) {
                    takeMonitoring.set(false)
                    throw IllegalStateException("Cabinet connection closed while waiting for key removal.")
                }
            } catch (error: Exception) {
                takeMonitoring.set(false)
                reportCommandFailure("Unable to monitor key removal at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Key Take Flow, step 3: polls Check Door Status (0x22) until the door
     * is physically closed. If [warningSeconds] (the Admin Menu's Take
     * Warning Time) elapses first, [onWarningExpired] fires exactly once —
     * the "please close the door" voice line and the door-left-open event
     * are the caller's responsibility, not this method's — and polling
     * continues indefinitely afterward; there is no further ceiling here
     * by design, since the operator must eventually close the door to
     * secure the cabinet. [onDoorClosed] always fires exactly once,
     * whenever the door actually closes, whether that is before or long
     * after the warning, and turns the node's light off and releases the
     * take guard at that point — the flow's only success exit.
     */
    fun waitForDoorCloseAfterTake(
        nodeAddress: Int,
        warningSeconds: Int,
        onWarningExpired: () -> Unit,
        onDoorClosed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                val startedAtMillis = System.currentTimeMillis()
                val warningMillis = warningSeconds * 1_000L
                var warningFired = false
                while (takeMonitoring.get() && transport.isOpen) {
                    val status = activeLink.checkDoorStatus().data
                    if (!isDoorOpen(status)) {
                        activeLink.blueLightOff(nodeAddress)
                        takeMonitoring.set(false)
                        publish(
                            currentState.copy(
                                busy = false,
                                doorStatus = "Door status: closed / not engaged.",
                                nodeStatus = "Node $nodeAddress: key take complete.",
                                message = "Key take complete.",
                            ),
                        )
                        mainHandler.post(onDoorClosed)
                        return@execute
                    }

                    if (!warningFired && System.currentTimeMillis() - startedAtMillis >= warningMillis) {
                        warningFired = true
                        mainHandler.post(onWarningExpired)
                    }

                    Thread.sleep(DOOR_CLOSE_POLL_INTERVAL_MILLIS)
                }

                if (!transport.isOpen) {
                    takeMonitoring.set(false)
                    throw IllegalStateException("Cabinet connection closed while waiting for the door to close.")
                }
            } catch (error: Exception) {
                takeMonitoring.set(false)
                reportCommandFailure("Unable to confirm the door closed for node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Key Return Flow, step 1 (CLAUDE.md "Terminal App UX Baseline
     * (Production)" §2 — supersedes [beginKeyReturn]'s bare open for the
     * production RETURN side): Blue Light On (0x11) -> Eject Door (0x23)
     * at [nodeAddress], then confirms the door is physically open via
     * Check Door Status (0x22). Unlike [beginKeyTake], this never touches
     * the electromagnet — nothing is locked to this node yet, so there is
     * nothing to unlock before opening it.
     *
     * Direction-reversed from the Key Take Flow, but reuses the same
     * [returnMonitoring] guard the pre-existing [waitForKeyInserted] uses
     * (both mean "a key return is being monitored"), not a new flag — see
     * [pollForKeyInsertion]/[waitForDoorCloseAfterReturn] for the rest of
     * the exit-cleanup guarantee.
     */
    fun beginKeyReturnFlow(
        nodeAddress: Int,
        onDoorOpenConfirmed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure)) return
        if (!returnMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("A key return is already being monitored.", onFailure)
            return
        }
        publish(currentState.copy(busy = true, message = "Lighting node $nodeAddress and ejecting the cabinet door…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.ejectDoor()

                val doorStatus = activeLink.checkDoorStatus().data
                if (!isDoorOpen(doorStatus)) {
                    throw IllegalStateException("The cabinet door did not confirm open for node $nodeAddress.")
                }

                publish(
                    currentState.copy(
                        busy = false,
                        doorStatus = "Door status: engaged / locked.",
                        message = "Door open for node $nodeAddress. Waiting for the key to be inserted…",
                    ),
                )
                mainHandler.post(onDoorOpenConfirmed)
            } catch (error: Exception) {
                runCatching { link?.blueLightOff(nodeAddress) }
                returnMonitoring.set(false)
                reportCommandFailure("Unable to begin the key return at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Key Return Flow, step 2: polls Test Micro Switch (0x16) for bolt
     * presence (key inserted) from the moment the door was confirmed
     * open. Two independent timers on two different clocks — this is the
     * deliberate asymmetry with [pollForKeyRemoval], not an oversight:
     * - [INSERTION_LOUDER_BEEP_THRESHOLD_MILLIS] (5 s), measured from
     *   *this call's start* (i.e. from door-open): if the key is still
     *   not inserted, [onLouderBeepThreshold] fires exactly once — volume
     *   only, never resets or extends the ceiling below.
     * - [abandonAtEpochMillis], an absolute wall-clock deadline the
     *   caller computed at the *original card swipe* (not from
     *   door-open, and not reset by however long any Key Return
     *   Certification login in between took) — the hard ceiling for the
     *   whole no-insert state. If the key is still not inserted here,
     *   the node is locked (0x14 — securing an empty/unused slot the
     *   operator never used, not "re-locking a removed key" the way Take
     *   abandonment does) and its light turned off before [onAbandoned]
     *   fires — the flow ends here and the guard is released.
     * Insertion at any point before the deadline cancels both timers,
     * locks the fob (0x14) — but deliberately leaves the light on, since
     * the flow continues through [waitForDoorCloseAfterReturn] — and
     * calls [onInserted].
     *
     * Return Flow rework — wrong-slot detection: once the door is open, every physical key
     * hook inside is reachable, not just [nodeAddress]'s — the protocol has no single command
     * that reports "which node changed," so each poll cycle also sweeps
     * [wrongSlotCandidateNodeAddresses] (every *other* node this cabinet actually has a
     * KeySlot for — not the full 1..127 address space, which would make each cycle
     * impractically slow) for an unexpected bolt-present transition. [onWrongSlotDetected]/
     * [onWrongSlotCleared] fire only on a state change (entering/leaving the wrong-slot
     * condition), not every cycle, so the caller doesn't need to de-duplicate. A wrong-slot
     * node's light turns red as a warning only — its electromagnet is never engaged, since
     * securing a key in the wrong place would be actively wrong, not helpful.
     *
     * Follow-up revision — identity, not just presence: the sweep now uses Test Micro Switch
     * *and Card* (0x17) instead of plain Test Micro Switch (0x16), so each cycle learns not
     * just "something is here" but the scanned fob's UID in the same round-trip (protocol doc
     * §5.4: readable 4 bytes = a card UID, all-`0x00` = bolt present with no readable card,
     * all-`0xFF` = nothing present at all — the same encoding [waitForReturnedKeyFob] already
     * relies on). A readable UID is resolved via [resolveKeyFobUid] (the caller's
     * `CardUidResolver`/key-fob-registry lookup — this class holds no UID store itself and
     * never should, see CLAUDE.md boundary #2) to confirm it's a genuinely enrolled key, not
     * guessed from bare presence. [onWrongSlotDetected]'s `confirmedKey` flag reports which
     * case it was. Deliberately still alarms on an unresolved presence (bolt-present-no-card,
     * or a scanned UID that doesn't resolve to any enrolled key) — a real key should always
     * carry a readable card, so anything else sitting in a node mid-return is exactly as
     * anomalous as a confirmed wrong key, and erring toward flagging it is the safer choice
     * for an unlocked, physically-open cabinet. Untested at scale: polling cost for a cabinet
     * with many configured slots — now with an extra card-read per candidate, not just a
     * switch check — is unverified against real hardware timing (see CLAUDE.md).
     */
    fun pollForKeyInsertion(
        nodeAddress: Int,
        abandonAtEpochMillis: Long,
        wrongSlotCandidateNodeAddresses: List<Int> = emptyList(),
        /** Called from this method's own worker thread, not the main thread — must be a fast, side-effect-free lookup. */
        resolveKeyFobUid: (rawUid: String) -> Boolean = { false },
        onInserted: () -> Unit,
        onWrongSlotDetected: (wrongNodeAddress: Int, confirmedKey: Boolean) -> Unit = { _, _ -> },
        onWrongSlotCleared: () -> Unit = {},
        onLouderBeepThreshold: () -> Unit,
        onAbandoned: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                val startedAtMillis = System.currentTimeMillis()
                val sweepCandidates = wrongSlotCandidateNodeAddresses.filter { it != nodeAddress }
                var louderBeepFired = false
                var wrongSlotNode: Int? = null
                while (returnMonitoring.get() && transport.isOpen) {
                    val elapsedSinceDoorOpenMillis = System.currentTimeMillis() - startedAtMillis
                    val status = activeLink.testMicroSwitch(nodeAddress).data
                    if (status.isFourBytesOf(0x00)) {
                        if (wrongSlotNode != null) {
                            runCatching { activeLink.redLightOff(wrongSlotNode!!) }
                        }
                        activeLink.releaseElectromagnet(nodeAddress)
                        publish(
                            currentState.copy(
                                busy = false,
                                nodeStatus = "Node $nodeAddress: key inserted and locked.",
                                message = "Key inserted. Waiting for the door to close.",
                            ),
                        )
                        mainHandler.post(onInserted)
                        return@execute
                    }

                    val sweepHit = sweepCandidates.firstNotNullOfOrNull { candidate ->
                        val data = activeLink.testMicroSwitchAndCard(candidate).data
                        when {
                            data.isFourBytesOf(0xFF) -> null
                            isReadableFobData(data) -> candidate to resolveKeyFobUid(data.toCompactHex())
                            else -> candidate to false
                        }
                    }
                    val detectedWrongNode = sweepHit?.first
                    if (detectedWrongNode != wrongSlotNode) {
                        if (detectedWrongNode != null) {
                            activeLink.redLightOn(detectedWrongNode)
                            wrongSlotNode = detectedWrongNode
                            val confirmedKey = sweepHit.second
                            mainHandler.post { onWrongSlotDetected(detectedWrongNode, confirmedKey) }
                        } else {
                            wrongSlotNode?.let { previous -> runCatching { activeLink.redLightOff(previous) } }
                            wrongSlotNode = null
                            mainHandler.post(onWrongSlotCleared)
                        }
                    }

                    if (!louderBeepFired && elapsedSinceDoorOpenMillis >= INSERTION_LOUDER_BEEP_THRESHOLD_MILLIS) {
                        louderBeepFired = true
                        mainHandler.post(onLouderBeepThreshold)
                    }

                    if (System.currentTimeMillis() >= abandonAtEpochMillis) {
                        if (wrongSlotNode != null) {
                            runCatching { activeLink.redLightOff(wrongSlotNode!!) }
                        }
                        activeLink.releaseElectromagnet(nodeAddress)
                        activeLink.blueLightOff(nodeAddress)
                        returnMonitoring.set(false)
                        publish(
                            currentState.copy(
                                busy = false,
                                nodeStatus = "Node $nodeAddress: key return abandoned.",
                                message = "Key return abandoned — no key was ever inserted.",
                            ),
                        )
                        mainHandler.post(onAbandoned)
                        return@execute
                    }

                    Thread.sleep(KEY_INSERTION_POLL_INTERVAL_MILLIS)
                }

                if (!transport.isOpen) {
                    returnMonitoring.set(false)
                    throw IllegalStateException("Cabinet connection closed while waiting for key insertion.")
                }
            } catch (error: Exception) {
                returnMonitoring.set(false)
                reportCommandFailure("Unable to monitor key insertion at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Key Return Flow, step 3: polls Check Door Status (0x22) until the
     * door is physically closed. If [warningSeconds] (the Admin Menu's
     * Door-Close Warning Time — a distinct setting from Take Warning
     * Time) elapses first, [onWarningExpired] fires exactly once — the
     * "please close the door" voice line and the door-left-open event
     * are the caller's responsibility, not this method's — and polling
     * continues indefinitely afterward. [onDoorClosed] always fires
     * exactly once, whenever the door actually closes, and turns the
     * node's light off and releases the return guard at that point — the
     * flow's only success exit.
     */
    fun waitForDoorCloseAfterReturn(
        nodeAddress: Int,
        warningSeconds: Int,
        onWarningExpired: () -> Unit,
        onDoorClosed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                val startedAtMillis = System.currentTimeMillis()
                val warningMillis = warningSeconds * 1_000L
                var warningFired = false
                while (returnMonitoring.get() && transport.isOpen) {
                    val status = activeLink.checkDoorStatus().data
                    if (!isDoorOpen(status)) {
                        activeLink.blueLightOff(nodeAddress)
                        returnMonitoring.set(false)
                        publish(
                            currentState.copy(
                                busy = false,
                                doorStatus = "Door status: closed / not engaged.",
                                nodeStatus = "Node $nodeAddress: key return complete.",
                                message = "Key return complete.",
                            ),
                        )
                        mainHandler.post(onDoorClosed)
                        return@execute
                    }

                    if (!warningFired && System.currentTimeMillis() - startedAtMillis >= warningMillis) {
                        warningFired = true
                        mainHandler.post(onWarningExpired)
                    }

                    Thread.sleep(RETURN_DOOR_CLOSE_POLL_INTERVAL_MILLIS)
                }

                if (!transport.isOpen) {
                    returnMonitoring.set(false)
                    throw IllegalStateException("Cabinet connection closed while waiting for the door to close.")
                }
            } catch (error: Exception) {
                returnMonitoring.set(false)
                reportCommandFailure("Unable to confirm the door closed for node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Section 3 (key return), step 1: lights node [nodeAddress]'s blue
     * indicator (0x11) and ejects the cabinet door (0x23) so the operator
     * can insert the key. Call [waitForKeyInserted] once this reports
     * ready; do not treat this call alone as the return being complete.
     */
    fun beginKeyReturn(
        nodeAddress: Int,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure)) return
        publish(currentState.copy(busy = true, message = "Lighting node $nodeAddress and ejecting the cabinet door…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.ejectDoor()
                publish(currentState.copy(busy = false, message = "Insert the key at node $nodeAddress."))
                mainHandler.post(onReady)
            } catch (error: Exception) {
                reportCommandFailure("Unable to begin the key return at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Section 3, step 2: polls Test Micro Switch (0x16) at [nodeAddress]
     * until the bolt is physically present, then secures it (0x14 — field-
     * verified to lock the key peg) and turns its blue indicator off.
     */
    fun waitForKeyInserted(
        nodeAddress: Int,
        onSecured: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!returnMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("A key return is already being monitored.", onFailure)
            return
        }

        publish(
            currentState.copy(
                busy = true,
                keyReturnMonitoring = true,
                message = "Waiting for the key to be inserted at node $nodeAddress…",
            ),
        )
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                while (returnMonitoring.get() && transport.isOpen) {
                    val data = activeLink.testMicroSwitch(nodeAddress).data
                    if (data.isFourBytesOf(0x00)) {
                        activeLink.releaseElectromagnet(nodeAddress)
                        activeLink.blueLightOff(nodeAddress)
                        returnMonitoring.set(false)
                        publish(
                            currentState.copy(
                                busy = false,
                                keyReturnMonitoring = false,
                                nodeStatus = "Node $nodeAddress: key inserted and slot secured.",
                                message = "Key return complete.",
                            ),
                        )
                        mainHandler.post(onSecured)
                        return@execute
                    }

                    publish(
                        currentState.copy(
                            busy = false,
                            keyReturnMonitoring = true,
                            message = "Waiting for the key to be inserted at node $nodeAddress…",
                        ),
                    )
                    Thread.sleep(700L)
                }

                if (!transport.isOpen) {
                    throw IllegalStateException("Cabinet connection closed while waiting for the key to be inserted.")
                }
                publish(
                    currentState.copy(
                        busy = false,
                        keyReturnMonitoring = false,
                        message = "Key-return monitoring stopped before the slot was secured.",
                    ),
                )
            } catch (error: Exception) {
                returnMonitoring.set(false)
                reportCommandFailure("Unable to secure the returned key at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Locates the selected slot, makes the physical fob removable, and reads
     * the fob UID from the cabinet node. The UID only leaves this class through
     * the callback so it can be compared in memory with the public reader.
     *
     * Engaging the electromagnet (0x13) here is field-verified to make the
     * key peg removable for pickup — see [KeyCabinetLink.engageElectromagnet].
     */
    fun prepareKeyFobForEnrollment(
        nodeAddress: Int,
        onFobRead: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartEnrollmentCommand(onFailure)) return

        publish(
            currentState.copy(
                busy = true,
                message = "Turning on blue light, releasing the key fob and reading the selected node…",
            ),
        )
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.engageElectromagnet(nodeAddress)

                val data = activeLink.readCard(nodeAddress).data
                if (data.isFourBytesOf(0x00) || data.isFourBytesOf(0xFF) || data.size != 4) {
                    throw IllegalStateException("No readable key fob was found at node $nodeAddress.")
                }

                val uid = data.toCompactHex()
                publish(
                    currentState.copy(
                        busy = false,
                        nodeStatus = "Node $nodeAddress: fob released and read for protected comparison.",
                        message = "Take the released fob and scan it at the Terminal NFC reader.",
                    ),
                )
                mainHandler.post { onFobRead(uid) }
            } catch (error: Exception) {
                reportCommandFailure("Unable to prepare node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * After a matching terminal-reader scan and key save, show the returned
     * fob location in red and poll the cabinet node until that exact fob is
     * detected. The peg is then secured (electromagnet released, 0x14) and
     * the indicator is turned off.
     */
    fun waitForReturnedKeyFob(
        nodeAddress: Int,
        expectedFobUid: String,
        onSecured: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartEnrollmentCommand(onFailure)) return
        if (!returnMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("This key return is already being monitored.", onFailure)
            return
        }

        publish(
            currentState.copy(
                busy = true,
                keyReturnMonitoring = true,
                message = "Saving complete. Preparing the selected slot for the fob return…",
            ),
        )
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOff(nodeAddress)
                activeLink.redLightOn(nodeAddress)
                publish(
                    currentState.copy(
                        busy = false,
                        keyReturnMonitoring = true,
                        message = "Place the same fob back into the red-lit node. The Terminal will secure it automatically.",
                    ),
                )

                while (returnMonitoring.get() && transport.isOpen) {
                    val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                    val returnedUid = data.takeIf { isReadableFobData(it) }?.toCompactHex()

                    if (returnedUid != null && MessageDigest.isEqual(
                            returnedUid.toByteArray(Charsets.US_ASCII),
                            expectedFobUid.toByteArray(Charsets.US_ASCII),
                        )
                    ) {
                        activeLink.releaseElectromagnet(nodeAddress)
                        activeLink.redLightOff(nodeAddress)
                        returnMonitoring.set(false)
                        publish(
                            currentState.copy(
                                busy = false,
                                keyReturnMonitoring = false,
                                nodeStatus = "Node $nodeAddress: matching fob returned and slot secured.",
                                message = "Key enrolment is complete. Ready for the next key.",
                            ),
                        )
                        mainHandler.post(onSecured)
                        return@execute
                    }

                    publish(
                        currentState.copy(
                            busy = false,
                            keyReturnMonitoring = true,
                            message = if (returnedUid == null) {
                                "Waiting for the fob to be returned to the red-lit node…"
                            } else {
                                "A different fob is in the selected node. Return the released fob to continue."
                            },
                        ),
                    )
                    Thread.sleep(700L)
                }

                if (!transport.isOpen) {
                    throw IllegalStateException("Cabinet connection closed while waiting for the key fob return.")
                }
                publish(
                    currentState.copy(
                        busy = false,
                        keyReturnMonitoring = false,
                        message = "Key-return monitoring stopped before the slot was secured.",
                    ),
                )
            } catch (error: Exception) {
                returnMonitoring.set(false)
                reportCommandFailure("Unable to secure the returned fob at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Key Attachment (Part 4) — the deliberate flow for attaching a genuinely NEW key that
     * isn't physically in the cabinet yet, distinct from Part 3's silent background capture of
     * fobs already present. Reuses the same primitives the old guided-enrollment sequence used
     * (Part 1.1 discovery: blueLightOn + engageElectromagnet to make a slot's peg workable,
     * testMicroSwitchAndCard to read a card, releaseElectromagnet + light-off to secure) but
     * adapted for the opposite intent — that old flow verified and re-secured a fob already
     * resting in the cabinet; this one unlocks an EMPTY slot and waits for a fob to be clipped
     * onto it, accepting whatever UID first appears rather than requiring it match a
     * previously-read one.
     *
     * Does not eject the door itself — like the old flow, that happens once per screen entry via
     * [openKeyEnrollmentSession], reused as-is; this only operates the one selected node.
     */
    fun beginKeyAttachment(
        nodeAddress: Int,
        onAttached: (rawUidHex: String) -> Unit,
        onTimeout: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartEnrollmentCommand(onFailure)) return
        if (!attachmentMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("A key attachment is already in progress.", onFailure)
            return
        }
        publish(
            currentState.copy(
                busy = true,
                message = "Unlocking node $nodeAddress — attach the new key's fob now…",
            ),
        )
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.engageElectromagnet(nodeAddress)

                val deadline = System.currentTimeMillis() + ATTACHMENT_TIMEOUT_MILLIS
                var uidHex: String? = null
                while (attachmentMonitoring.get() && transport.isOpen && System.currentTimeMillis() < deadline) {
                    val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                    if (isReadableFobData(data)) {
                        uidHex = data.toCompactHex()
                        break
                    }
                    Thread.sleep(ATTACHMENT_POLL_INTERVAL_MILLIS)
                }

                if (uidHex != null) {
                    activeLink.releaseElectromagnet(nodeAddress)
                    activeLink.blueLightOff(nodeAddress)
                    attachmentMonitoring.set(false)
                    publish(
                        currentState.copy(
                            busy = false,
                            nodeStatus = "Node $nodeAddress: new fob attached and secured.",
                            message = "Key attached and locked at node $nodeAddress.",
                        ),
                    )
                    val capturedUid = uidHex
                    mainHandler.post { onAttached(capturedUid) }
                } else if (!attachmentMonitoring.get()) {
                    // Cancelled via cancelKeyAttachment() — that call already released/lit-off.
                    publish(currentState.copy(busy = false, message = "Key attachment cancelled."))
                } else {
                    runCatching { activeLink.releaseElectromagnet(nodeAddress) }
                    runCatching { activeLink.blueLightOff(nodeAddress) }
                    attachmentMonitoring.set(false)
                    publish(
                        currentState.copy(
                            busy = false,
                            message = "No fob was attached to node $nodeAddress in time. Node re-locked.",
                        ),
                    )
                    mainHandler.post(onTimeout)
                }
            } catch (error: Exception) {
                runCatching { link?.releaseElectromagnet(nodeAddress) }
                runCatching { link?.blueLightOff(nodeAddress) }
                attachmentMonitoring.set(false)
                reportCommandFailure("Unable to complete key attachment at node $nodeAddress", error, onFailure)
            }
        }
    }

    /** Cancels an in-progress [beginKeyAttachment] — re-locks and lights off the node the same
     * way a timeout does, since an unlocked, unattended slot should never be left that way. */
    fun cancelKeyAttachment(nodeAddress: Int) {
        if (!attachmentMonitoring.compareAndSet(true, false)) return
        worker.execute {
            runCatching { link?.releaseElectromagnet(nodeAddress) }
            runCatching { link?.blueLightOff(nodeAddress) }
        }
    }

    /**
     * Stops whichever return monitor is running — enrollment's
     * [waitForReturnedKeyFob] or retrieval/return's [waitForKeyInserted] —
     * without itself changing a peg state.
     */
    fun stopMonitoring() {
        returnMonitoring.set(false)
        takeMonitoring.set(false)
        attachmentMonitoring.set(false)
        if (currentState.keyReturnMonitoring) {
            publish(
                currentState.copy(
                    busy = false,
                    keyReturnMonitoring = false,
                    message = "Key-return monitoring stopped. Confirm the physical slot before leaving this screen.",
                ),
            )
        }
    }

    fun readNodeStatus(nodeAddress: Int) = runCommand(
        "Reading node " + nodeAddress + " state…",
    ) { link ->
        val data = link.testMicroSwitchAndCard(nodeAddress).data
        val status = when {
            data.isFourBytesOf(0x00) -> "Node " + nodeAddress + ": no card, key bolt present."
            data.isFourBytesOf(0xFF) -> "Node " + nodeAddress + ": no card, key bolt absent."
            else -> "Node " + nodeAddress + ": card detected."
        }
        currentState.copy(nodeStatus = status, message = status)
    }

    fun readPhysicalFob(
        nodeAddress: Int,
        onFobRead: (String) -> Unit,
    ) = runCommand(
        "Reading protected fob identifier at node " + nodeAddress + "…",
    ) { link ->
        val data = link.readCard(nodeAddress).data
        if (data.isFourBytesOf(0x00) || data.isFourBytesOf(0xFF) || data.size != 4) {
            throw IllegalStateException("No readable fob was returned by node " + nodeAddress + ".")
        }
        val uid = data.toCompactHex()
        mainHandler.post { onFobRead(uid) }
        currentState.copy(
            nodeStatus = "Node " + nodeAddress + ": physical fob read successfully.",
            message = "Physical fob captured for enrollment. Its UID remains hidden.",
        )
    }

    /**
     * Key Attachment's background auto-scan-and-save (Part 3): confirmed safe with the door
     * CLOSED by a real-hardware probe (0x17 returns a stable card UID with the door shut and no
     * electromagnet engaged — see CLAUDE_TERMINAL.md's door-closed card probe). Never ejects the
     * door and never engages/releases an electromagnet — this only reads whatever's already
     * resting in each node's card slot, exactly like [readNodeStatus] but swept across many
     * nodes in one worker-thread pass, with a brief blue-light flash per node so someone
     * standing at the cabinet gets a naked-eye cue the sweep is actually running even with no
     * app screen open. [onNodeResult] is posted to the main thread after each node (so the
     * caller can do local-store/network work without blocking the sweep); [onSweepComplete]
     * fires once, after the last node, on the main thread, always.
     */
    fun autoScanKeyFobs(
        nodeAddresses: List<Int>,
        onNodeResult: (nodeAddress: Int, result: KeyFobScanResult) -> Unit,
        onSweepComplete: () -> Unit,
    ) {
        if (nodeAddresses.isEmpty()) {
            mainHandler.post(onSweepComplete)
            return
        }
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                for (nodeAddress in nodeAddresses) {
                    try {
                        activeLink.blueLightOn(nodeAddress)
                        val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                        Thread.sleep(FOB_SCAN_FLASH_MILLIS)
                        activeLink.blueLightOff(nodeAddress)
                        val result = when {
                            data.isFourBytesOf(0x00) -> KeyFobScanResult.BoltPresentNoCard
                            data.isFourBytesOf(0xFF) -> KeyFobScanResult.NothingPresent
                            else -> KeyFobScanResult.CardRead(data.toCompactHex())
                        }
                        mainHandler.post { onNodeResult(nodeAddress, result) }
                    } catch (nodeError: Exception) {
                        runCatching { link?.blueLightOff(nodeAddress) }
                        val message = nodeError.message ?: nodeError.javaClass.simpleName
                        mainHandler.post { onNodeResult(nodeAddress, KeyFobScanResult.Failed(message)) }
                    }
                }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Auto-scan sweep could not connect: ${error.detail()}")
            } finally {
                mainHandler.post(onSweepComplete)
            }
        }
    }

    fun blueLight(nodeAddress: Int, enabled: Boolean) = runCommand(
        "Sending blue light command to node " + nodeAddress + "…",
    ) { link ->
        if (enabled) link.blueLightOn(nodeAddress) else link.blueLightOff(nodeAddress)
        currentState.copy(
            message = "Blue indicator " + (if (enabled) "ON" else "OFF") +
                    " acknowledged for node " + nodeAddress + ".",
        )
    }

    fun redLight(nodeAddress: Int, enabled: Boolean) = runCommand(
        "Sending red light command to node " + nodeAddress + "…",
    ) { link ->
        if (enabled) link.redLightOn(nodeAddress) else link.redLightOff(nodeAddress)
        currentState.copy(
            message = "Red indicator " + (if (enabled) "ON" else "OFF") +
                    " acknowledged for node " + nodeAddress + ".",
        )
    }

    /**
     * Physical action: supplier command 0x13. UI confirmation is required.
     * Rejected by [KeyCabinetLink] if a different node's electromagnet is
     * already engaged (section 10.4) — surfaced through the same error path
     * as any other command failure below.
     */
    fun engageElectromagnet(nodeAddress: Int) = runCommand(
        "Sending electromagnet engage command (0x13) to node " + nodeAddress + "…",
    ) { link ->
        link.engageElectromagnet(nodeAddress)
        currentState.copy(message = "Electromagnet engage (0x13) acknowledged for node " + nodeAddress + ".")
    }

    /** Physical action: supplier command 0x14. UI confirmation is required. */
    fun releaseElectromagnet(nodeAddress: Int) = runCommand(
        "Sending electromagnet release command (0x14) to node " + nodeAddress + "…",
    ) { link ->
        link.releaseElectromagnet(nodeAddress)
        currentState.copy(message = "Electromagnet release (0x14) acknowledged for node " + nodeAddress + ".")
    }

    fun close() {
        returnMonitoring.set(false)
        takeMonitoring.set(false)
        transport.close()
        link = null
        worker.shutdownNow()
    }

    private fun runCommand(
        startingMessage: String,
        command: (KeyCabinetLink) -> CabinetHardwareState,
    ) {
        if (returnMonitoring.get()) {
            publish(currentState.copy(message = "A key return is being monitored. Wait until the slot is secured."))
            return
        }
        if (takeMonitoring.get()) {
            publish(currentState.copy(message = "A key take is in progress. Wait until it finishes."))
            return
        }
        if (!currentState.connected || link == null || !transport.isOpen) {
            publish(currentState.copy(message = "Connect the cabinet before sending a command."))
            return
        }
        if (currentState.busy) {
            publish(currentState.copy(message = "Wait for the current cabinet command to finish."))
            return
        }

        publish(currentState.copy(busy = true, message = startingMessage))
        worker.execute {
            try {
                val next = command(requireNotNull(link))
                publish(next.copy(busy = false))
            } catch (error: Exception) {
                publish(
                    currentState.copy(
                        busy = false,
                        message = "Cabinet command failed: " + error.detail(),
                    ),
                )
            }
        }
    }

    private fun publish(next: CabinetHardwareState) {
        currentState = next
        mainHandler.post { onStateChanged(next) }
    }

    private fun canStartEnrollmentCommand(onFailure: (String) -> Unit): Boolean {
        val problem = when {
            returnMonitoring.get() -> "A key return is already being monitored."
            currentState.busy -> "Wait for the current cabinet action to finish."
            !currentState.connected || link == null || !transport.isOpen ->
                "Open the key-enrolment session before operating a node."
            else -> null
        }
        if (problem != null) {
            notifyCommandFailure(problem, onFailure)
            return false
        }
        return true
    }

    /**
     * Unlike [canStartEnrollmentCommand], this does not require the cabinet
     * to already be connected — an ordinary operator reaches key retrieval/
     * return directly from login, with no admin "Connect" step first, so
     * [ensureConnectedOnWorker] opens it on demand instead.
     */
    private fun canStartOperatorCommand(onFailure: (String) -> Unit): Boolean {
        val problem = when {
            returnMonitoring.get() -> "A key return is already being monitored."
            takeMonitoring.get() -> "A key take is already in progress."
            currentState.busy -> "Wait for the current cabinet action to finish."
            else -> null
        }
        if (problem != null) {
            notifyCommandFailure(problem, onFailure)
            return false
        }
        return true
    }

    /** Opens the cabinet with its last-used (or default) settings if not already connected. Must run on [worker]. */
    private fun ensureConnectedOnWorker() {
        if (transport.isOpen) return
        val portPath = currentState.portPath
        val baudRate = currentState.baudRate
        val boxAddress = currentState.boxAddress
        transport.open(portPath, baudRate)
        link = KeyCabinetLink(transport, boxAddress, onCommandFailure = ::logCommandFailure)
        publish(
            currentState.copy(
                connected = true,
                busy = true,
                portPath = portPath,
                baudRate = baudRate,
                boxAddress = boxAddress,
            ),
        )
    }

    private fun notifyCommandFailure(message: String, onFailure: (String) -> Unit) {
        publish(currentState.copy(busy = false, message = message))
        mainHandler.post { onFailure(message) }
    }

    private fun reportCommandFailure(
        context: String,
        error: Exception,
        onFailure: (String) -> Unit,
    ) {
        val message = "$context: ${error.detail()}"
        publish(
            currentState.copy(
                connected = transport.isOpen,
                busy = false,
                keyReturnMonitoring = false,
                message = message,
            ),
        )
        mainHandler.post { onFailure(message) }
    }

    /** Section 7.5: log on repeated command failure. Never logs a raw fob UID. */
    private fun logCommandFailure(nodeAddress: Int, command: Int, message: String) {
        Log.w(LOG_TAG, "node=$nodeAddress command=0x${command.toString(16)}: $message")
    }

    private fun isReadableFobData(data: ByteArray): Boolean =
        data.size == 4 && !data.isFourBytesOf(0x00) && !data.isFourBytesOf(0xFF)

    /**
     * Field-verified against real hardware (Phase 10, 2026-07-23): 0x00 —
     * the vendor doc's own "Door engaged (locked)" wording — is what
     * [KeyCabinetLink.checkDoorStatus] returns once 0x23 has ejected the
     * door open; 0xFF ("Door closed / not engaged") is the door's normal
     * resting/closed state. Mirrors [checkDoorStatus]'s existing message
     * mapping exactly; see that method for the admin-console-facing text.
     */
    private fun isDoorOpen(data: ByteArray): Boolean = data.isFourBytesOf(0x00)

    private fun Exception.detail(): String =
        message ?: javaClass.simpleName
}

/** Per-node result of [CabinetHardwareController.autoScanKeyFobs] — the raw 4-byte 0x17 read,
 * classified the same way every other 0x17 call site in this file already does. [CardRead]
 * never carries anything but the already-hex-encoded UID (boundary #2 — the caller decides what
 * to do with it locally; this class itself never persists or transmits it). */
sealed interface KeyFobScanResult {
    data class CardRead(val uidHex: String) : KeyFobScanResult
    data object BoltPresentNoCard : KeyFobScanResult
    data object NothingPresent : KeyFobScanResult
    data class Failed(val message: String) : KeyFobScanResult
}

data class CabinetHardwareState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Cabinet is not connected.",
    val portPath: String = CabinetHardwareController.DEFAULT_PORT_PATH,
    val baudRate: Int = CabinetHardwareController.DEFAULT_BAUD_RATE,
    val boxAddress: Int = CabinetHardwareController.DEFAULT_BOX_ADDRESS,
    val doorStatus: String? = null,
    val nodeStatus: String? = null,
    val keyReturnMonitoring: Boolean = false,
)

internal fun ByteArray.isFourBytesOf(value: Int): Boolean =
    size == 4 && all { (it.toInt() and 0xFF) == value }

internal fun ByteArray.toCompactHex(): String =
    joinToString(separator = "") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }
