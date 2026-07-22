package com.ekms.terminal.hardware

import android_serialport_api.Device
import android_serialport_api.SerialPortManager
import com.ekms.shared.protocol.SerialTransport
import java.io.InputStream
import java.io.OutputStream

/**
 * Android implementation of [SerialTransport], wrapping the vendor serial
 * AAR to open the cabinet controller port (`/dev/ttyS1`). One instance owns
 * one port for its lifetime so commands cannot overlap or corrupt one
 * another's frames — the same exclusivity the previous `CabinetSerialPort`
 * provided, now behind the shared protocol layer's transport interface.
 */
class AndroidSerialTransport : SerialTransport {
    private var manager: SerialPortManager? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    override var isOpen: Boolean = false
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
            ?: throw IllegalStateException("Serial input is unavailable for $portPath.")
        val openedOutput = openedManager.mOutputStream
            ?: throw IllegalStateException("Serial output is unavailable for $portPath.")

        manager = openedManager
        input = openedInput
        output = openedOutput
        isOpen = true
    }

    @Synchronized
    override fun write(bytes: ByteArray) {
        check(isOpen) { "Serial port is not open." }
        val stream = output ?: throw IllegalStateException("Serial output is unavailable.")
        stream.write(bytes)
        stream.flush()
    }

    @Synchronized
    override fun clearInputBuffer() {
        val stream = input ?: return
        while (stream.available() > 0) {
            stream.read()
        }
    }

    @Synchronized
    override fun readByteOrNull(timeoutMillis: Long): Int? {
        check(isOpen) { "Serial port is not open." }
        val stream = input ?: throw IllegalStateException("Serial input is unavailable.")
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            if (stream.available() > 0) {
                val next = stream.read()
                if (next >= 0) return next and 0xFF
            } else {
                Thread.sleep(READ_POLL_INTERVAL_MILLIS)
            }
        }
        return null
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

    private companion object {
        const val READ_POLL_INTERVAL_MILLIS = 10L
    }
}
