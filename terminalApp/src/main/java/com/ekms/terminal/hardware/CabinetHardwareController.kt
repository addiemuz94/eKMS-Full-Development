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
import java.util.concurrent.atomic.AtomicLong

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

        // Key Attachment missing-fob blink (Part 3): a plain guess at a "clearly blinking, not
        // flickering" rate — no spec or hardware pass to derive this from, easy to retune.
        private const val MISSING_FOB_BLINK_INTERVAL_MILLIS = 600L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val transport = AndroidSerialTransport()
    /** Return Flow session rebuild (Jul 2026): guards one node's own unlock-through-inserted-or-
     * abandoned cycle only — short-lived, re-acquired per scan. The whole open session is
     * guarded separately by [returnSessionMonitoring]. */
    private val returnMonitoring = AtomicBoolean(false)
    /** Return Flow session rebuild (Jul 2026): guards the whole open return session — acquired
     * once by [beginReturnSessionDoorMonitor] on the first scan, released only when the door is
     * confirmed physically closed. Session-wide, unlike [returnMonitoring]. */
    private val returnSessionMonitoring = AtomicBoolean(false)
    /** Node currently mid-cycle (unlocked, awaiting insertion) during an open return session, or
     * null if none — read by [beginReturnSessionDoorMonitor] to force-clean-up a node that was
     * still active when the door closed. Only ever touched from [worker]'s single thread. */
    @Volatile
    private var activeReturnNodeAddress: Int? = null
    /** Door-Close Warning Time window for the current return session, captured once at
     * [beginReturnSessionDoorMonitor]'s start — resettable countdown anchor lives separately in
     * [returnSessionLastScanAtEpochMillis]. */
    @Volatile
    private var returnSessionDoorCloseWarningMillis: Long = 0L
    /** Reset by [resetReturnSessionDoorCloseWarning] on every new fob scan during an open return
     * session — [beginReturnSessionDoorMonitor] compares against this, not session-start time,
     * so any new scan gives the warning a fresh full window. */
    private val returnSessionLastScanAtEpochMillis = AtomicLong(0L)
    /** Key Take Flow (CLAUDE.md "Terminal App UX Baseline (Production)" §1): guards the whole take, door-open through door-close. */
    private val takeMonitoring = AtomicBoolean(false)
    /** Key Attachment (Part 4): guards the guided attach-a-new-key flow, unlock through secure. */
    private val attachmentMonitoring = AtomicBoolean(false)
    /** Key Attachment missing-fob blink (Part 3) — main-thread-owned, only ever touched from
     * mainHandler's own callbacks, so no extra synchronization is needed on top of that. */
    private val blinkingNodes = mutableSetOf<Int>()
    private var blinkOn = false
    private var blinkLoopScheduled = false

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
    ) = beginKeyTakeInternal(nodeAddress, precedingRedLightOff = false, acquireGuard = true, onDoorOpenConfirmed, onFailure)

    /**
     * Multi-key sequential Take Flow (Key Menu): the exact same sequence as [beginKeyTake],
     * preceded by turning this node's red "waiting in queue" indicator off as one atomic
     * worker-thread operation — the red -> blue "your turn" transition. Sequential, not
     * simultaneous: the red command completes before [beginKeyTakeInternal] sends its own
     * blue-on — nothing in the protocol doc implies both lit at once is meaningful, so this
     * is the conservative default. Every other node still waiting in the queue keeps its red
     * light untouched; nothing here polls to hold it on, it already latches on its own.
     *
     * [isContinuingSession] (Jul 2026, door-stays-open-across-the-queue fix): true for every
     * node after the first in a queue. [takeMonitoring] is now held for the whole multi-key
     * session (see [endTakeSession]), not re-acquired per node — a continuation node must skip
     * the fresh-acquire gate below, since the flag is correctly already true from the first
     * node's take, not from some unrelated concurrent operation.
     */
    fun beginQueuedKeyTake(
        nodeAddress: Int,
        onDoorOpenConfirmed: () -> Unit,
        onFailure: (String) -> Unit,
        isContinuingSession: Boolean = false,
    ) = beginKeyTakeInternal(nodeAddress, precedingRedLightOff = true, acquireGuard = !isContinuingSession, onDoorOpenConfirmed, onFailure)

    private fun beginKeyTakeInternal(
        nodeAddress: Int,
        precedingRedLightOff: Boolean,
        acquireGuard: Boolean,
        onDoorOpenConfirmed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure, checkTakeMonitoring = acquireGuard)) return
        if (acquireGuard && !takeMonitoring.compareAndSet(false, true)) {
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
                // Idempotent door-open, mirroring ensureDoorOpen's existing check-then-eject
                // pattern (Jul 2026 fix, found via ad hoc multi-key queue testing): this
                // previously called ejectDoor() unconditionally on every node, including one
                // still open from a prior node's take in the same queue — a needless physical
                // re-actuation, not a required one (0x23 has no documented behavior tied to
                // re-ejecting an already-open door, and ensureDoorOpen already established the
                // check-first precedent elsewhere in this file). Checking first means a queued
                // take at a node that finds the door already open never re-ejects it.
                var doorStatus = activeLink.checkDoorStatus().data
                if (!isDoorOpen(doorStatus)) {
                    activeLink.ejectDoor()
                    doorStatus = activeLink.checkDoorStatus().data
                }
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
     * Key Attachment screen (Part 5, extended for the 4-state rework): lights every relevant
     * node at once — blue-only for [needsAttachmentNodeAddresses] (web-assigned, no stored fob
     * UID yet), red-only for [alreadyAttachedNodeAddresses] (web-assigned and complete), BOTH
     * blue and red for [availableForRegistrationNodeAddresses] (no web assignment, but a card is
     * physically present — available for new-key registration from the terminal). One command
     * per node, individually, same never-a-batch-command reasoning as
     * [beginMultiKeyRedLightSequence]. Auto-connects if not already connected, same as every
     * other operator-reachable entry point.
     */
    fun lightKeyAttachmentOverview(
        needsAttachmentNodeAddresses: List<Int>,
        alreadyAttachedNodeAddresses: List<Int>,
        availableForRegistrationNodeAddresses: List<Int>,
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
                availableForRegistrationNodeAddresses.forEach {
                    activeLink.blueLightOn(it)
                    activeLink.redLightOn(it)
                }
                publish(currentState.copy(busy = false, message = "Cabinet overview lit."))
                mainHandler.post(onReady)
            } catch (error: Exception) {
                reportCommandFailure("Unable to light the cabinet overview", error, onFailure)
            }
        }
    }

    /**
     * Key Attachment (door pop on node selection): checks door status first (0x22); only ejects
     * (0x23) if it isn't already open — reusing exactly the primitives Take/Return Flow already
     * call directly (e.g. [beginReturnNodeCycle]'s own eject+confirm pair), no new door command.
     * Deliberately does not track/close the door itself afterward — the door is meant to stay
     * open across multiple attachments in one screen visit; only the exit flow
     * ([checkDoorStatusOnly]) reports on it again, and only to prompt a human to close it.
     */
    fun ensureDoorOpen(onReady: () -> Unit, onFailure: (String) -> Unit) {
        if (!canStartOperatorCommand(onFailure)) return
        publish(currentState.copy(busy = true, message = "Checking cabinet door…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                val status = activeLink.checkDoorStatus().data
                if (!isDoorOpen(status)) {
                    activeLink.ejectDoor()
                    val confirmStatus = activeLink.checkDoorStatus().data
                    if (!isDoorOpen(confirmStatus)) {
                        throw IllegalStateException("The cabinet door did not confirm open.")
                    }
                }
                publish(currentState.copy(busy = false, doorStatus = "Door status: open.", message = "Door open."))
                mainHandler.post(onReady)
            } catch (error: Exception) {
                reportCommandFailure("Unable to open the cabinet door", error, onFailure)
            }
        }
    }

    /** Key Attachment's exit check (Part 3, step 1) — reads door status only, never ejects. */
    fun checkDoorStatusOnly(onResult: (isOpen: Boolean) -> Unit, onFailure: (String) -> Unit) {
        if (!canStartOperatorCommand(onFailure)) return
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                val isOpen = isDoorOpen(activeLink.checkDoorStatus().data)
                publish(currentState.copy(busy = false))
                mainHandler.post { onResult(isOpen) }
            } catch (error: Exception) {
                reportCommandFailure("Unable to check the cabinet door", error, onFailure)
            }
        }
    }

    /**
     * Key Attachment's missing-fob alert (Part 1/Part 3): a node whose fob is currently detected
     * missing blinks red regardless of its other state — layered on top of, not a replacement
     * for, the steady blue/red/both lighting elsewhere on this screen. Deliberately NOT a
     * blocking loop on [worker] (that would monopolize the cabinet's one serial-command thread
     * indefinitely, starving every other real command — Take/Return Flow, background sync —
     * for as long as anything is blinking): timing is driven by [mainHandler]'s own
     * `postDelayed`, and each tick submits one quick, non-blocking on/off command per blinking
     * node to [worker] rather than looping there itself.
     */
    fun setMissingFobBlink(nodeAddresses: List<Int>) {
        if (nodeAddresses.isEmpty()) return
        // Blinking red overrides whatever steady light was there — blue is turned off up front
        // so a previously-blue node doesn't also still show blue between red blink pulses.
        worker.execute { nodeAddresses.forEach { runCatching { link?.blueLightOff(it) } } }
        blinkingNodes += nodeAddresses
        if (!blinkLoopScheduled) {
            blinkLoopScheduled = true
            scheduleMissingFobBlinkTick()
        }
    }

    /** Restores a node's steady blue/red light (RED for a physically-attached key, BLUE
     * otherwise) — reused by [resolveMissingFob]/[cancelResolveMissingFob] (both need the exact
     * same "what should this node look like when it's not blinking or mid-operation" logic).
     * Always called from the worker thread — never touches [link] from anywhere else.
     *
     * Formerly also used by a `reconcileMissingFobBlink` acknowledge-and-relight method, removed
     * this pass: Key Attachment's exit-time missing-fob dialog became a mandatory
     * resolve-or-explicitly-override gate (see [resolveMissingFob]'s doc comment), which
     * superseded that method's only call site. */
    private fun relightSteadyState(nodeAddress: Int, physicallyAttached: Boolean) {
        if (physicallyAttached) {
            runCatching { link?.blueLightOff(nodeAddress) }
            runCatching { link?.redLightOn(nodeAddress) }
        } else {
            runCatching { link?.redLightOff(nodeAddress) }
            runCatching { link?.blueLightOn(nodeAddress) }
        }
    }

    /**
     * Key Attachment's exit-check missing-fob dialog (mandatory resolution, not just
     * acknowledgment) — "Unlock node to return key fob": engages this specific node's
     * electromagnet (0x13) so the operator can physically return the fob, then waits for it to
     * be reinserted, reusing exactly [beginKeyAttachment]'s hardware-tested "wait for
     * reinsertion" pattern (the same [ATTACHMENT_TIMEOUT_MILLIS]/[ATTACHMENT_POLL_INTERVAL_MILLIS]
     * budget and [isReadableFobData] success condition) rather than the old instant-success check
     * that pass fixed. Unlike [beginKeyAttachment], there is no "already occupied" branch to
     * consider here — a node only reaches this method because the exit scan already confirmed it
     * reads [KeyFobScanResult.NothingPresent], so this is always the "wait for something to
     * appear" case.
     *
     * Shares [attachmentMonitoring] with [beginKeyAttachment] — the same one-electromagnet-at-a-
     * time hardware guard ([KeyCabinetLink.engageElectromagnet] throws if a different node is
     * already engaged) also means this and a node-attachment flow can never run concurrently,
     * which is correct: both physically move the same one electromagnet-current budget. If
     * multiple nodes are missing, the screen only allows resolving them one at a time anyway
     * (each "Unlock" button disabled while another node's resolution is in progress) — this guard
     * is defense in depth under that, not the only thing enforcing it.
     *
     * On success: re-locks (0x14), relights the node's correct steady state via
     * [relightSteadyState] using [wasPhysicallyAttached] (its blink was never touched by the
     * missing-fob detection itself — this call starts by removing it from [blinkingNodes]), and
     * reports [onResolved]. On timeout or cancellation ([cancelResolveMissingFob]), the node is
     * still genuinely missing — blinking is restored via [setMissingFobBlink] rather than left
     * dark or falsely steady.
     *
     * Does not open the door itself — same convention as [beginKeyAttachment]: the caller
     * (`KeyAttachmentScreen.resolveMissingFobAt`) must confirm the door is open via
     * [ensureDoorOpen] first. Reopening the door here is expected even after Key Attachment's
     * exit-time door-closed gate has already run once — that gate deliberately checks last, only
     * once every missing node is resolved or overridden, precisely so this method remains free
     * to reopen the door as needed in the meantime.
     */
    fun resolveMissingFob(
        nodeAddress: Int,
        wasPhysicallyAttached: Boolean,
        onResolved: () -> Unit,
        onTimeout: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        blinkingNodes -= nodeAddress
        if (!canStartEnrollmentCommand(onFailure)) return
        if (!attachmentMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("Another key attachment/resolution is already in progress.", onFailure)
            setMissingFobBlink(listOf(nodeAddress))
            return
        }
        publish(currentState.copy(busy = true, message = "Unlocking node $nodeAddress to return the key fob…"))
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.engageElectromagnet(nodeAddress)

                val deadline = System.currentTimeMillis() + ATTACHMENT_TIMEOUT_MILLIS
                var resolved = false
                while (attachmentMonitoring.get() && transport.isOpen && System.currentTimeMillis() < deadline) {
                    val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                    if (isReadableFobData(data)) {
                        resolved = true
                        break
                    }
                    Thread.sleep(ATTACHMENT_POLL_INTERVAL_MILLIS)
                }

                if (resolved) {
                    activeLink.releaseElectromagnet(nodeAddress)
                    relightSteadyState(nodeAddress, wasPhysicallyAttached)
                    attachmentMonitoring.set(false)
                    publish(
                        currentState.copy(
                            busy = false,
                            nodeStatus = "Node $nodeAddress: fob returned and secured.",
                            message = "Fob returned and locked at node $nodeAddress.",
                        ),
                    )
                    mainHandler.post(onResolved)
                } else if (!attachmentMonitoring.get()) {
                    // Cancelled via cancelResolveMissingFob() — that call already released the
                    // electromagnet and restored the blink.
                    publish(currentState.copy(busy = false, message = "Fob return cancelled."))
                } else {
                    runCatching { activeLink.releaseElectromagnet(nodeAddress) }
                    attachmentMonitoring.set(false)
                    setMissingFobBlink(listOf(nodeAddress))
                    publish(
                        currentState.copy(
                            busy = false,
                            message = "No fob was returned to node $nodeAddress in time. Still missing.",
                        ),
                    )
                    mainHandler.post(onTimeout)
                }
            } catch (error: Exception) {
                runCatching { link?.releaseElectromagnet(nodeAddress) }
                attachmentMonitoring.set(false)
                setMissingFobBlink(listOf(nodeAddress))
                reportCommandFailure("Unable to resolve the missing fob at node $nodeAddress", error, onFailure)
            }
        }
    }

    /** Cancels an in-progress [resolveMissingFob] — re-locks the node (an unlocked, unattended
     * slot should never be left that way, same reasoning as [cancelKeyAttachment]) and restores
     * the missing-fob blink, since cancelling leaves the fob genuinely still missing. */
    fun cancelResolveMissingFob(nodeAddress: Int) {
        if (!attachmentMonitoring.compareAndSet(true, false)) return
        worker.execute { runCatching { link?.releaseElectromagnet(nodeAddress) } }
        setMissingFobBlink(listOf(nodeAddress))
    }

    private fun scheduleMissingFobBlinkTick() {
        if (blinkingNodes.isEmpty()) {
            blinkLoopScheduled = false
            return
        }
        blinkOn = !blinkOn
        val snapshot = blinkingNodes.toList()
        val turnOn = blinkOn
        worker.execute {
            snapshot.forEach { nodeAddress ->
                runCatching { if (turnOn) link?.redLightOn(nodeAddress) else link?.redLightOff(nodeAddress) }
            }
        }
        mainHandler.postDelayed({ scheduleMissingFobBlinkTick() }, MISSING_FOB_BLINK_INTERVAL_MILLIS)
    }

    /**
     * Key Attachment screen: a generic foreground sweep — one non-unlocking, door-closed-safe
     * read per node (0x17, no eject/engage, same as [autoScanKeyFobs]'s own read but
     * deliberately no per-node light flash here: this sweep runs with the screen already open,
     * so its result feeds straight into a light command afterward rather than needing its own
     * visual confirmation mid-sweep). Reused for two distinct purposes by the caller:
     * - **Discovery** (unassigned nodes, entry): a [KeyFobScanResult.CardRead] means "available
     *   for new registration"; anything else means OFF/unlit.
     * - **Missing-fob check** (assigned nodes, exit): a [KeyFobScanResult.NothingPresent] means
     *   the previously-known fob is gone; [KeyFobScanResult.Failed] is a transient read problem,
     *   never itself proof of absence — the caller must not conflate the two.
     * Each node's read is individually try/caught (unlike the older wrong-slot sweep, which lets
     * one node's failure abort the whole poll) — appropriate here since this is a one-shot check
     * rather than a timing-critical loop, and a single hiccup must not read as "missing."
     */
    fun scanNodes(
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
                    val result = try {
                        val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                        when {
                            data.isFourBytesOf(0x00) -> KeyFobScanResult.BoltPresentNoCard
                            data.isFourBytesOf(0xFF) -> KeyFobScanResult.NothingPresent
                            else -> KeyFobScanResult.CardRead(data.toCompactHex())
                        }
                    } catch (nodeError: Exception) {
                        KeyFobScanResult.Failed(nodeError.message ?: nodeError.javaClass.simpleName)
                    }
                    mainHandler.post { onNodeResult(nodeAddress, result) }
                }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Node scan could not connect: ${error.detail()}")
            } finally {
                mainHandler.post(onSweepComplete)
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
     *
     * **Bug fix (Jul 2026, found via ad hoc hardware testing — the door-stays-open-across-
     * the-queue fix did not actually work on real hardware, still reported "close the door to
     * unlock the next key"):** this used to be one `while` loop with `Thread.sleep` between
     * checks, all inside a single `worker.execute {}` call — since [worker] is a
     * single-threaded executor (one physical serial port, by design), that one call
     * monopolized the only thread for the *entire* poll duration. A queued node's own
     * [beginQueuedKeyTake] — submitted to the same [worker] once the *previous* node's key was
     * removed — could not run a single command until this loop actually exited, which only
     * happens on removal, abandonment, or (here, the real bug) never, since the *next* node's
     * poll used the identical pattern once it got a turn. The app-level "advance on removal, let
     * this node's own monitoring continue independently in the background" design (see
     * `TerminalAdminApp.kt`'s `onKeyRemoved`) was correct; the two polling primitives
     * underneath it were not actually capable of running concurrently with anything else on
     * the same thread. Restructured into one short check per [worker] submission, rescheduled
     * via [mainHandler]`.postDelayed` instead of blocking inside the executor — between one
     * check and the next, the worker thread is genuinely free for another node's commands to
     * interleave. Still only ever one command in flight on the serial port at any instant (the
     * single-threaded executor guarantee is unchanged); it's the *idle time between* checks
     * that's no longer wasted holding the thread hostage. Every threshold/callback/message is
     * unchanged, only the mechanism that reaches them.
     */
    fun pollForKeyRemoval(
        nodeAddress: Int,
        onRemoved: () -> Unit,
        onLouderBeepThreshold: () -> Unit,
        onAbandoned: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val startedAtMillis = System.currentTimeMillis()
        var louderBeepFired = false

        lateinit var pollStep: () -> Unit
        pollStep = {
            runCatching {
                worker.execute {
                    try {
                        if (!takeMonitoring.get()) {
                            // Stopped externally (stopMonitoring/disconnect/close) — exit
                            // silently, matching the old loop's own exit-without-callback
                            // behavior for this exact case.
                            return@execute
                        }
                        if (!transport.isOpen) {
                            takeMonitoring.set(false)
                            throw IllegalStateException("Cabinet connection closed while waiting for key removal.")
                        }
                        val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                        val elapsedMillis = System.currentTimeMillis() - startedAtMillis
                        val status = activeLink.testMicroSwitch(nodeAddress).data
                        if (status.isFourBytesOf(0xFF)) {
                            // Jul 2026 fix (see Completed entry): the electromagnet guard
                            // (KeyCabinetLink.engagedNodeAddress) is released here, on this same
                            // worker submission, before onRemoved is posted — the abandonment
                            // branch below does the same. Door-close/blueLightOff monitoring is
                            // unaffected — happens independently in waitForDoorCloseAfterTake.
                            activeLink.releaseElectromagnet(nodeAddress)
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

                        mainHandler.postDelayed({ pollStep() }, KEY_REMOVAL_POLL_INTERVAL_MILLIS)
                    } catch (error: Exception) {
                        takeMonitoring.set(false)
                        reportCommandFailure("Unable to monitor key removal at node $nodeAddress", error, onFailure)
                    }
                }
            }
            // A rejected submission means worker was already shut down (close()) between this
            // step being scheduled and firing — nothing left to report to; drop silently rather
            // than crash on the main thread.
        }
        pollStep()
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
     * after the warning, and turns the node's light off — but, as of the
     * Jul 2026 door-stays-open-across-the-queue fix, does **not** release
     * [takeMonitoring] itself anymore: a queued multi-key session can have
     * more than one node's door-close-wait concurrently pending (the queue
     * now advances to the next node's engage on confirmed removal, not on
     * this node's own door-close — see [beginQueuedKeyTake]), so no single
     * node's closing can safely assume it's the last. The caller releases
     * the guard exactly once, via [endTakeSession], only once it knows the
     * whole session is done. Also **not** where the electromagnet-level
     * guard clears — that's [pollForKeyRemoval]'s confirmed-removal
     * branch, a distinct fact from `KeyCabinetLink.engagedNodeAddress`
     * this doc previously conflated with `takeMonitoring` (bug found Jul
     * 2026: the electromagnet guard was never released on the success
     * path at all until that fix).
     *
     * **Bug fix (Jul 2026, same pass as [pollForKeyRemoval]'s — the door-stays-open-across-
     * the-queue fix still did not work on real hardware):** this was one `while` loop with
     * `Thread.sleep` between checks, all inside a single `worker.execute {}` call. Since
     * [worker] is single-threaded, a queued node's own door-close-wait — meant to run
     * independently in the background while the *next* node proceeds — actually monopolized
     * the only thread for its entire duration, so the next node's [beginQueuedKeyTake] (also
     * submitted to [worker]) could not run a single command until *this* door closed. That is
     * exactly backwards from the intent and is what produced "the next key only unlocks after
     * closing this one's door, then it immediately pops." Restructured the same way as
     * [pollForKeyRemoval]: one short check per [worker] submission, rescheduled via
     * [mainHandler]`.postDelayed` rather than blocking inside the executor, so the thread is
     * genuinely free between checks for another node's commands to interleave.
     */
    fun waitForDoorCloseAfterTake(
        nodeAddress: Int,
        warningSeconds: Int,
        onWarningExpired: () -> Unit,
        onDoorClosed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val startedAtMillis = System.currentTimeMillis()
        val warningMillis = warningSeconds * 1_000L
        var warningFired = false

        lateinit var pollStep: () -> Unit
        pollStep = {
            runCatching {
                worker.execute {
                    try {
                        if (!takeMonitoring.get()) {
                            // Stopped externally — exit silently, same as before this fix.
                            return@execute
                        }
                        if (!transport.isOpen) {
                            takeMonitoring.set(false)
                            throw IllegalStateException("Cabinet connection closed while waiting for the door to close.")
                        }
                        val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                        val status = activeLink.checkDoorStatus().data
                        if (!isDoorOpen(status)) {
                            activeLink.blueLightOff(nodeAddress)
                            // takeMonitoring is deliberately NOT reset here (Jul 2026, door-stays-
                            // open-across-the-queue fix) — a queued session can have more than one
                            // node's door-close-wait concurrently pending, and this node closing
                            // must never clear the flag out from under a still-pending sibling.
                            // The caller releases the guard exactly once, via endTakeSession(),
                            // only once it knows the whole session is done.
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

                        mainHandler.postDelayed({ pollStep() }, DOOR_CLOSE_POLL_INTERVAL_MILLIS)
                    } catch (error: Exception) {
                        takeMonitoring.set(false)
                        reportCommandFailure("Unable to confirm the door closed for node $nodeAddress", error, onFailure)
                    }
                }
            }
            // Rejected submission means worker was already shut down between scheduling and
            // firing this step — nothing left to report to; drop silently rather than crash.
        }
        pollStep()
    }

    /**
     * Explicit "the whole take session is now finished" signal (Jul 2026, door-stays-open-
     * across-the-queue fix) — releases [takeMonitoring]. The caller (`TerminalAdminApp.kt`)
     * calls this exactly once per session: from the single-key Take Flow's own completion, or
     * from a multi-key queue's genuinely last node's completion — never per node, since
     * [waitForDoorCloseAfterTake] no longer releases the guard itself (a queued session can have
     * more than one node's door-close-wait concurrently pending, and none of them can safely
     * assume it's the last).
     */
    fun endTakeSession() {
        takeMonitoring.set(false)
    }

    /**
     * Return Flow session rebuild (Jul 2026, full scrap-and-rebuild per explicit instruction —
     * supersedes the per-key [beginKeyReturnFlow]/[waitForDoorCloseAfterReturn] shape entirely,
     * not layered on top of it): Blue Light On (0x11) -> Engage electromagnet (0x13, unlock) at
     * [nodeAddress] -> idempotent door check (Check Door Status 0x22; Eject Door 0x23 only if
     * not already open, same check-then-eject-only-if-closed pattern [ensureDoorOpen]/
     * [beginKeyTakeInternal] already use — a session's *second* and later scans find the door
     * already open and skip the eject entirely).
     *
     * Called once per fob scan during an open return session — first scan (door closed) and
     * every subsequent scan (door already open from an earlier key in this same session) both
     * go through this one function; there is no separate "first scan" path. Acquires
     * [returnMonitoring] for the duration of *this node's* cycle only (unlock through
     * insertion-confirmed-or-abandoned, released by [pollForKeyInsertion]) — a genuinely
     * shorter-lived guard than before, since the flow no longer waits on door-close per key.
     * The session-wide guard is the separate [returnSessionMonitoring], acquired by
     * [beginReturnSessionDoorMonitor] once per session, not here.
     */
    fun beginReturnNodeCycle(
        nodeAddress: Int,
        onNodeUnlocked: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure)) return
        if (!returnMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("A key return is already in progress at another node.", onFailure)
            return
        }
        activeReturnNodeAddress = nodeAddress
        publish(currentState.copy(busy = true, message = "Lighting node $nodeAddress and unlocking the slot…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.engageElectromagnet(nodeAddress)

                val doorStatus = activeLink.checkDoorStatus().data
                if (!isDoorOpen(doorStatus)) {
                    activeLink.ejectDoor()
                    val confirmStatus = activeLink.checkDoorStatus().data
                    if (!isDoorOpen(confirmStatus)) {
                        throw IllegalStateException("The cabinet door did not confirm open for node $nodeAddress.")
                    }
                }

                publish(
                    currentState.copy(
                        busy = false,
                        doorStatus = "Door status: open.",
                        message = "Node $nodeAddress unlocked. Waiting for the key to be inserted…",
                    ),
                )
                mainHandler.post(onNodeUnlocked)
            } catch (error: Exception) {
                // A failure after the electromagnet engage above must not leave the node's latch
                // unlocked with no active poll watching it — mirrors beginKeyTakeInternal's own
                // cleanup shape.
                runCatching { link?.releaseElectromagnet(nodeAddress) }
                runCatching { link?.blueLightOff(nodeAddress) }
                activeReturnNodeAddress = null
                returnMonitoring.set(false)
                reportCommandFailure("Unable to begin the key return at node $nodeAddress", error, onFailure)
            }
        }
    }

    /**
     * Polls Test Micro Switch (0x16) for bolt presence (key inserted) at [nodeAddress] from the
     * moment its cycle was unlocked. Two independent timers on two different clocks — this is
     * the deliberate asymmetry with [pollForKeyRemoval], not an oversight:
     * - [INSERTION_LOUDER_BEEP_THRESHOLD_MILLIS] (5 s), measured from *this call's start* (i.e.
     *   from node-unlock): if the key is still not inserted, [onLouderBeepThreshold] fires
     *   exactly once — volume only, never resets or extends the ceiling below.
     * - [abandonAtEpochMillis], an absolute wall-clock deadline the caller computed at the
     *   *original card swipe* (not from node-unlock, and not reset by however long any Key
     *   Return Certification login in between took) — the hard per-node ceiling. If the key is
     *   still not inserted here, the node is locked (0x14) and its light turned off before
     *   [onAbandoned] fires — **this ends only this node's own cycle, not the whole session**
     *   (Return Flow session rebuild, Jul 2026) — the door stays open and the session keeps
     *   listening for the next scan.
     * Insertion at any point before the deadline locks the fob (0x14) **and now also turns the
     * node's light off immediately** (Return Flow session rebuild, Jul 2026 — moved here from
     * the old per-key [waitForDoorCloseAfterReturn], which no longer exists: this node's cycle
     * is genuinely done at insertion now, since the door no longer has to close between keys)
     * before calling [onInserted].
     *
     * **Threading (Return Flow session rebuild, Jul 2026): converted from a blocking
     * `while`+`Thread.sleep` loop inside one [worker] submission to one short check per
     * submission, rescheduled via [mainHandler]`.postDelayed` — the same fix already applied to
     * [pollForKeyRemoval]/[waitForDoorCloseAfterTake], and for the identical reason: this poll
     * can now run for up to [RETURN_FLOW_ABANDONMENT_TIMEOUT_MILLIS] concurrently with
     * [beginReturnSessionDoorMonitor]'s own session-long poll on the same single-threaded
     * [worker] (the session monitor starts once and keeps running through however many node
     * cycles happen inside it), so neither loop may block the thread for its own entire
     * duration. This was flagged as a latent risk, not yet live, in a prior audit note — this
     * rebuild is the redesign that note warned would make it live, so the fix ships with it.**
     *
     * Return Flow rework — wrong-slot detection (unchanged by this rebuild, per instruction):
     * once the door is open, every physical key hook inside is reachable, not just
     * [nodeAddress]'s — the protocol has no single command that reports "which node changed,"
     * so each poll cycle also sweeps [wrongSlotCandidateNodeAddresses] (every *other* node this
     * cabinet actually has a KeySlot for — not the full 1..127 address space) for an unexpected
     * bolt-present transition via Test Micro Switch *and Card* (0x17): a readable 4-byte value
     * is a card UID, all-`0x00` is bolt-present-no-card, all-`0xFF` is nothing present.
     * [onWrongSlotDetected]/[onWrongSlotCleared] fire only on a state change. A readable UID is
     * resolved via [resolveKeyFobUid] (the caller's `CardUidResolver` lookup — this class holds
     * no UID store itself, boundary #2) to confirm a genuinely enrolled key; unresolved presence
     * still alarms, per the established "equally anomalous" design intent.
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
        val startedAtMillis = System.currentTimeMillis()
        val sweepCandidates = wrongSlotCandidateNodeAddresses.filter { it != nodeAddress }
        var louderBeepFired = false
        var wrongSlotNode: Int? = null

        lateinit var pollStep: () -> Unit
        pollStep = {
            runCatching {
                worker.execute {
                    try {
                        if (!returnMonitoring.get()) return@execute
                        if (!transport.isOpen) {
                            returnMonitoring.set(false)
                            throw IllegalStateException("Cabinet connection closed while waiting for key insertion.")
                        }
                        val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                        val elapsedSinceUnlockMillis = System.currentTimeMillis() - startedAtMillis
                        val status = activeLink.testMicroSwitch(nodeAddress).data
                        if (status.isFourBytesOf(0x00)) {
                            if (wrongSlotNode != null) {
                                runCatching { activeLink.redLightOff(wrongSlotNode!!) }
                            }
                            activeLink.releaseElectromagnet(nodeAddress)
                            activeLink.blueLightOff(nodeAddress)
                            activeReturnNodeAddress = null
                            returnMonitoring.set(false)
                            publish(
                                currentState.copy(
                                    busy = false,
                                    nodeStatus = "Node $nodeAddress: key inserted and locked.",
                                    message = "Key inserted and locked. Ready for the next scan.",
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

                        if (!louderBeepFired && elapsedSinceUnlockMillis >= INSERTION_LOUDER_BEEP_THRESHOLD_MILLIS) {
                            louderBeepFired = true
                            mainHandler.post(onLouderBeepThreshold)
                        }

                        if (System.currentTimeMillis() >= abandonAtEpochMillis) {
                            if (wrongSlotNode != null) {
                                runCatching { activeLink.redLightOff(wrongSlotNode!!) }
                            }
                            activeLink.releaseElectromagnet(nodeAddress)
                            activeLink.blueLightOff(nodeAddress)
                            activeReturnNodeAddress = null
                            returnMonitoring.set(false)
                            publish(
                                currentState.copy(
                                    busy = false,
                                    nodeStatus = "Node $nodeAddress: key return abandoned.",
                                    message = "Key return abandoned at node $nodeAddress — no key was ever inserted.",
                                ),
                            )
                            mainHandler.post(onAbandoned)
                            return@execute
                        }

                        mainHandler.postDelayed({ pollStep() }, KEY_INSERTION_POLL_INTERVAL_MILLIS)
                    } catch (error: Exception) {
                        activeReturnNodeAddress = null
                        returnMonitoring.set(false)
                        reportCommandFailure("Unable to monitor key insertion at node $nodeAddress", error, onFailure)
                    }
                }
            }
        }
        pollStep()
    }

    /**
     * Return Flow session rebuild (Jul 2026) — supersedes the old per-key
     * `waitForDoorCloseAfterReturn`, whose entire *reason to exist* was "door-close ends this
     * one return"; that assumption no longer holds, since the door now stays open across
     * however many keys are returned in one session. This is the session's own poll, started
     * once (idempotent — a no-op if already running, so every node's unlock can call it without
     * double-starting) from the first node's confirmed unlock, and running continuously,
     * independent of and concurrently with whichever node's [pollForKeyInsertion] is currently
     * active, until the door is confirmed physically closed (Check Door Status, 0x22) —
     * **that is the session's sole ending trigger** (constraint 7: no Done button, no idle
     * timeout).
     *
     * Also owns the session-level Door-Close Warning Time countdown — a *separate* concept from
     * the per-node abandonment ceiling above: it measures time since the *last new scan*
     * ([resetReturnSessionDoorCloseWarning], called by the app layer on every fob scan, first or
     * subsequent), not time since session start, and resets to full on every scan rather than
     * counting once. Firing [onWarningExpired] is a **warning only** — it does not end the
     * session, and a scan afterward both resets the countdown and continues working normally.
     * Fires at most once per distinct reset (tracked by comparing the live reset anchor against
     * the last value warned for), not once per poll tick.
     *
     * If the door is still open when a node was mid-cycle (unlocked, awaiting insertion) at the
     * moment this poll detects closure, that node's cycle is force-ended here — electromagnet
     * released, light off — rather than left with an unlocked latch and no poll watching it;
     * [pollForKeyInsertion]'s own loop then exits silently on its next scheduled tick (sees
     * [returnMonitoring] already cleared), the same "stopped externally" exit
     * [pollForKeyRemoval] already uses.
     *
     * **Threading**: reschedule-based from the start (see [pollForKeyInsertion]'s doc for why
     * this is now a hard requirement, not a latent one) — this poll and whichever node's
     * [pollForKeyInsertion] is active both need to interleave on the same single-threaded
     * [worker] for however long the session runs.
     */
    fun beginReturnSessionDoorMonitor(
        doorCloseWarningSeconds: Int,
        onWarningExpired: () -> Unit,
        onSessionDoorClosed: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!returnSessionMonitoring.compareAndSet(false, true)) return
        returnSessionDoorCloseWarningMillis = doorCloseWarningSeconds * 1_000L
        returnSessionLastScanAtEpochMillis.set(System.currentTimeMillis())
        var lastWarnedAnchor = -1L

        lateinit var pollStep: () -> Unit
        pollStep = {
            runCatching {
                worker.execute {
                    try {
                        if (!returnSessionMonitoring.get()) return@execute
                        if (!transport.isOpen) {
                            returnSessionMonitoring.set(false)
                            throw IllegalStateException("Cabinet connection closed while monitoring the return session.")
                        }
                        val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                        val status = activeLink.checkDoorStatus().data
                        if (!isDoorOpen(status)) {
                            activeReturnNodeAddress?.let { staleNode ->
                                runCatching { activeLink.releaseElectromagnet(staleNode) }
                                runCatching { activeLink.blueLightOff(staleNode) }
                            }
                            activeReturnNodeAddress = null
                            returnMonitoring.set(false)
                            returnSessionMonitoring.set(false)
                            publish(
                                currentState.copy(
                                    busy = false,
                                    doorStatus = "Door status: closed / not engaged.",
                                    message = "Return session ended — door closed.",
                                ),
                            )
                            mainHandler.post(onSessionDoorClosed)
                            return@execute
                        }

                        val anchor = returnSessionLastScanAtEpochMillis.get()
                        if (anchor != lastWarnedAnchor &&
                            System.currentTimeMillis() - anchor >= returnSessionDoorCloseWarningMillis
                        ) {
                            lastWarnedAnchor = anchor
                            mainHandler.post(onWarningExpired)
                        }

                        mainHandler.postDelayed({ pollStep() }, RETURN_DOOR_CLOSE_POLL_INTERVAL_MILLIS)
                    } catch (error: Exception) {
                        returnSessionMonitoring.set(false)
                        reportCommandFailure("Unable to monitor the return session door", error, onFailure)
                    }
                }
            }
        }
        pollStep()
    }

    /**
     * Called by the app layer on every fob scan during an open return session (first scan or
     * any subsequent one) — resets [beginReturnSessionDoorMonitor]'s Door-Close Warning Time
     * countdown to a fresh window from now. No-op if no session is currently open.
     */
    fun resetReturnSessionDoorCloseWarning() {
        returnSessionLastScanAtEpochMillis.set(System.currentTimeMillis())
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
     * resting in the cabinet.
     *
     * **Hardware-tested bug fix, this pass**: this function is shared by both a genuinely empty
     * blue node and the amber "available for registration" node (which, by construction, already
     * has a fob physically resting in it — that's how it was discovered). The original version
     * unconditionally polled "wait until a readable fob is present," which is correct for an
     * empty slot but was trivially, instantly satisfied for an already-occupied one — the unlock
     * (0x13) was torn down by a re-lock (0x14) within a single 0x17 round-trip, far too fast for
     * a human to physically pull the fob out. Fixed by branching on an immediate presence check
     * right after unlock:
     * - Not present (0xFF): unchanged — wait for a NEW fob to be clipped on, same as before.
     * - Already present (the common case for this hardware): a two-phase wait instead — (a) wait
     *   for the existing fob to be physically REMOVED (0x17 turns to 0xFF), then (b) wait for it
     *   (or a different fob) to be REINSERTED (0x17 becomes readable again). Only re-lock (0x14)
     *   once phase (b) succeeds, so the slot stays genuinely unlocked for the whole real physical
     *   task (pull fob out, attach the real key to it, put it back), not one instant tick.
     *   [onSlotState] reports which branch was taken (fired once, right after the presence
     *   check) and [onRemovalConfirmed] reports the phase (a)→(b) transition (occupied case
     *   only) — both purely for the caller's on-screen guidance text, no behavior depends on
     *   either being observed. Each phase gets its own full [ATTACHMENT_TIMEOUT_MILLIS] budget
     *   rather than splitting one budget across both — removing the fob, attaching a real key to
     *   it, and bringing it back is a genuinely longer physical task than clipping on a brand-new
     *   fob (what that constant was originally sized for); flagged as an easy constant to retune
     *   if this proves too generous or not generous enough on real hardware.
     * - [onAttached]'s `uidChangedFromPrevious` flag is set when the occupied case's reinserted
     *   UID differs from the one read during the initial presence check — a different physical
     *   fob than what was removed came back. Never blocks completion (the operator may
     *   legitimately be threading a different fob on) but is surfaced as an on-screen warning
     *   rather than silently treated as identical. Always `false` for the empty-slot case (there
     *   is nothing to compare against) and for the occupied case's `BoltPresentNoCard` initial
     *   read (no UID was ever captured to compare).
     *
     * Does not eject the door itself — like the old flow, that happens once per screen entry via
     * [openKeyEnrollmentSession] (or, for the reworked Key Attachment screen, [ensureDoorOpen]);
     * this only operates the one selected node.
     */
    fun beginKeyAttachment(
        nodeAddress: Int,
        onSlotState: (occupied: Boolean) -> Unit,
        onRemovalConfirmed: () -> Unit,
        onAttached: (rawUidHex: String, uidChangedFromPrevious: Boolean) -> Unit,
        onTimeout: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartEnrollmentCommand(onFailure)) return
        if (!attachmentMonitoring.compareAndSet(false, true)) {
            notifyCommandFailure("A key attachment is already in progress.", onFailure)
            return
        }
        publish(currentState.copy(busy = true, message = "Unlocking node $nodeAddress…"))
        worker.execute {
            try {
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.blueLightOn(nodeAddress)
                activeLink.engageElectromagnet(nodeAddress)

                val initialData = activeLink.testMicroSwitchAndCard(nodeAddress).data
                val initiallyOccupied = !initialData.isFourBytesOf(0xFF)
                val initialUid = initialData.takeIf { isReadableFobData(it) }?.toCompactHex()
                mainHandler.post { onSlotState(initiallyOccupied) }

                var uidHex: String? = null
                var uidChanged = false

                if (!initiallyOccupied) {
                    // Empty slot — unchanged behavior: wait for a NEW fob to be clipped on.
                    val deadline = System.currentTimeMillis() + ATTACHMENT_TIMEOUT_MILLIS
                    while (attachmentMonitoring.get() && transport.isOpen && System.currentTimeMillis() < deadline) {
                        val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                        if (isReadableFobData(data)) {
                            uidHex = data.toCompactHex()
                            break
                        }
                        Thread.sleep(ATTACHMENT_POLL_INTERVAL_MILLIS)
                    }
                } else {
                    // Phase (a): wait for the existing fob to be physically removed.
                    val removalDeadline = System.currentTimeMillis() + ATTACHMENT_TIMEOUT_MILLIS
                    var removed = false
                    while (attachmentMonitoring.get() && transport.isOpen && System.currentTimeMillis() < removalDeadline) {
                        val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                        if (data.isFourBytesOf(0xFF)) {
                            removed = true
                            break
                        }
                        Thread.sleep(ATTACHMENT_POLL_INTERVAL_MILLIS)
                    }

                    if (removed && attachmentMonitoring.get()) {
                        mainHandler.post(onRemovalConfirmed)
                        // Phase (b): wait for it (or a different fob) to be reinserted.
                        val reinsertDeadline = System.currentTimeMillis() + ATTACHMENT_TIMEOUT_MILLIS
                        while (attachmentMonitoring.get() && transport.isOpen && System.currentTimeMillis() < reinsertDeadline) {
                            val data = activeLink.testMicroSwitchAndCard(nodeAddress).data
                            if (isReadableFobData(data)) {
                                uidHex = data.toCompactHex()
                                uidChanged = initialUid != null && uidHex != initialUid
                                break
                            }
                            Thread.sleep(ATTACHMENT_POLL_INTERVAL_MILLIS)
                        }
                    }
                }

                if (uidHex != null) {
                    activeLink.releaseElectromagnet(nodeAddress)
                    activeLink.blueLightOff(nodeAddress)
                    attachmentMonitoring.set(false)
                    publish(
                        currentState.copy(
                            busy = false,
                            nodeStatus = "Node $nodeAddress: fob attached and secured.",
                            message = "Key attached and locked at node $nodeAddress.",
                        ),
                    )
                    val capturedUid = uidHex
                    val capturedChanged = uidChanged
                    mainHandler.post { onAttached(capturedUid, capturedChanged) }
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
        returnSessionMonitoring.set(false)
        activeReturnNodeAddress = null
        takeMonitoring.set(false)
        transport.close()
        link = null
        worker.shutdownNow()
    }

    private fun runCommand(
        startingMessage: String,
        command: (KeyCabinetLink) -> CabinetHardwareState,
    ) {
        if (returnMonitoring.get() || returnSessionMonitoring.get()) {
            publish(currentState.copy(message = "A key return session is active. Wait until it finishes."))
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
            returnMonitoring.get() || returnSessionMonitoring.get() -> "A key return session is active."
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
    private fun canStartOperatorCommand(onFailure: (String) -> Unit, checkTakeMonitoring: Boolean = true): Boolean {
        val problem = when {
            returnMonitoring.get() || returnSessionMonitoring.get() -> "A key return session is active."
            // Skipped for a queued take's continuation nodes (see beginQueuedKeyTake's
            // isContinuingSession) — takeMonitoring is deliberately still true there, held for
            // the whole multi-key session, not per node; it isn't "someone else's take."
            checkTakeMonitoring && takeMonitoring.get() -> "A key take is already in progress."
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
