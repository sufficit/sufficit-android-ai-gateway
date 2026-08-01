package com.sufficit.ai.gateway.network

import android.content.Context

/** Faz a descoberta e preserva no aparelho os MACs aprendidos na LAN. */
class WakeOnLanInventoryService(context: Context) {
    private val store = WakeOnLanDeviceInventoryStore(context.applicationContext)

    fun discover(activeProbe: Boolean): WakeOnLanDiscoveryResult {
        val result = WakeOnLanDiscoveryTool.discover(activeProbe)
        val known = store.merge(result.devices)
        return result.copy(knownDevices = known)
    }

    fun knownDevices(): List<WakeOnLanKnownDevice> = store.list()

    fun nameDevice(macAddress: String, name: String): WakeOnLanKnownDevice =
        store.saveName(macAddress, name)

    fun verify(macAddresses: List<String> = emptyList(), waitSeconds: Int = 30): WakeOnLanVerificationResult {
        val selected = macAddresses.mapNotNull(WakeOnLanDeviceInventoryStore::normalizeMac).toSet()
        val devices = store.list().let { values -> if (selected.isEmpty()) values else values.filter { it.macAddress in selected } }
        val result = WakeOnLanVerificationTool.verify(devices, waitSeconds)
        store.updateVerification(result.targets)
        return result
    }

    fun wakeAndVerify(
        macAddress: String,
        ipAddress: String? = null,
        broadcastAddress: String? = null,
        port: Int = WakeOnLanTool.DEFAULT_PORT,
        repeat: Int = WakeOnLanTool.DEFAULT_REPEAT,
        waitSeconds: Int = WakeOnLanVerificationTool.DEFAULT_WAIT_SECONDS,
        name: String? = null,
        isCanceled: () -> Boolean = { false }
    ): WakeOnLanVerificationResult {
        val remembered = store.rememberTarget(
            macAddress = macAddress,
            ipAddress = ipAddress,
            broadcastAddress = broadcastAddress,
            name = name
        )
        val result = WakeOnLanVerificationTool.verifyTarget(
            macAddress = remembered.macAddress,
            ipAddress = remembered.ipAddress,
            broadcastAddress = broadcastAddress?.takeIf { it.isNotBlank() } ?: remembered.broadcastAddress,
            port = port,
            repeat = repeat,
            waitSeconds = waitSeconds,
            isCanceled = isCanceled
        )
        val target = result.targets.single()
        store.rememberTarget(
            macAddress = target.macAddress,
            ipAddress = target.ipAddress,
            broadcastAddress = broadcastAddress?.takeIf { it.isNotBlank() } ?: remembered.broadcastAddress,
            name = name
        )
        store.updateVerification(result.targets)
        return result
    }
}
