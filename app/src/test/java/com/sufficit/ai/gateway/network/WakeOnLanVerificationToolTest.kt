package com.sufficit.ai.gateway.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CancellationException

class WakeOnLanVerificationToolTest {
    @Test(expected = CancellationException::class)
    fun canceledVerificationStopsBeforeNetworkIO() {
        WakeOnLanVerificationTool.verifyTarget(
            macAddress = "02:00:00:00:00:01",
            isCanceled = { true }
        )
    }

    @Test
    fun reportsConfirmedOnlineWhenTargetAppearsAfterWake() {
        assertEquals(
            WakeOnLanVerificationStatus.CONFIRMED_ONLINE,
            WakeOnLanVerificationTool.classify(
                reachableBeforeWake = false,
                reachableAfterWake = true,
                hasKnownIpAddress = true,
                sendError = null
            )
        )
    }

    @Test
    fun reportsNotRespondingWhenKnownIpRemainsOffline() {
        assertEquals(
            WakeOnLanVerificationStatus.NOT_RESPONDING,
            WakeOnLanVerificationTool.classify(
                reachableBeforeWake = false,
                reachableAfterWake = false,
                hasKnownIpAddress = true,
                sendError = null
            )
        )
    }

    @Test
    fun reportsUnverifiableWhenMacNeverAppearsAndIpIsUnknown() {
        assertEquals(
            WakeOnLanVerificationStatus.UNVERIFIABLE,
            WakeOnLanVerificationTool.classify(
                reachableBeforeWake = null,
                reachableAfterWake = null,
                hasKnownIpAddress = false,
                sendError = null
            )
        )
    }

    @Test
    fun sendFailureTakesPriorityOverPresenceSignals() {
        assertEquals(
            WakeOnLanVerificationStatus.SEND_FAILED,
            WakeOnLanVerificationTool.classify(
                reachableBeforeWake = null,
                reachableAfterWake = null,
                hasKnownIpAddress = false,
                sendError = "sem broadcast"
            )
        )
    }
}
