package com.sufficit.ai.gateway.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeOnLanToolTest {
    @Test
    fun magicPacketUsesTheStandardHeaderAndSixteenMacCopies() {
        val mac = byteArrayOf(
            0x34,
            0x5A,
            0x60,
            0xBD.toByte(),
            0x8B.toByte(),
            0x60
        )

        val packet = WakeOnLanTool.buildMagicPacket(mac)

        assertEquals(WakeOnLanTool.MAGIC_PACKET_BYTES, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        repeat(16) { copyIndex ->
            assertTrue(packet.copyOfRange(6 + copyIndex * mac.size, 12 + copyIndex * mac.size).contentEquals(mac))
        }
    }

    @Test
    fun compatibilityPlanCoversKnownIpBroadcastsAndPortsSevenAndNine() {
        val plan = WakeOnLanTool.buildDeliveryPlan(
            broadcastAddress = "192.168.31.255",
            targetIpAddress = "192.168.31.220",
            requestedPort = 9,
            includeCompatibilityRoutes = true,
            interfaceBroadcastAddresses = listOf("192.168.31.255", "10.0.0.255")
        )

        assertEquals(8, plan.size)
        assertEquals(setOf(7, 9), plan.map(WakeOnLanDeliveryAttempt::port).toSet())
        assertTrue(plan.any { it.destination == "192.168.31.220" && it.mode == WakeOnLanDeliveryMode.UNICAST_LAST_KNOWN_IP })
        assertTrue(plan.any { it.destination == "192.168.31.255" && it.mode == WakeOnLanDeliveryMode.SUBNET_BROADCAST })
        assertTrue(plan.any { it.destination == "255.255.255.255" && it.mode == WakeOnLanDeliveryMode.LIMITED_BROADCAST })
        assertEquals(plan.size, plan.map { it.destination to it.port }.distinct().size)
    }

    @Test
    fun restrictedPlanUsesOnlyRequestedPortAndExplicitRoutes() {
        val plan = WakeOnLanTool.buildDeliveryPlan(
            broadcastAddress = "192.168.31.255",
            targetIpAddress = "192.168.31.220",
            requestedPort = 4000,
            includeCompatibilityRoutes = false,
            interfaceBroadcastAddresses = listOf("10.0.0.255")
        )

        assertEquals(2, plan.size)
        assertEquals(setOf(4000), plan.map(WakeOnLanDeliveryAttempt::port).toSet())
        assertEquals(setOf("192.168.31.220", "192.168.31.255"), plan.map(WakeOnLanDeliveryAttempt::destination).toSet())
    }
}
