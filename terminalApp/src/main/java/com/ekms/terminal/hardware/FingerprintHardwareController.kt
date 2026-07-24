package com.ekms.terminal.hardware

import android.os.Handler
import android.os.Looper
import com.ekms.shared.protocol.R503FingerprintProtocol
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the R503 fingerprint module's serial port (`/dev/ttyS0`) and background executor,
 * parallel to [CabinetHardwareController]'s ownership of `/dev/ttyS1` — a fully separate
 * physical device and port with its own [AndroidSerialTransport] instance, never shared with
 * the cabinet link or the public card reader.
 *
 * Frame assembly, checksums, and the AutoEnroll/AutoIdentify command sequences all live in the
 * shared, hardware-free-testable [R503FingerprintProtocol]; this class only owns the real
 * Android serial port, the background executor that serializes commands onto it, and the one
 * piece of orchestration that couldn't live in `shared` — polling for finger presence with a
 * real delay between attempts (`shared` is Kotlin Multiplatform and has no cross-platform sleep
 * primitive available to a plain blocking call; see [R503FingerprintProtocol]'s class doc).
 *
 * Scope: enrollment + template management only, matching terminalApp's current feature — no
 * runtime fingerprint-based login/matching is wired up here. [R503FingerprintProtocol.verifyTemplateOnce]/
 * [R503FingerprintProtocol.autoIdentify] exist for future use but have no caller from this class yet.
 */
class FingerprintHardwareController(
    private val onStateChanged: (FingerprintHardwareState) -> Unit,
) {
    companion object {
        private const val FINGER_DETECTION_TIMEOUT_MILLIS = 10_000L
        private const val FINGER_DETECTION_POLL_INTERVAL_MILLIS = 250L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val transport = AndroidSerialTransport()

    @Volatile
    private var currentState = FingerprintHardwareState()
    private var protocol: R503FingerprintProtocol? = null

    fun connect(
        portPath: String = R503FingerprintProtocol.PORT_PATH,
        baudRate: Int = R503FingerprintProtocol.BAUD_RATE,
    ) {
        publish(currentState.copy(busy = true, message = "Opening fingerprint sensor port…"))
        worker.execute {
            try {
                transport.open(portPath, baudRate)
                val link = R503FingerprintProtocol(transport)
                link.requireSuccessfulAcknowledgement(link.handshake())
                protocol = link
                publish(
                    currentState.copy(
                        connected = true,
                        busy = false,
                        message = "Fingerprint sensor connected at $portPath @ $baudRate baud.",
                    ),
                )
            } catch (error: Exception) {
                transport.close()
                protocol = null
                publish(
                    currentState.copy(
                        connected = false,
                        busy = false,
                        message = "Fingerprint sensor connection failed: ${error.detail()}",
                    ),
                )
            }
        }
    }

    fun disconnect() {
        publish(currentState.copy(busy = true, message = "Closing fingerprint sensor port…"))
        worker.execute {
            transport.close()
            protocol = null
            publish(currentState.copy(connected = false, busy = false, message = "Fingerprint sensor disconnected."))
        }
    }

    /** Full teardown (transport + executor) for when the owning composable leaves composition. */
    fun close() {
        transport.close()
        protocol = null
        worker.shutdownNow()
    }

    /**
     * Auto-connects with defaults if not already connected (mirrors [CabinetHardwareController]'s
     * guided flows, which are reached directly from a menu with no separate admin "Connect" step
     * first), waits for a first finger presence, then runs the R503's own AutoEnroll (0x31)
     * 6-scan cycle, publishing step-by-step progress. The sensor auto-assigns the template ID —
     * callers must persist the (userId, templateId) mapping themselves (see
     * [FingerprintTemplateStore]) since the R503 only knows slots, not who they belong to.
     */
    fun enrollFingerprint(
        onProgress: (String) -> Unit,
        onOutcome: (FingerprintEnrollmentOutcome) -> Unit,
    ) {
        publish(currentState.copy(busy = true, message = "Preparing fingerprint sensor…"))
        worker.execute {
            try {
                val link = ensureConnectedOnWorker()
                mainHandler.post { onProgress("Place a finger on the sensor…") }

                val detected = waitForFinger(
                    link,
                    FINGER_DETECTION_TIMEOUT_MILLIS,
                    FINGER_DETECTION_POLL_INTERVAL_MILLIS,
                )
                if (!detected) {
                    val message = "No finger detected within 10 seconds."
                    publish(currentState.copy(busy = false, message = message))
                    mainHandler.post { onOutcome(FingerprintEnrollmentOutcome.Failed(message)) }
                    return@execute
                }

                val result = link.autoEnroll { progress ->
                    mainHandler.post { onProgress(enrollmentStepText(progress.step)) }
                }

                val confirmationCode = result.confirmationCode
                if (confirmationCode != 0x00) {
                    val message = "Enrollment failed: " +
                        R503FingerprintProtocol.confirmationCodeDescription(confirmationCode ?: -1)
                    publish(currentState.copy(busy = false, message = message))
                    mainHandler.post { onOutcome(FingerprintEnrollmentOutcome.Failed(message)) }
                    return@execute
                }

                val templateId = result.storedTemplateId
                if (templateId == null) {
                    val message = "Enrollment completed, but the sensor did not report a template ID."
                    publish(currentState.copy(busy = false, message = message))
                    mainHandler.post { onOutcome(FingerprintEnrollmentOutcome.Failed(message)) }
                    return@execute
                }

                publish(currentState.copy(busy = false, message = "Fingerprint saved in template $templateId."))
                mainHandler.post { onOutcome(FingerprintEnrollmentOutcome.Success(templateId)) }
            } catch (error: Exception) {
                val message = "Enrollment failed: ${error.detail()}"
                publish(currentState.copy(busy = false, message = message))
                mainHandler.post { onOutcome(FingerprintEnrollmentOutcome.Failed(message)) }
            }
        }
    }

    /** Deletes one template slot from the sensor's own library (0x0C) — used by revoke. */
    fun deleteTemplate(templateId: Int, onOutcome: (success: Boolean, message: String) -> Unit) {
        publish(currentState.copy(busy = true, message = "Deleting template $templateId…"))
        worker.execute {
            try {
                val link = ensureConnectedOnWorker()
                val transaction = link.deleteSingleTemplate(templateId)
                val confirmationCode = transaction.responsePacket.confirmationCode
                val success = confirmationCode == 0x00
                val message = if (success) {
                    "Template $templateId deleted."
                } else {
                    "Delete failed: " + R503FingerprintProtocol.confirmationCodeDescription(confirmationCode ?: -1)
                }
                publish(currentState.copy(busy = false, message = message))
                mainHandler.post { onOutcome(success, message) }
            } catch (error: Exception) {
                val message = "Delete failed: ${error.detail()}"
                publish(currentState.copy(busy = false, message = message))
                mainHandler.post { onOutcome(false, message) }
            }
        }
    }

    /** Called on [worker] only. Opens the port with defaults if this is the first use. */
    private fun ensureConnectedOnWorker(): R503FingerprintProtocol {
        protocol?.let { return it }
        transport.open(R503FingerprintProtocol.PORT_PATH, R503FingerprintProtocol.BAUD_RATE)
        val link = R503FingerprintProtocol(transport)
        link.requireSuccessfulAcknowledgement(link.handshake())
        protocol = link
        publish(currentState.copy(connected = true))
        return link
    }

    /**
     * The one place this feature needs a real polling delay — [R503FingerprintProtocol] stays
     * platform-agnostic by exposing only the single-shot [R503FingerprintProtocol.getImage]
     * primitive; this method owns the repeat-with-delay loop, same division of responsibility
     * as [CabinetHardwareController]'s own take/return-flow polling.
     */
    private fun waitForFinger(
        link: R503FingerprintProtocol,
        timeoutMillis: Long,
        pollIntervalMillis: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val transaction = link.getImage()
            when (transaction.responsePacket.confirmationCode) {
                0x00 -> return true
                0x02 -> Thread.sleep(pollIntervalMillis) // no finger yet — keep polling
                else -> return false
            }
        }
        return false
    }

    private fun enrollmentStepText(step: Int?): String = when (step) {
        0x01 -> "Capture 1 of 6 completed."
        0x02 -> "Feature 1 generated. Lift and place the finger again."
        0x03 -> "Capture 2 of 6 completed."
        0x04 -> "Feature 2 generated. Lift and place the finger again."
        0x05 -> "Capture 3 of 6 completed."
        0x06 -> "Feature 3 generated. Lift and place the finger again."
        0x07 -> "Capture 4 of 6 completed."
        0x08 -> "Feature 4 generated. Lift and place the finger again."
        0x09 -> "Capture 5 of 6 completed."
        0x0A -> "Feature 5 generated. Lift and place the finger again."
        0x0B -> "Capture 6 of 6 completed."
        0x0C -> "Feature 6 generated."
        0x0D -> "Checking for a duplicate fingerprint."
        0x0E -> "Combining the six fingerprint features."
        0x0F -> "Saving the fingerprint template."
        else -> "Waiting for enrollment progress…"
    }

    private fun publish(next: FingerprintHardwareState) {
        currentState = next
        mainHandler.post { onStateChanged(next) }
    }

    private fun Exception.detail(): String = message ?: javaClass.simpleName
}

data class FingerprintHardwareState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Fingerprint sensor not connected.",
)

sealed interface FingerprintEnrollmentOutcome {
    data class Success(val templateId: Int) : FingerprintEnrollmentOutcome
    data class Failed(val message: String) : FingerprintEnrollmentOutcome
}
