package com.ekms.terminal.hardware

import android.os.Handler
import android.os.Looper
import android_serialport_api.Device
import android_serialport_api.SerialPortManager
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot reader for the Terminal's public M1/NFC reader.
 *
 * It owns /dev/ttyS2 at 9600 baud only while a fob must be compared during
 * key enrolment. Card values are delivered directly to the caller, are never
 * put into reader UI state, and must not be logged by callers.
 */
class TerminalNfcReaderController(
    private val onStateChanged: (TerminalNfcReaderState) -> Unit,
    private val onFobDetected: (String) -> Unit,
) {
    companion object {
        const val PORT_PATH = "/dev/ttyS2"
        const val BAUD_RATE = 9_600

        private const val POLL_INTERVAL_MILLIS = 300L
        private val POLL_COMMAND = byteArrayOf(0x02, 0xAF.toByte(), 0xDD.toByte())
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private val serialPort = ReaderSerialPort()

    @Volatile
    private var closed = false

    /** Starts a fresh scan. Repeated calls while a scan is active do nothing. */
    fun startScan() {
        if (closed) {
            publish(TerminalNfcReaderState.Error("Terminal NFC reader is closed."))
            return
        }
        if (!scanning.compareAndSet(false, true)) return

        publish(TerminalNfcReaderState.Connecting)
        worker.execute {
            try {
                serialPort.open(PORT_PATH, BAUD_RATE)
                publish(TerminalNfcReaderState.WaitingForFob)
                val buffer = ByteArray(512)

                while (scanning.get()) {
                    serialPort.write(POLL_COMMAND)
                    Thread.sleep(POLL_INTERVAL_MILLIS)

                    val bytesRead = serialPort.readIfAvailable(buffer)
                    val uid = if (bytesRead > 0) {
                        parseUid(buffer.copyOf(bytesRead))
                    } else {
                        null
                    }

                    if (uid != null) {
                        // Close before posting the callback so a mismatch can
                        // immediately begin the next scan without a ttyS2 race.
                        scanning.set(false)
                        serialPort.close()
                        publish(TerminalNfcReaderState.FobCaptured)
                        mainHandler.post { onFobDetected(uid) }
                        return@execute
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (scanning.get()) {
                    publish(
                        TerminalNfcReaderState.Error(
                            "Unable to use the Terminal NFC reader: " +
                                    (error.message ?: error.javaClass.simpleName),
                        ),
                    )
                }
            } finally {
                serialPort.close()
                scanning.set(false)
            }
        }
    }

    fun stopScan() {
        scanning.set(false)
        serialPort.close()
        publish(TerminalNfcReaderState.Stopped)
    }

    fun close() {
        closed = true
        stopScan()
        worker.shutdownNow()
    }

    private fun publish(state: TerminalNfcReaderState) {
        mainHandler.post { onStateChanged(state) }
    }

    /** The supplier public reader returns a hexadecimal UID as ASCII data. */
    private fun parseUid(bytes: ByteArray): String? {
        val hexadecimal = String(bytes, Charsets.US_ASCII)
            .filter { character ->
                character in '0'..'9' || character in 'A'..'F' || character in 'a'..'f'
            }
            .uppercase(Locale.US)

        return hexadecimal.takeLast(8).takeIf { it.length == 8 }
    }
}

sealed interface TerminalNfcReaderState {
    data object Idle : TerminalNfcReaderState
    data object Connecting : TerminalNfcReaderState
    data object WaitingForFob : TerminalNfcReaderState
    data object FobCaptured : TerminalNfcReaderState
    data object Stopped : TerminalNfcReaderState
    data class Error(val message: String) : TerminalNfcReaderState
}

/** A private serial-port owner so /dev/ttyS2 is never shared with /dev/ttyS1. */
private class ReaderSerialPort {
    private var manager: SerialPortManager? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    var isOpen: Boolean = false
        private set

    @Synchronized
    fun open(portPath: String, baudRate: Int) {
        if (isOpen) return

        val device = Device().apply {
            path = portPath
            speed = baudRate
        }
        val openedManager = SerialPortManager(device)
        val openedInput = openedManager.mInputStream
            ?: throw IllegalStateException("Terminal NFC input is unavailable for $portPath.")
        val openedOutput = openedManager.mOutputStream
            ?: throw IllegalStateException("Terminal NFC output is unavailable for $portPath.")

        manager = openedManager
        input = openedInput
        output = openedOutput
        isOpen = true
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        check(isOpen) { "Terminal NFC reader is not open." }
        val stream = output ?: throw IllegalStateException("Terminal NFC output is unavailable.")
        stream.write(bytes)
        stream.flush()
    }

    @Synchronized
    fun readIfAvailable(buffer: ByteArray): Int {
        check(isOpen) { "Terminal NFC reader is not open." }
        val stream = input ?: throw IllegalStateException("Terminal NFC input is unavailable.")
        return if (stream.available() > 0) stream.read(buffer) else 0
    }

    @Synchronized
    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { manager?.closeSerialPort() }
        input = null
        output = null
        manager = null
        isOpen = false
    }
}