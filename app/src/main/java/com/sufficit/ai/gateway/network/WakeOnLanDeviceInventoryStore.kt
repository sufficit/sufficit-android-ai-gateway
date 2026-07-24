package com.sufficit.ai.gateway.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Cadastro local, persistente e sem credenciais dos destinos Wake-on-LAN. */
data class WakeOnLanKnownDevice(
    val macAddress: String,
    val ipAddress: String?,
    val broadcastAddress: String?,
    val name: String?,
    val source: String,
    val firstSeenAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val lastVerifiedAtEpochMs: Long? = null,
    val lastReachable: Boolean? = null,
    val awaitingName: Boolean = false
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: ipAddress ?: macAddress
}

class WakeOnLanDeviceInventoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    fun list(): List<WakeOnLanKnownDevice> = synchronized(lock) { readLocked() }

    fun merge(devices: List<WakeOnLanDiscoveredDevice>): List<WakeOnLanKnownDevice> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val values = readLocked().associateBy { it.macAddress }.toMutableMap()
        devices.forEach { discovered ->
            val mac = normalizeMac(discovered.macAddress) ?: return@forEach
            val previous = values[mac]
            values[mac] = WakeOnLanKnownDevice(
                macAddress = mac,
                ipAddress = discovered.ipAddress.trim().ifBlank { previous?.ipAddress },
                broadcastAddress = discovered.broadcastAddress.trim().ifBlank { previous?.broadcastAddress },
                name = previous?.name ?: discovered.discoveredName,
                source = discovered.discoverySource,
                firstSeenAtEpochMs = previous?.firstSeenAtEpochMs ?: now,
                lastSeenAtEpochMs = now,
                lastVerifiedAtEpochMs = previous?.lastVerifiedAtEpochMs,
                lastReachable = previous?.lastReachable,
                awaitingName = previous?.awaitingName ?: false
            )
        }
        writeLocked(values.values)
    }

    fun rememberTarget(
        macAddress: String,
        ipAddress: String? = null,
        broadcastAddress: String? = null,
        name: String? = null,
        source: String = "agent"
    ): WakeOnLanKnownDevice = synchronized(lock) {
        val mac = normalizeMac(macAddress) ?: throw IllegalArgumentException("MAC Wake-on-LAN invalido.")
        val now = System.currentTimeMillis()
        val values = readLocked().associateBy { it.macAddress }.toMutableMap()
        val previous = values[mac]
        val saved = WakeOnLanKnownDevice(
            macAddress = mac,
            ipAddress = ipAddress?.trim()?.takeIf { it.isNotBlank() } ?: previous?.ipAddress,
            broadcastAddress = broadcastAddress?.trim()?.takeIf { it.isNotBlank() } ?: previous?.broadcastAddress,
            name = name?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: previous?.name,
            source = previous?.source ?: source,
            firstSeenAtEpochMs = previous?.firstSeenAtEpochMs ?: now,
            lastSeenAtEpochMs = previous?.lastSeenAtEpochMs ?: now,
            lastVerifiedAtEpochMs = previous?.lastVerifiedAtEpochMs,
            lastReachable = previous?.lastReachable,
            awaitingName = previous?.awaitingName ?: false
        )
        values[mac] = saved
        writeLocked(values.values)
        saved
    }

    fun updateVerification(results: List<WakeOnLanVerificationDevice>): List<WakeOnLanKnownDevice> = synchronized(lock) {
        val values = readLocked().associateBy { it.macAddress }.toMutableMap()
        results.forEach { result ->
            val mac = normalizeMac(result.macAddress) ?: return@forEach
            val previous = values[mac] ?: return@forEach
            values[mac] = previous.copy(
                ipAddress = result.ipAddress.takeIf { it.isNotBlank() } ?: previous.ipAddress,
                lastVerifiedAtEpochMs = result.checkedAtEpochMs,
                lastReachable = result.reachableAfterWake,
                awaitingName = result.respondedAfterWake && previous.name.isNullOrBlank()
            )
        }
        writeLocked(values.values)
    }

    fun saveName(macAddress: String, name: String): WakeOnLanKnownDevice = synchronized(lock) {
        val mac = normalizeMac(macAddress) ?: throw IllegalArgumentException("MAC Wake-on-LAN invalido.")
        val value = name.trim().take(80)
        require(value.isNotBlank()) { "Informe um nome para o dispositivo." }
        val values = readLocked().associateBy { it.macAddress }.toMutableMap()
        val previous = values[mac] ?: throw IllegalArgumentException("Dispositivo Wake-on-LAN nao cadastrado.")
        val saved = previous.copy(name = value, awaitingName = false)
        values[mac] = saved
        writeLocked(values.values)
        saved
    }

    private fun readLocked(): List<WakeOnLanKnownDevice> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val mac = normalizeMac(value.optString("mac")) ?: continue
                    add(
                        WakeOnLanKnownDevice(
                            macAddress = mac,
                            ipAddress = value.optString("ip").trim().ifBlank { null },
                            broadcastAddress = value.optString("broadcast").trim().ifBlank { null },
                            name = value.optString("name").trim()
                                .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                            source = value.optString("source").ifBlank { "unknown" },
                            firstSeenAtEpochMs = value.optLong("firstSeenAtEpochMs").takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                            lastSeenAtEpochMs = value.optLong("lastSeenAtEpochMs").takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                            lastVerifiedAtEpochMs = value.optLong("lastVerifiedAtEpochMs").takeIf { it > 0L },
                            lastReachable = value.booleanOrNull("lastReachable"),
                            awaitingName = value.optBoolean("awaitingName", false)
                        )
                    )
                }
            }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(values: Collection<WakeOnLanKnownDevice>): List<WakeOnLanKnownDevice> {
        val sorted = values.distinctBy { it.macAddress }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
        val array = JSONArray()
        sorted.forEach { device ->
            array.put(JSONObject()
                .put("mac", device.macAddress)
                .put("ip", device.ipAddress)
                .put("broadcast", device.broadcastAddress)
                .put("name", device.name)
                .put("source", device.source)
                .put("firstSeenAtEpochMs", device.firstSeenAtEpochMs)
                .put("lastSeenAtEpochMs", device.lastSeenAtEpochMs)
                .put("lastVerifiedAtEpochMs", device.lastVerifiedAtEpochMs)
                .put("lastReachable", device.lastReachable)
                .put("awaitingName", device.awaitingName)
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(array.toString())
        return sorted
    }

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    companion object {
        private const val FILE_NAME = "wake_on_lan_devices.json"

        fun normalizeMac(value: String): String? {
            val compact = value.trim().replace(Regex("[:.\\-\\s]"), "")
            if (!compact.matches(Regex("[0-9a-fA-F]{12}"))) return null
            if (compact.all { it == '0' } || compact.all { it.equals('f', true) }) return null
            return compact.chunked(2).joinToString(":") { it.uppercase(Locale.US) }
        }
    }
}
