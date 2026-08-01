package com.sufficit.ai.gateway.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerStoreTest {
    @Test
    fun `migrates built in server to canonical endpoint and authentication`() {
        val legacy = McpServerConfiguration(
            id = McpServerStore.SUFFICIT_SERVER_ID,
            namespace = "old",
            name = "Old Sufficit",
            endpoint = "https://obsolete.invalid/mcp",
            authenticationMode = McpAuthenticationMode.NONE,
            enabled = false,
            summary = McpCapabilitySummary(tools = listOf("memory_search"))
        )

        val result = McpServerStore.reconcileBuiltInServer(listOf(legacy)).single()

        assertEquals(SufficitMcpClient.DEFAULT_ENDPOINT, result.endpoint)
        assertEquals(McpAuthenticationMode.SUFFICIT, result.authenticationMode)
        assertEquals("sufficit", result.namespace)
        assertFalse(result.enabled)
        assertEquals(listOf("memory_search"), result.summary.tools)
        assertTrue(result.builtIn)
    }

    @Test
    fun `seeds canonical built in server without duplicating custom servers`() {
        val custom = McpServerConfiguration(
            id = "custom",
            namespace = "custom",
            name = "Custom",
            endpoint = "https://example.test/mcp",
            authenticationMode = McpAuthenticationMode.NONE
        )

        val result = McpServerStore.reconcileBuiltInServer(listOf(custom))

        assertEquals(2, result.size)
        assertEquals(McpServerStore.SUFFICIT_SERVER_ID, result.first().id)
        assertEquals(custom, result.last())
    }
}
