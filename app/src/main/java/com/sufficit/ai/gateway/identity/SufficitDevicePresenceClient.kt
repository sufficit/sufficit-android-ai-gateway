package com.sufficit.ai.gateway.identity

import android.content.Context
import android.os.Build
import com.sufficit.ai.gateway.config.InstallationId
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Publishes this app installation to the shared Sufficit AI device inventory.
 * The Gateway is an AI/VPN client, not an OpenAI-compatible model provider, so
 * [providesModels] is explicitly false and no provider record is created.
 */
class SufficitDevicePresenceClient(context: Context) {
    private val appContext = context.applicationContext
    private val tokenProvider = SufficitAccessTokenProvider(appContext)
    private val http = OkHttpClient()

    suspend fun announce() {
        val session = tokenProvider.authenticatedSession()
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val deviceModel = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { "Android device" }
        val appVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty()

        val payload = JSONObject().apply {
            put("deviceInstanceId", InstallationId.get(appContext))
            put("deviceName", deviceModel)
            put("deviceModel", deviceModel)
            put("appVersion", appVersion)
            put("applicationId", appContext.packageName)
            put("applicationName", APPLICATION_NAME)
            put("providesModels", false)
        }
        val request = Request.Builder()
            .url(SELF_ANNOUNCE_ENDPOINT)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request)
    }

    private suspend fun execute(request: Request) = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val detail = runCatching {
                            JSONObject(it.body?.string().orEmpty()).optString("error")
                        }.getOrNull()?.takeIf(String::isNotBlank)
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException(detail ?: "Sufficit AI device announce HTTP ${it.code}")
                            )
                        }
                        return
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        })
    }

    private companion object {
        const val SELF_ANNOUNCE_ENDPOINT = "https://ai.sufficit.com.br/api/ai/mobile-devices/self-announce"
        const val APPLICATION_NAME = "Sufficit AI Gateway"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
