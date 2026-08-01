package com.sufficit.ai.gateway.capabilities

import com.sufficit.ai.gateway.capabilities.adapters.WakeOnLanCapability
import com.sufficit.ai.gateway.capabilities.adapters.NativeCapabilityDescriptors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCapabilityRegistryTest {
    @Test
    fun catalogAndDispatchUseTheSameWakeOnLanDescriptor() {
        var dispatchedTool: String? = null
        val registry = ClientCapabilityRegistry(
            listOf(WakeOnLanCapability.descriptor { dispatchedTool = it.tool })
        )
        val catalog = JSONArray().also(registry::appendCatalog)

        assertEquals(1, catalog.length())
        assertEquals("wakeonlan", catalog.getJSONObject(0).getString("tool"))
        assertTrue(catalog.getJSONObject(0).getJSONObject("inputSchema").has("properties"))
        assertEquals("available", catalog.getJSONObject(0).getJSONObject("availability").getString("status"))

        val result = registry.dispatch(
            JSONObject().put("tool", "wol").put("mac", "02:00:00:00:00:01")
                .toAgentClientActionRequest()
        )

        assertTrue(result is CapabilityDispatchResult.Dispatched)
        assertEquals("wol", dispatchedTool)
    }

    @Test
    fun invalidWakeOnLanRequestNeverReachesTheHandler() {
        var executed = false
        val registry = ClientCapabilityRegistry(
            listOf(WakeOnLanCapability.descriptor { executed = true })
        )

        val result = registry.dispatch(
            JSONObject().put("tool", "wakeonlan").toAgentClientActionRequest()
        )

        assertTrue(result is CapabilityDispatchResult.Invalid)
        assertFalse(executed)
    }

    @Test
    fun unavailableCapabilityRemainsInTheCatalogWithAReason() {
        val descriptor = ClientCapabilityDescriptor(
            name = "camera_fixture",
            description = "fixture",
            inputSchema = JSONObject().put("type", "object"),
            timeoutMs = 1_000L,
            availability = {
                CapabilityAvailability(
                    CapabilityAvailabilityStatus.PERMISSION_REQUIRED,
                    reason = "camera_permission"
                )
            },
            execute = {}
        )
        val registry = ClientCapabilityRegistry(listOf(descriptor))
        val catalog = JSONArray().also(registry::appendCatalog)

        assertEquals(
            "permission_required",
            catalog.getJSONObject(0).getJSONObject("availability").getString("status")
        )
        assertTrue(
            registry.dispatch(
                JSONObject().put("tool", "camera_fixture").toAgentClientActionRequest()
            ) is CapabilityDispatchResult.Unavailable
        )
    }

    @Test
    fun everyNativeAliasResolvesToTheDescriptorThatAdvertisesIt() {
        val registry = ClientCapabilityRegistry(
            NativeCapabilityDescriptors.create { _, _ -> }
        )

        registry.descriptors().forEach { descriptor ->
            descriptor.allNames.forEach { alias ->
                assertEquals(descriptor.name, registry.resolve(alias)?.name)
            }
        }
        assertEquals(registry.descriptors().size, registry.catalog().length())
    }

    @Test
    fun dynamicMcpGroupCanBeReplacedWithoutChangingNativeCapabilities() {
        val native = ClientCapabilityDescriptor(
            name = "native_fixture",
            description = "native",
            inputSchema = JSONObject().put("type", "object"),
            timeoutMs = 1_000L,
            execute = {}
        )
        val firstRemote = native.copy(name = "mcp__first", description = "first")
        val secondRemote = native.copy(name = "mcp__second", description = "second")
        val registry = ClientCapabilityRegistry(listOf(native))

        registry.replaceDynamic("mcp", listOf(firstRemote))
        assertTrue(registry.resolve("mcp__first") != null)
        assertTrue(registry.resolve("native_fixture") != null)

        registry.replaceDynamic("mcp", listOf(secondRemote))
        assertTrue(registry.resolve("mcp__first") == null)
        assertTrue(registry.resolve("mcp__second") != null)
        assertTrue(registry.resolve("native_fixture") != null)
    }
}
