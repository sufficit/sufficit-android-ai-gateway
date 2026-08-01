package com.sufficit.ai.gateway.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException

enum class WakeOnLanVerificationStatus {
    ALREADY_REACHABLE,
    CONFIRMED_ONLINE,
    NOT_RESPONDING,
    UNVERIFIABLE,
    SEND_FAILED
}

data class WakeOnLanVerificationDevice(
    val macAddress: String,
    val ipAddress: String,
    val reachableBeforeWake: Boolean?,
    val reachableAfterWake: Boolean?,
    val respondedAfterWake: Boolean,
    val status: WakeOnLanVerificationStatus,
    val checkAttempts: Int,
    val verificationMethod: String?,
    val elapsedMs: Long,
    val checkedAtEpochMs: Long = System.currentTimeMillis(),
    val error: String? = null
)

data class WakeOnLanVerificationResult(
    val targets: List<WakeOnLanVerificationDevice>,
    val waitSeconds: Int,
    val packetsSent: Int,
    val elapsedMs: Long,
    /** Entregas aceitas pelo kernel de rede do Android; não prova recepção na NIC remota. */
    val deliveryAttempts: List<WakeOnLanDeliveryAttempt> = emptyList()
)

/**
 * Envia Wake-on-LAN e monitora presença real até o prazo expirar.
 *
 * Com IP conhecido, tenta ICMP e portas comuns. Sem IP conhecido, sonda a
 * sub-rede e procura o MAC na vizinhança ARP, NetBIOS ou no companion Sufficit.
 * Encontrar o MAC na camada 2 confirma presença mesmo quando o Linux bloqueia
 * ping e não expõe nenhuma das portas testadas.
 */
object WakeOnLanVerificationTool {
    const val DEFAULT_WAIT_SECONDS = 30
    private const val MIN_WAIT_SECONDS = 5
    private const val MAX_WAIT_SECONDS = 60
    private const val CHECK_INTERVAL_MILLIS = 1_000L

    fun verify(
        devices: List<WakeOnLanKnownDevice>,
        waitSeconds: Int = DEFAULT_WAIT_SECONDS
    ): WakeOnLanVerificationResult {
        require(devices.isNotEmpty()) { "Nenhum dispositivo Wake-on-LAN cadastrado." }
        val results = devices.map { device ->
            verifyTarget(
                macAddress = device.macAddress,
                ipAddress = device.ipAddress,
                broadcastAddress = device.broadcastAddress,
                waitSeconds = waitSeconds
            )
        }
        return WakeOnLanVerificationResult(
            targets = results.flatMap(WakeOnLanVerificationResult::targets),
            waitSeconds = waitSeconds.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS),
            packetsSent = results.sumOf(WakeOnLanVerificationResult::packetsSent),
            elapsedMs = results.maxOfOrNull(WakeOnLanVerificationResult::elapsedMs) ?: 0L,
            deliveryAttempts = results.flatMap(WakeOnLanVerificationResult::deliveryAttempts)
        )
    }

    fun verifyTarget(
        macAddress: String,
        ipAddress: String? = null,
        broadcastAddress: String? = null,
        port: Int = WakeOnLanTool.DEFAULT_PORT,
        repeat: Int = WakeOnLanTool.DEFAULT_REPEAT,
        waitSeconds: Int = DEFAULT_WAIT_SECONDS,
        isCanceled: () -> Boolean = { false }
    ): WakeOnLanVerificationResult {
        if (isCanceled()) throw CancellationException("Verificacao Wake-on-LAN cancelada.")
        val normalizedMac = WakeOnLanDeviceInventoryStore.normalizeMac(macAddress)
            ?: throw IllegalArgumentException("MAC Wake-on-LAN invalido.")
        val safeWait = waitSeconds.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)
        val startedAt = System.currentTimeMillis()
        val before = observePresence(
            macAddress = normalizedMac,
            preferredIpAddress = ipAddress,
            activeProbe = false
        )
        val wakeResult = runCatching {
            if (isCanceled()) throw CancellationException("Envio Wake-on-LAN cancelado.")
            WakeOnLanTool.send(
                macAddress = normalizedMac,
                broadcastAddress = broadcastAddress,
                port = port,
                repeat = repeat,
                targetIpAddress = ipAddress
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val elapsed = System.currentTimeMillis() - startedAt
            return WakeOnLanVerificationResult(
                targets = listOf(
                    WakeOnLanVerificationDevice(
                        macAddress = normalizedMac,
                        ipAddress = before.ipAddress.orEmpty(),
                        reachableBeforeWake = before.reachable,
                        reachableAfterWake = null,
                        respondedAfterWake = false,
                        status = WakeOnLanVerificationStatus.SEND_FAILED,
                        checkAttempts = 0,
                        verificationMethod = before.method,
                        elapsedMs = elapsed,
                        error = error.message ?: "falha ao enviar magic packet"
                    )
                ),
                waitSeconds = safeWait,
                packetsSent = 0,
                elapsedMs = elapsed
            )
        }

        if (before.reachable == true) {
            val elapsed = System.currentTimeMillis() - startedAt
            return WakeOnLanVerificationResult(
                targets = listOf(
                    WakeOnLanVerificationDevice(
                        macAddress = normalizedMac,
                        ipAddress = before.ipAddress.orEmpty(),
                        reachableBeforeWake = true,
                        reachableAfterWake = true,
                        respondedAfterWake = false,
                        status = WakeOnLanVerificationStatus.ALREADY_REACHABLE,
                        checkAttempts = 1,
                        verificationMethod = before.method,
                        elapsedMs = elapsed
                    )
                ),
                waitSeconds = safeWait,
                packetsSent = wakeResult.packetsSent,
                elapsedMs = elapsed,
                deliveryAttempts = wakeResult.deliveries
            )
        }

        val deadline = startedAt + safeWait * 1_000L
        var attempts = 0
        var latest = before
        do {
            if (isCanceled()) throw CancellationException("Verificacao Wake-on-LAN cancelada.")
            attempts += 1
            latest = observePresence(
                macAddress = normalizedMac,
                preferredIpAddress = latest.ipAddress ?: ipAddress,
                activeProbe = true
            )
            if (latest.reachable == true) break
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                Thread.sleep(minOf(CHECK_INTERVAL_MILLIS, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        val elapsed = System.currentTimeMillis() - startedAt
        val status = classify(
            reachableBeforeWake = before.reachable,
            reachableAfterWake = latest.reachable,
            hasKnownIpAddress = !latest.ipAddress.isNullOrBlank(),
            sendError = null
        )
        return WakeOnLanVerificationResult(
            targets = listOf(
                WakeOnLanVerificationDevice(
                    macAddress = normalizedMac,
                    ipAddress = latest.ipAddress.orEmpty(),
                    reachableBeforeWake = before.reachable,
                    reachableAfterWake = latest.reachable,
                    respondedAfterWake = before.reachable != true && latest.reachable == true,
                    status = status,
                    checkAttempts = attempts,
                    verificationMethod = latest.method,
                    elapsedMs = elapsed
                )
            ),
            waitSeconds = safeWait,
            packetsSent = wakeResult.packetsSent,
            elapsedMs = elapsed,
            deliveryAttempts = wakeResult.deliveries
        )
    }

    internal fun classify(
        reachableBeforeWake: Boolean?,
        reachableAfterWake: Boolean?,
        hasKnownIpAddress: Boolean,
        sendError: String?
    ): WakeOnLanVerificationStatus = when {
        !sendError.isNullOrBlank() -> WakeOnLanVerificationStatus.SEND_FAILED
        reachableBeforeWake == true -> WakeOnLanVerificationStatus.ALREADY_REACHABLE
        reachableAfterWake == true -> WakeOnLanVerificationStatus.CONFIRMED_ONLINE
        hasKnownIpAddress -> WakeOnLanVerificationStatus.NOT_RESPONDING
        else -> WakeOnLanVerificationStatus.UNVERIFIABLE
    }

    private fun observePresence(
        macAddress: String,
        preferredIpAddress: String?,
        activeProbe: Boolean
    ): PresenceObservation {
        val preferredIp = preferredIpAddress?.trim()?.ifBlank { null }
        if (reachable(preferredIp) == true) {
            return PresenceObservation(preferredIp, true, "icmp_or_tcp")
        }

        val discovered = runCatching {
            WakeOnLanDiscoveryTool.discover(activeProbe).devices.firstOrNull { device ->
                WakeOnLanDeviceInventoryStore.normalizeMac(device.macAddress) == macAddress
            }
        }.getOrNull()
        if (discovered != null) {
            return PresenceObservation(
                ipAddress = discovered.ipAddress.trim().ifBlank { preferredIp },
                reachable = true,
                method = discovered.discoverySource
            )
        }

        return PresenceObservation(
            ipAddress = preferredIp,
            reachable = preferredIp?.let { false },
            method = preferredIp?.let { "icmp_or_tcp" }
        )
    }

    private fun reachable(value: String?): Boolean? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val address = InetAddress.getByName(value)
            if (address.isReachable(700)) true else intArrayOf(80, 443, 445, 22).any { port ->
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), 180)
                    }
                }.isSuccess
            }
        }.getOrNull()
    }

    private data class PresenceObservation(
        val ipAddress: String?,
        val reachable: Boolean?,
        val method: String?
    )
}
