package com.sufficit.ai.gateway.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/** Resultado de um disparo Wake-on-LAN concluído no cliente Android. */
data class WakeOnLanResult(
    val macAddress: String,
    val destinations: List<String>,
    val port: Int,
    val packetsSent: Int
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
    const val DEFAULT_REPEAT = 3
    private const val MAX_REPEAT = 5

    fun send(
        macAddress: String,
        broadcastAddress: String? = null,
        port: Int = DEFAULT_PORT,
        repeat: Int = DEFAULT_REPEAT
    ): WakeOnLanResult {
        require(port in 1..65535) { "Porta Wake-on-LAN deve estar entre 1 e 65535." }
        require(repeat in 1..MAX_REPEAT) { "Repetições Wake-on-LAN devem estar entre 1 e $MAX_REPEAT." }

        val mac = parseMacAddress(macAddress)
        val destinations = resolveDestinations(broadcastAddress)
        require(destinations.isNotEmpty()) { "Nenhum broadcast IPv4 disponível para Wake-on-LAN." }

        val magicPacket = ByteArray(6 + 16 * mac.size).also { packet ->
            java.util.Arrays.fill(packet, 0, 6, 0xFF.toByte())
            for (index in 0 until 16) {
                System.arraycopy(mac, 0, packet, 6 + index * mac.size, mac.size)
            }
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(repeat) {
                destinations.forEach { destination ->
                    socket.send(DatagramPacket(magicPacket, magicPacket.size, destination, port))
                }
            }
        }

        return WakeOnLanResult(
            macAddress = mac.joinToString(":") { byte ->
                "%02X".format(Locale.US, byte.toInt() and 0xFF)
            },
            destinations = destinations.map { destination ->
                destination.hostAddress ?: destination.toString()
            },
            port = port,
            packetsSent = destinations.size * repeat
        )
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

    private fun resolveDestinations(broadcastAddress: String?): List<InetAddress> {
        val explicit = broadcastAddress?.trim().orEmpty()
        if (explicit.isNotBlank()) {
            val resolved = InetAddress.getByName(explicit)
            require(resolved is Inet4Address) { "O broadcast Wake-on-LAN deve ser um endereço IPv4." }
            return listOf(resolved)
        }

        val destinations = linkedSetOf<InetAddress>()
        // Broadcast limitado: útil mesmo quando a interface não expõe a máscara.
        destinations += InetAddress.getByName("255.255.255.255")
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) {
                continue
            }
            networkInterface.interfaceAddresses
                .mapNotNull { it.broadcast as? Inet4Address }
                .forEach(destinations::add)
        }
        return destinations.toList()
    }
}
