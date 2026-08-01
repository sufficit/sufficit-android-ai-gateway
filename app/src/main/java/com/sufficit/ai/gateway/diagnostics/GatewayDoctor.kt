package com.sufficit.ai.gateway.diagnostics

import android.content.Context
import android.os.Build
import com.sufficit.ai.gateway.audio.wake.WakeWordStore
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.ledger.InteractionLedger
import com.sufficit.ai.gateway.ledger.LedgerSanitizer
import com.sufficit.ai.gateway.mcp.McpServerStore
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Gera um snapshot de suporte sem texto de conversa, áudio ou credenciais. */
object GatewayDoctor {
    fun export(context: Context, capabilityCatalog: JSONArray): File {
        val state = GatewayRuntime.state().value
        val settings = GatewaySettingsStore(context).load()
        val ledgerEvents = InteractionLedger(context).recentEvents(300)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject()
            .put("schema", "sufficit.android-gateway-doctor/v1")
            .put("generatedAtUtc", Instant.now().toString())
            .put(
                "app",
                JSONObject()
                    .put("applicationId", context.packageName)
                    .put("versionName", packageInfo.versionName)
                    .put("versionCode", packageInfo.longVersionCode)
            )
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT)
            )
            .put(
                "runtime",
                JSONObject()
                    .put("listening", state.listening)
                    .put("microphoneCaptureActive", state.microphoneCaptureActive)
                    .put("transcribing", state.transcribing)
                    .put("speakingBack", state.speakingBack)
                    .put("textInputModeActive", state.textInputModeActive)
                    .put("assistantProcessing", state.assistantProcessing)
                    .put("transcriptionQueueCount", state.transcriptionQueueCount)
                    .put("remoteDispatchQueueCount", state.openClawDispatchQueueCount)
                    .put("transcriptionBackend", state.transcriptionBackendLabel)
                    .put("transcriptionModel", state.transcriptionModelLabel)
                    .put("cameraGestureStatus", state.cameraGestureStatus)
                    .put("openClawStatus", state.openClawStatus)
            )
            .put(
                "configurationPresence",
                JSONObject()
                    .put("remoteAgentEndpoint", settings.openClawGatewayUrl.isNotBlank())
                    .put("remoteAgentToken", settings.openClawGatewayToken.isNotBlank())
                    .put("deviceToken", settings.openClawDeviceToken.isNotBlank())
                    .put("sessionKey", settings.openClawSessionKey.isNotBlank())
                    .put("assistantVoiceEnabled", settings.assistantVoiceEnabled)
                    .put("cameraGestureEnabled", settings.cameraGestureEnabled)
                    .put("wakeWordEnabled", WakeWordStore(context).loadConfig().enabled)
            )
            .put("capabilities", JSONArray(capabilityCatalog.toString()))
            .put(
                "mcpServers",
                JSONArray().apply {
                    McpServerStore(context).list().forEach { server ->
                        put(
                            JSONObject()
                                .put("namespace", server.namespace)
                                .put("name", server.name)
                                .put("enabled", server.enabled)
                                .put("authenticationMode", server.authenticationMode.persistedValue)
                                .put("toolCount", server.summary.tools.size)
                                .put("promptCount", server.summary.prompts.size)
                                .put("resourceCount", server.summary.resources.size)
                                .put("discoveredAtEpochMs", server.summary.discoveredAtEpochMs)
                                .put("error", server.summary.error?.let(LedgerSanitizer::safeSummary))
                        )
                    }
                }
            )
            .put(
                "ledgerEvents",
                JSONArray().apply {
                    ledgerEvents.forEach { event ->
                        put(
                            JSONObject()
                                .put("id", event.id)
                                .put("turnId", event.turnId)
                                .put("callId", event.callId)
                                .put("category", event.category)
                                .put("state", event.state)
                                .put("summary", event.summary)
                                .put("details", event.detailsJson?.let(::JSONObject))
                                .put("atEpochMs", event.atEpochMs)
                        )
                    }
                }
            )

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return File(exportDir, "sufficit-gateway-doctor-$timestamp.json").also { file ->
            file.writeText(LedgerSanitizer.sanitizeJson(report).toString(2))
        }
    }
}
