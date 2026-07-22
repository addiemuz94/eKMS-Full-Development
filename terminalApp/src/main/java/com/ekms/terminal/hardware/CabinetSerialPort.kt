package com.ekms.terminal.hardware

import android_serialport_api.Device
import android_serialport_api.SerialPortManager
import java.io.InputStream
import java.io.OutputStream

/**
 * Exclusive Android-only owner for the cabinet serial bus.
 *
 * The supplier serial AAR is used directly here. A single controller owns
 * this port so commands cannot overlap or corrupt one another's frames.
 */
class CabinetSerialPort {
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
            ?: throw IllegalStateException("Serial input is unavailable for " + portPath + ".")
        val openedOutput = openedManager.mOutputStream
            ?: throw IllegalStateException("Serial output is unavailable for " + portPath + ".")

        manager = openedManager
        input = openedInput
        output = openedOutput
        isOpen = true
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        check(isOpen) { "Cabinet serial port is not open." }
        val stream = output ?: throw IllegalStateException("Cabinet serial output is unavailable.")
        stream.write(bytes)
        stream.flush()
    }

    @Synchronized
    fun clearInputBuffer() {
        val stream = input ?: return
        while (stream.available() > 0) {
            stream.read()
        }
    }

    /**
     * Reads one supplier response frame. Response bytes are delimited by
     * 0x01 and 0x03, while the split-encoded body only uses 0x30–0x3F.
     */
    @Synchronized
    fun readResponseFrame(timeoutMillis: Long): ByteArray? {
        check(isOpen) { "Cabinet serial port is not open." }
        val stream = input ?: throw IllegalStateException("Cabinet serial input is unavailable.")
        val deadline = System.currentTimeMillis() + timeoutMillis
        val bytes = ArrayList<Byte>()
        var started = false

        while (System.currentTimeMillis() < deadline) {
            if (stream.available() <= 0) {
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                continue
            }

            val next = stream.read()
            if (next < 0) continue
            val value = next and 0xFF

            if (!started) {
                if (value == 0x01) {
                    bytes.add(value.toByte())
                    started = true
                }
                continue
            }

            bytes.add(value.toByte())
            if (value == 0x03) {
                return bytes.toByteArray()
            }
            if (bytes.size > 128) {
                throw IllegalStateException("Cabinet response exceeded the maximum frame length.")
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
}
