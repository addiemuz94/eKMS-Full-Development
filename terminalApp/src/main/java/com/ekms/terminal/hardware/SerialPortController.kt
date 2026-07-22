package com.ekms.terminal.hardware

import android_serialport_api.Device
import android_serialport_api.SerialPortManager
import java.io.InputStream
import java.io.OutputStream

/**
 * Small Android-only wrapper around the supplier serial-port AAR.
 *
 * A caller owns one port for one short operation. This prevents the card
 * reader on /dev/ttyS2 from being shared with a supplier/demo app or the
 * cabinet controller on /dev/ttyS1.
 */
class SerialPortController {
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
            ?: throw IllegalStateException("Serial input stream is unavailable for $portPath.")
        val openedOutput = openedManager.mOutputStream
            ?: throw IllegalStateException("Serial output stream is unavailable for $portPath.")

        manager = openedManager
        input = openedInput
        output = openedOutput
        isOpen = true
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        check(isOpen) { "Serial port is not open." }
        val stream = output ?: throw IllegalStateException("Serial output stream is unavailable.")
        stream.write(bytes)
        stream.flush()
    }

    @Synchronized
    fun readIfAvailable(buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "Read buffer cannot be empty." }
        check(isOpen) { "Serial port is not open." }
        val stream = input ?: throw IllegalStateException("Serial input stream is unavailable.")
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