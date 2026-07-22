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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val transport = AndroidSerialTransport()
    private val returnMonitoring = AtomicBoolean(false)

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
     * Section 2 (key retrieval): unlocks the electromagnet at [nodeAddress]
     * (0x13 — field-verified to free the key peg for pickup; see
     * docs/Key_Cabinet_Communication_Protocol.md) then confirms via Test
     * Micro Switch (0x16) that the bolt was actually removed before
     * reporting success — an acknowledged command is not treated as proof
     * the key was really taken. Connects the cabinet with its saved/default
     * settings first if it isn't already open, since an ordinary operator
     * reaches this directly from login, not through the admin hardware
     * console's manual connect step.
     */
    fun releaseKeyForPickup(
        nodeAddress: Int,
        onReleased: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!canStartOperatorCommand(onFailure)) return
        publish(currentState.copy(busy = true, message = "Releasing key at node $nodeAddress…"))
        worker.execute {
            try {
                ensureConnectedOnWorker()
                val activeLink = requireNotNull(link) { "Cabinet protocol is unavailable." }
                activeLink.engageElectromagnet(nodeAddress)

                val status = activeLink.testMicroSwitch(nodeAddress).data
                if (!status.isFourBytesOf(0xFF)) {
                    throw IllegalStateException("Node $nodeAddress did not confirm the key was removed.")
                }

                publish(
                    currentState.copy(
                        busy = false,
                        nodeStatus = "Node $nodeAddress: key removed and confirmed.",
                        message = "Key released for pickup.",
                    ),
                )
                mainHandler.post(onReleased)
            } catch (error: Exception) {
                reportCommandFailure("Unable to release the key at node $nodeAddress", error, onFailure)
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
     * Stops whichever return monitor is running — enrollment's
     * [waitForReturnedKeyFob] or retrieval/return's [waitForKeyInserted] —
     * without itself changing a peg state.
     */
    fun stopMonitoring() {
        returnMonitoring.set(false)
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

    private fun Exception.detail(): String =
        message ?: javaClass.simpleName
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
