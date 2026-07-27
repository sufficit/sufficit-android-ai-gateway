package com.sufficit.ai.gateway.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/** Caminho de rede usado para entregar um Magic Packet. */
enum class WakeOnLanDeliveryMode {
    UNICAST_LAST_KNOWN_IP,
    SUBNET_BROADCAST,
    LIMITED_BROADCAST
}

/** Uma combinação destino/porta efetivamente enviada pelo cliente. */
data class WakeOnLanDeliveryAttempt(
    val destination: String,
    val port: Int,
    val mode: WakeOnLanDeliveryMode
) {
    val description: String
        get() = when (mode) {
            WakeOnLanDeliveryMode.UNICAST_LAST_KNOWN_IP -> "unicast $destination:$port"
            WakeOnLanDeliveryMode.SUBNET_BROADCAST -> "broadcast da rede $destination:$port"
            WakeOnLanDeliveryMode.LIMITED_BROADCAST -> "broadcast limitado $destination:$port"
        }
}

/** Resultado de um disparo Wake-on-LAN concluído no cliente Android. */
data class WakeOnLanResult(
    val macAddress: String,
    val destinations: List<String>,
    val port: Int,
    val packetsSent: Int,
    val ports: List<Int> = listOf(port),
    val deliveries: List<WakeOnLanDeliveryAttempt> = emptyList(),
    val packetBytes: Int = WakeOnLanTool.MAGIC_PACKET_BYTES
)

/**
 * Envia magic packets Wake-on-LAN a partir da rede na qual o telefone está.
 *
 * Não abre porta de entrada nem depende do servidor OpenClaw: é uma ação de
 * saída do próprio cliente. Sem um broadcast explícito, cobre o broadcast
 * limitado e os broadcasts IPv4 das interfaces ativas (normalmente Wi-Fi).
 */
object WakeOnLanTool {
    const val DEFAULT_PORT = 9
    const val COMPATIBILITY_PORT = 7
    const val DEFAULT_REPEAT = 3
    private const val MAX_REPEAT = 5
    const val MAGIC_PACKET_BYTES = 102

    fun send(
        macAddress: String,
        broadcastAddress: String? = null,
        port: Int = DEFAULT_PORT,
        repeat: Int = DEFAULT_REPEAT,
        targetIpAddress: String? = null,
        includeCompatibilityRoutes: Boolean = true
    ): WakeOnLanResult {
        require(port in 1..65535) { "Porta Wake-on-LAN deve estar entre 1 e 65535." }
        require(repeat in 1..MAX_REPEAT) { "Repetições Wake-on-LAN devem estar entre 1 e $MAX_REPEAT." }

        val mac = parseMacAddress(macAddress)
        val deliveries = resolveDeliveryPlan(
            broadcastAddress = broadcastAddress,
            targetIpAddress = targetIpAddress,
            requestedPort = port,
            includeCompatibilityRoutes = includeCompatibilityRoutes
        )
        require(deliveries.isNotEmpty()) { "Nenhum destino IPv4 disponível para Wake-on-LAN." }

        val magicPacket = buildMagicPacket(mac)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(repeat) {
                deliveries.forEach { delivery ->
                    val destination = InetAddress.getByName(delivery.destination)
                    socket.send(DatagramPacket(magicPacket, magicPacket.size, destination, delivery.port))
                }
            }
        }

        return WakeOnLanResult(
            macAddress = mac.joinToString(":") { byte ->
                "%02X".format(Locale.US, byte.toInt() and 0xFF)
            },
            destinations = deliveries.map(WakeOnLanDeliveryAttempt::destination).distinct(),
            port = port,
            packetsSent = deliveries.size * repeat,
            ports = deliveries.map(WakeOnLanDeliveryAttempt::port).distinct(),
            deliveries = deliveries
        )
    }

    internal fun buildMagicPacket(mac: ByteArray): ByteArray = ByteArray(MAGIC_PACKET_BYTES).also { packet ->
        java.util.Arrays.fill(packet, 0, 6, 0xFF.toByte())
        for (index in 0 until 16) {
            System.arraycopy(mac, 0, packet, 6 + index * mac.size, mac.size)
        }
    }

    private fun parseMacAddress(value: String): ByteArray {
        val compact = value.trim().replace(Regex("[:.\\-\\s]"), "")
        require(compact.matches(Regex("[0-9a-fA-F]{12}"))) {
            "MAC inválido. Use seis pares hexadecimais, por exemplo AA:BB:CC:DD:EE:FF."
        }
        val bytes = ByteArray(6) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        require(bytes.any { it.toInt() != 0 } && bytes.any { (it.toInt() and 0xFF) != 0xFF }) {
            "MAC de broadcast ou nulo não pode receber Wake-on-LAN."
        }
        return bytes
    }

    private fun resolveDeliveryPlan(
        broadcastAddress: String?,
        targetIpAddress: String?,
        requestedPort: Int,
        includeCompatibilityRoutes: Boolean
    ): List<WakeOnLanDeliveryAttempt> {
        val interfaceBroadcasts = buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) {
                    continue
                }
                networkInterface.interfaceAddresses
                    .mapNotNull { it.broadcast as? Inet4Address }
                    .forEach { address -> add(address.hostAddress) }
            }
        }
        return buildDeliveryPlan(
            broadcastAddress = broadcastAddress,
            targetIpAddress = targetIpAddress,
            requestedPort = requestedPort,
            includeCompatibilityRoutes = includeCompatibilityRoutes,
            interfaceBroadcastAddresses = interfaceBroadcasts
        )
    }

    internal fun buildDeliveryPlan(
        broadcastAddress: String?,
        targetIpAddress: String?,
        requestedPort: Int,
        includeCompatibilityRoutes: Boolean,
        interfaceBroadcastAddresses: List<String>
    ): List<WakeOnLanDeliveryAttempt> {
        val destinations = linkedMapOf<String, WakeOnLanDeliveryMode>()
        fun addDestination(address: Inet4Address, mode: WakeOnLanDeliveryMode) {
            destinations.putIfAbsent(address.hostAddress, mode)
        }

        targetIpAddress?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            val target = InetAddress.getByName(value)
            require(target is Inet4Address) { "O IP unicast Wake-on-LAN deve ser IPv4." }
            require(!target.isMulticastAddress && target.hostAddress != "255.255.255.255") {
                "O IP unicast Wake-on-LAN não pode ser broadcast."
            }
            addDestination(target, WakeOnLanDeliveryMode.UNICAST_LAST_KNOWN_IP)
        }

        val explicit = broadcastAddress?.trim().orEmpty()
        if (explicit.isNotBlank()) {
            val resolved = InetAddress.getByName(explicit)
            require(resolved is Inet4Address) { "O broadcast Wake-on-LAN deve ser um endereço IPv4." }
            addDestination(resolved, WakeOnLanDeliveryMode.SUBNET_BROADCAST)
        }

        if (explicit.isBlank() || includeCompatibilityRoutes) {
            interfaceBroadcastAddresses.forEach { address ->
                val resolved = InetAddress.getByName(address)
                require(resolved is Inet4Address) { "O broadcast Wake-on-LAN deve ser um endereço IPv4." }
                addDestination(resolved, WakeOnLanDeliveryMode.SUBNET_BROADCAST)
            }
        }

        if (explicit.isBlank() || includeCompatibilityRoutes) {
            addDestination(
                InetAddress.getByName("255.255.255.255") as Inet4Address,
                WakeOnLanDeliveryMode.LIMITED_BROADCAST
            )
        }

        val ports = linkedSetOf(requestedPort)
        if (includeCompatibilityRoutes) {
            ports += DEFAULT_PORT
            ports += COMPATIBILITY_PORT
        }
        return destinations.flatMap { (destination, mode) ->
            ports.map { deliveryPort ->
                WakeOnLanDeliveryAttempt(destination, deliveryPort, mode)
            }
        }
    }
}
