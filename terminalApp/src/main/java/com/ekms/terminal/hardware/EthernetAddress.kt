package com.ekms.terminal.hardware

import java.io.File
import java.util.Locale

/**
 * Best-effort read of the device's Ethernet MAC address for the Admin Menu's
 * "Ethernet MAC address" display item. Android has restricted MAC address
 * APIs since API 23 (WifiInfo.getMacAddress() returns a dummy value), so
 * this reads the interface directly; that path is not guaranteed to be
 * readable on every device/Android version. Returns null rather than a
 * fabricated address when it cannot be read.
 */
fun readEthernetMacAddress(): String? =
    runCatching { File(ETHERNET_ADDRESS_PATH).readText().trim() }
        .getOrNull()
        ?.uppercase(Locale.US)
        ?.takeIf { it.isNotBlank() && it != UNSET_ADDRESS }

private const val ETHERNET_ADDRESS_PATH = "/sys/class/net/eth0/address"
private const val UNSET_ADDRESS = "00:00:00:00:00:00"
