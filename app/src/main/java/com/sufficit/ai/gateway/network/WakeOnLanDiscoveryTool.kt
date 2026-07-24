package com.sufficit.ai.gateway.network

import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Vizinho IPv4 visto na LAN e que pode ser usado como alvo de Wake-on-LAN. */
data class WakeOnLanDiscoveredDevice(
    val ipAddress: String,
    val macAddress: String,
    val interfaceName: String,
    val localAddress: String,
    val broadcastAddress: String,
    val discoverySource: String,
    /** Nome vindo de uma fonte local confiavel, quando disponivel. */
    val discoveredName: String? = null,
    /** A descoberta nao consegue confirmar BIOS/NIC; use um teste WOL para isso. */
    val wakeOnLanStatus: String = "unknown"
)

/** Rede IPv4 local usada durante uma descoberta. */
data class WakeOnLanDiscoveryNetwork(
    val interfaceName: String,
    val localAddress: String,
    val prefixLength: Int,
    val broadcastAddress: String,
    val scannedRange: String
)

/** Resultado da descoberta de candidatos Wake-on-LAN. */
data class WakeOnLanDiscoveryResult(
    val probeExecuted: Boolean,
    val scannedHostCount: Int,
    val networks: List<WakeOnLanDiscoveryNetwork>,
    val devices: List<WakeOnLanDiscoveredDevice>,
    val warnings: List<String>,
    /** MACs ja aprendidos continuam disponiveis mesmo com destino desligado. */
    val knownDevices: List<WakeOnLanKnownDevice> = emptyList()
)

/**
 * Descoberta local de candidatos Wake-on-LAN.
 *
 * O Android nao disponibiliza uma API que revele se a BIOS/NIC de outra
 * maquina aceita magic packets. Por isso a ferramenta combina uma sonda UDP
 * vazia e limitada (que apenas preenche ARP) com /proc/net/arp e devolve os
 * vizinhos que responderam em camada 2. Cada item deve ser tratado como
 * candidato: o estado WOL e "unknown" ate um envio de magic packet ser
 * confirmado no destino.
 */
object WakeOnLanDiscoveryTool {
    private const val MAX_PROBE_HOSTS_PER_NETWORK = 254
    private const val PROBE_PORT = WakeOnLanTool.DEFAULT_PORT
    private const val ARP_SETTLE_MILLIS = 350L
    private const val NETBIOS_PORT = 137
    private const val NETBIOS_RESPONSE_WINDOW_MILLIS = 1_000L
    private const val SUFFICIT_COMPANION_PORT = 45_991
    private const val SUFFICIT_COMPANION_RESPONSE_WINDOW_MILLIS = 2_000L
    private const val SUFFICIT_COMPANION_REQUEST_PREFIX = "SUFFICIT_WOL_DISCOVER_V1 "
    private const val SUFFICIT_COMPANION_PROTOCOL = "sufficit-wol-inventory-v1"
    private const val IPV4_MASK = 0xFFFF_FFFFL

    fun discover(activeProbe: Boolean = true): WakeOnLanDiscoveryResult {
        val networks = resolveLocalNetworks()
        val warnings = mutableListOf<String>()
        if (networks.isEmpty()) {
            warnings += "Nenhuma interface IPv4 local com broadcast foi encontrada."
        }

        var scannedHostCount = 0
        if (activeProbe && networks.isNotEmpty()) {
            runCatching {
                DatagramSocket().use { socket ->
                    networks.forEach { network ->
                        network.probeTargets().forEach { target ->
                            socket.send(DatagramPacket(EMPTY_PROBE, 0, target, PROBE_PORT))
                            scannedHostCount += 1
                        }
                    }
                }
                // A resolucao ARP e assincrona; uma janela curta permite que
                // os vizinhos respondam sem transformar a ferramenta em scan
                // de portas ou bloquear o fluxo do agente por muito tempo.
                Thread.sleep(ARP_SETTLE_MILLIS)
            }.onFailure { error ->
                warnings += "Sonda ARP parcial: ${error.message ?: "erro de rede"}."
            }
        }

        val arpNeighbors = readArpNeighbors(warnings)
        val netBiosNeighbors = if (activeProbe && networks.isNotEmpty()) {
            discoverNetBiosNeighbors(networks, warnings)
        } else {
            emptyList()
        }
        val companionDevices = if (activeProbe && networks.isNotEmpty()) {
            discoverSufficitCompanionDevices(networks, warnings)
        } else {
            emptyList()
        }
        if (activeProbe && netBiosNeighbors.isEmpty()) {
            warnings += "Nenhum dispositivo respondeu à consulta NetBIOS de nome e MAC."
        }
        val devices = arpNeighbors.mapNotNull { neighbor ->
            val network = networks.firstOrNull { it.contains(neighbor.address) } ?: return@mapNotNull null
            WakeOnLanDiscoveredDevice(
                ipAddress = neighbor.address.hostAddress.orEmpty(),
                macAddress = neighbor.macAddress,
                interfaceName = neighbor.interfaceName,
                localAddress = network.localAddress.hostAddress.orEmpty(),
                broadcastAddress = network.broadcastAddress.hostAddress.orEmpty(),
                discoverySource = "arp"
            )
        }
            .plus(netBiosNeighbors.mapNotNull { neighbor ->
                val network = networks.firstOrNull { it.contains(neighbor.address) } ?: return@mapNotNull null
                WakeOnLanDiscoveredDevice(
                    ipAddress = neighbor.address.hostAddress.orEmpty(),
                    macAddress = neighbor.macAddress,
                    interfaceName = network.interfaceName,
                    localAddress = network.localAddress.hostAddress.orEmpty(),
                    broadcastAddress = network.broadcastAddress.hostAddress.orEmpty(),
                    discoverySource = "netbios"
                )
            })
            .plus(companionDevices)
            .distinctBy { it.macAddress }
            .sortedWith(compareBy(WakeOnLanDiscoveredDevice::interfaceName, WakeOnLanDiscoveredDevice::ipAddress))

        if (devices.isEmpty() && networks.isNotEmpty()) {
            warnings += "Nenhum vizinho com MAC resolvido foi encontrado na tabela ARP."
        }

        return WakeOnLanDiscoveryResult(
            probeExecuted = activeProbe,
            scannedHostCount = scannedHostCount,
            networks = networks.map { it.toResult() },
            devices = devices,
            warnings = warnings.distinct()
        )
    }

    /**
     * Consulta um companion Sufficit presente na propria LAN. A resposta
     * precisa repetir o nonce e so pode conter IPs da sub-rede que recebeu a
     * consulta; isso evita aceitar inventarios antigos ou externos.
     */
    private fun discoverSufficitCompanionDevices(
        networks: List<LocalNetwork>,
        warnings: MutableList<String>
    ): List<WakeOnLanDiscoveredDevice> {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val request = (SUFFICIT_COMPANION_REQUEST_PREFIX + nonce).toByteArray(Charsets.US_ASCII)
        val devices = mutableListOf<WakeOnLanDiscoveredDevice>()
        runCatching {
            networks.forEach { network ->
                DatagramSocket(InetSocketAddress(network.localAddress, 0)).use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(request, request.size, network.broadcastAddress, SUFFICIT_COMPANION_PORT))
                    val deadline = System.currentTimeMillis() + SUFFICIT_COMPANION_RESPONSE_WINDOW_MILLIS
                    while (System.currentTimeMillis() < deadline) {
                        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                        socket.soTimeout = minOf(remaining, 250L).toInt()
                        val response = DatagramPacket(ByteArray(65_507), 65_507)
                        try {
                            socket.receive(response)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val responder = response.address as? Inet4Address ?: continue
                        if (!network.contains(responder)) continue
                        val json = runCatching {
                            JSONObject(response.data.copyOfRange(0, response.length).toString(Charsets.UTF_8))
                        }.getOrNull() ?: continue
                        if (json.optString("protocol") != SUFFICIT_COMPANION_PROTOCOL || json.optString("nonce") != nonce) {
                            continue
                        }
                        val array = json.optJSONArray("devices") ?: continue
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val address = runCatching { InetAddress.getByName(item.optString("ip")) }.getOrNull()
                                as? Inet4Address ?: continue
                            if (!network.contains(address)) continue
                            val mac = normalizeMac(item.optString("mac")) ?: continue
                            devices += WakeOnLanDiscoveredDevice(
                                ipAddress = address.hostAddress.orEmpty(),
                                macAddress = mac,
                                interfaceName = item.optString("interface").trim().ifBlank { "lan" },
                                localAddress = network.localAddress.hostAddress.orEmpty(),
                                broadcastAddress = network.broadcastAddress.hostAddress.orEmpty(),
                                discoverySource = "sufficit_lan_companion",
                                discoveredName = item.optString("name").trim().take(80)
                                    .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                            )
                        }
                    }
                }
            }
        }.onFailure { error ->
            warnings += "Companion Sufficit indisponivel: ${error.message ?: "erro de rede"}."
        }
        return devices.distinctBy { it.macAddress }
    }

    private fun resolveLocalNetworks(): List<LocalNetwork> {
        val networks = mutableListOf<LocalNetwork>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) {
                continue
            }
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                val localAddress = interfaceAddress.address as? Inet4Address ?: return@forEach
                val broadcastAddress = interfaceAddress.broadcast as? Inet4Address ?: return@forEach
                val prefixLength = interfaceAddress.networkPrefixLength.toInt()
                if (prefixLength !in 1..30 || localAddress.isLoopbackAddress || localAddress.isLinkLocalAddress) {
                    return@forEach
                }
                networks += LocalNetwork(
                    interfaceName = networkInterface.name,
                    localAddress = localAddress,
                    broadcastAddress = broadcastAddress,
                    prefixLength = prefixLength
                )
            }
        }
        return networks.distinctBy { "${it.interfaceName}|${it.localAddress.hostAddress}|${it.prefixLength}" }
    }

    private fun readArpNeighbors(warnings: MutableList<String>): List<ArpNeighbor> {
        return runCatching {
            File("/proc/net/arp").useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    if (columns.size < 6) return@mapNotNull null
                    val address = runCatching { InetAddress.getByName(columns[0]) }.getOrNull() as? Inet4Address
                        ?: return@mapNotNull null
                    val macAddress = normalizeMac(columns[3]) ?: return@mapNotNull null
                    val flags = columns[2].removePrefix("0x").toIntOrNull(16) ?: 0
                    if ((flags and 0x2) == 0) return@mapNotNull null
                    ArpNeighbor(address, macAddress, columns[5])
                }.toList()
            }
        }.onFailure { error ->
            warnings += "Tabela ARP indisponivel: ${error.message ?: "acesso negado"}."
        }.getOrDefault(emptyList())
    }

    /**
     * Fallback para Android moderno, onde /proc/net/arp e Netlink sao
     * bloqueados para UID de app. Maquinas Windows e Samba que respondem a
     * NBNS NODE STATUS devolvem o Unit ID, isto e, o MAC real da interface.
     */
    private fun discoverNetBiosNeighbors(
        networks: List<LocalNetwork>,
        warnings: MutableList<String>
    ): List<NetBiosNeighbor> {
        val transactionId = (System.nanoTime() and 0xFFFF).toInt()
        val query = buildNetBiosNodeStatusQuery(transactionId)
        val neighbors = mutableListOf<NetBiosNeighbor>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                networks.forEach { network ->
                    socket.send(DatagramPacket(query, query.size, network.broadcastAddress, NETBIOS_PORT))
                }
                val deadline = System.currentTimeMillis() + NETBIOS_RESPONSE_WINDOW_MILLIS
                val buffer = ByteArray(576)
                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                    socket.soTimeout = minOf(remaining, 250L).toInt()
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val address = response.address as? Inet4Address ?: continue
                    val macAddress = parseNetBiosUnitId(response.data, response.length, transactionId) ?: continue
                    neighbors += NetBiosNeighbor(address, macAddress)
                }
            }
        }.onFailure { error ->
            warnings += "Descoberta NetBIOS indisponivel: ${error.message ?: "erro de rede"}."
        }
        return neighbors.distinctBy { "${it.address.hostAddress}|${it.macAddress}" }
    }

    private fun buildNetBiosNodeStatusQuery(transactionId: Int): ByteArray {
        return ByteArrayOutputStream(50).use { output ->
            output.write(transactionId shr 8)
            output.write(transactionId and 0xFF)
            output.write(0x00)
            output.write(0x10) // broadcast query
            output.write(0x00)
            output.write(0x01) // QDCOUNT
            output.write(0x00)
            output.write(0x00) // ANCOUNT
            output.write(0x00)
            output.write(0x00) // NSCOUNT
            output.write(0x00)
            output.write(0x00) // ARCOUNT
            output.write(0x20) // nome NetBIOS codificado tem 32 bytes
            val netBiosName = ByteArray(16) { ' '.code.toByte() }.also { it[0] = '*'.code.toByte() }
            netBiosName.forEach { byte ->
                val value = byte.toInt() and 0xFF
                output.write('A'.code + (value ushr 4))
                output.write('A'.code + (value and 0x0F))
            }
            output.write(0x00)
            output.write(0x00)
            output.write(0x21) // NBSTAT
            output.write(0x00)
            output.write(0x01) // IN
            output.toByteArray()
        }
    }

    private fun parseNetBiosUnitId(data: ByteArray, length: Int, transactionId: Int): String? {
        if (length < 12 || readUnsignedShort(data, 0) != transactionId) return null
        val questionCount = readUnsignedShort(data, 4)
        val answerCount = readUnsignedShort(data, 6)
        var offset = 12
        repeat(questionCount) {
            offset = skipDnsName(data, length, offset) ?: return null
            if (offset + 4 > length) return null
            offset += 4
        }
        repeat(answerCount) {
            offset = skipDnsName(data, length, offset) ?: return null
            if (offset + 10 > length) return null
            val type = readUnsignedShort(data, offset)
            val recordLength = readUnsignedShort(data, offset + 8)
            val rdataOffset = offset + 10
            val rdataEnd = rdataOffset + recordLength
            if (rdataEnd > length) return null
            if (type == 0x0021 && recordLength >= 7) {
                val nameCount = data[rdataOffset].toInt() and 0xFF
                val unitIdOffset = rdataOffset + 1 + nameCount * 18
                if (unitIdOffset + 6 <= rdataEnd) {
                    return data.copyOfRange(unitIdOffset, unitIdOffset + 6)
                        .joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
                }
            }
            offset = rdataEnd
        }
        return null
    }

    private fun skipDnsName(data: ByteArray, length: Int, initialOffset: Int): Int? {
        var offset = initialOffset
        while (offset < length) {
            val labelLength = data[offset].toInt() and 0xFF
            when {
                labelLength == 0 -> return offset + 1
                (labelLength and 0xC0) == 0xC0 -> return if (offset + 2 <= length) offset + 2 else null
                labelLength > 63 -> return null
                else -> {
                    offset += labelLength + 1
                    if (offset > length) return null
                }
            }
        }
        return null
    }

    private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun normalizeMac(value: String): String? {
        val compact = value.trim().replace(Regex("[:.\\-\\s]"), "")
        if (!compact.matches(Regex("[0-9a-fA-F]{12}"))) return null
        if (compact.all { it == '0' } || compact.all { it.equals('f', ignoreCase = true) }) return null
        return compact.chunked(2).joinToString(":") { it.uppercase(Locale.US) }
    }

    private data class ArpNeighbor(
        val address: Inet4Address,
        val macAddress: String,
        val interfaceName: String
    )

    private data class NetBiosNeighbor(
        val address: Inet4Address,
        val macAddress: String
    )

    private data class LocalNetwork(
        val interfaceName: String,
        val localAddress: Inet4Address,
        val broadcastAddress: Inet4Address,
        val prefixLength: Int
    ) {
        private val localValue = localAddress.toUnsignedLong()
        private val broadcastValue = broadcastAddress.toUnsignedLong()
        private val prefixMask = ((IPV4_MASK shl (32 - prefixLength)) and IPV4_MASK)
        private val networkValue = localValue and prefixMask

        fun contains(address: Inet4Address): Boolean =
            (address.toUnsignedLong() and prefixMask) == networkValue

        fun probeTargets(): Sequence<InetAddress> = sequence {
            // Redes maiores que /24 sao limitadas ao /24 que contem o telefone:
            // suficiente para LANs domesticas e evita varrer milhares de hosts.
            val sliceStart = if (prefixLength >= 24) networkValue + 1 else (localValue and 0xFFFF_FF00L) + 1
            val sliceEnd = if (prefixLength >= 24) broadcastValue - 1 else {
                minOf(broadcastValue - 1, (localValue and 0xFFFF_FF00L) + MAX_PROBE_HOSTS_PER_NETWORK)
            }
            var candidate = sliceStart
            var emitted = 0
            while (candidate <= sliceEnd && emitted < MAX_PROBE_HOSTS_PER_NETWORK) {
                if (candidate != localValue) {
                    yield(InetAddress.getByAddress(candidate.toIpv4Bytes()))
                    emitted += 1
                }
                candidate += 1
            }
        }

        fun toResult(): WakeOnLanDiscoveryNetwork = WakeOnLanDiscoveryNetwork(
            interfaceName = interfaceName,
            localAddress = localAddress.hostAddress.orEmpty(),
            prefixLength = prefixLength,
            broadcastAddress = broadcastAddress.hostAddress.orEmpty(),
            scannedRange = probeRange()
        )

        private fun probeRange(): String {
            val start = if (prefixLength >= 24) networkValue + 1 else (localValue and 0xFFFF_FF00L) + 1
            val end = if (prefixLength >= 24) broadcastValue - 1 else {
                minOf(broadcastValue - 1, (localValue and 0xFFFF_FF00L) + MAX_PROBE_HOSTS_PER_NETWORK)
            }
            return "${start.toIpv4String()}-${end.toIpv4String()}"
        }
    }

    private fun Inet4Address.toUnsignedLong(): Long =
        address.fold(0L) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF).toLong() }

    private fun Long.toIpv4Bytes(): ByteArray = byteArrayOf(
        ((this shr 24) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte()
    )

    private fun Long.toIpv4String(): String = toIpv4Bytes().joinToString(".") { (it.toInt() and 0xFF).toString() }

    private val EMPTY_PROBE = ByteArray(0)
}
