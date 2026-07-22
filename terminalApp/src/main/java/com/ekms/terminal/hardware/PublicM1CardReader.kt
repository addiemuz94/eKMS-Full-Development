package com.ekms.terminal.hardware

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reader driver for the terminal's public M1 fob reader.
 *
 * Confirmed terminal wiring: /dev/ttyS2, 9600 baud, poll command 02 AF DD.
 * The driver returns the normalized UID only to its caller. It never logs it.
 */
class PublicM1CardReader(
    private val serialPort: SerialPortController,
    private val onStatus: (String) -> Unit,
    private val onCardDetected: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val PORT_PATH = "/dev/ttyS2"
        const val BAUD_RATE = 9_600

        private const val POLL_INTERVAL_MS = 300L
        private const val RETRY_INTERVAL_MS = 1_000L
        private val POLL_COMMAND = byteArrayOf(0x02, 0xAF.toByte(), 0xDD.toByte())
    }

    private val polling = AtomicBoolean(false)
    private var readerThread: Thread? = null

    @Synchronized
    fun connect() {
        if (serialPort.isOpen) return
        serialPort.open(PORT_PATH, BAUD_RATE)
        onStatus("Reader connected. Present a physical key fob.")
    }

    @Synchronized
    fun startPolling() {
        check(serialPort.isOpen) { "Connect the reader before polling." }
        if (!polling.compareAndSet(false, true)) return

        readerThread = Thread(
            { monitorLoop() },
            "ekms-public-m1-reader",
        ).apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun disconnect() {
        polling.set(false)
        readerThread?.interrupt()
        readerThread = null
        if (serialPort.isOpen) serialPort.close()
    }

    /** Compatibility-safe terminal resource cleanup. */
    fun close() {
        disconnect()
    }

    private fun monitorLoop() {
        val buffer = ByteArray(512)

        while (polling.get() && serialPort.isOpen) {
            try {
                serialPort.write(POLL_COMMAND)
                Thread.sleep(POLL_INTERVAL_MS)

                val bytesRead = serialPort.readIfAvailable(buffer)
                if (bytesRead > 0) {
                    val uid = parseUid(buffer.copyOf(bytesRead))
                    if (uid != null) {
                        polling.set(false)
                        onCardDetected(uid)
                        break
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Exception) {
                if (polling.get()) {
                    onError(
                        "Reader communication problem: " +
                                (error.message ?: error.javaClass.simpleName) + ". Retrying…",
                    )
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (!polling.get() && serialPort.isOpen) serialPort.close()
    }

    /**
     * The public reader returns hexadecimal card data. Keep only the detected
     * hex characters, then use the last four-byte-or-longer UID field.
     */
    private fun parseUid(bytes: ByteArray): String? {
        val hexadecimal = String(bytes, Charsets.US_ASCII)
            .filter { character ->
                character in '0'..'9' || character in 'A'..'F' || character in 'a'..'f'
            }
            .uppercase(Locale.US)

        return when {
            hexadecimal.length >= 8 -> hexadecimal.takeLast(8)
            else -> null
        }
    }
}