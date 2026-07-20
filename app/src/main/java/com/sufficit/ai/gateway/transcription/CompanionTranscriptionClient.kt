package com.sufficit.ai.gateway.transcription

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.sufficit.ipc.transcription.ISufficitTranscriptionCallback
import com.sufficit.ipc.transcription.ISufficitTranscriptionService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

private const val COMPANION_PACKAGE = "com.sufficit.ai.mobiledevice"
private const val BIND_ACTION = "com.sufficit.ipc.transcription.ACTION_BIND_TRANSCRIPTION_SERVICE"

/**
 * Thrown when a companion-app transcription request fails. [errorCode] mirrors
 * `ISufficitTranscriptionService.ERROR_*` (see the AIDL) when the failure came from the
 * provider itself; [ERROR_CLIENT] marks a client-side failure (bind timeout/failure,
 * disconnect) with no matching provider error code.
 */
class CompanionTranscriptionException(
    val errorCode: Int,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause) {
    val isNotReady: Boolean get() = errorCode == ISufficitTranscriptionService.ERROR_NOT_READY

    companion object {
        const val ERROR_CLIENT = -1
    }
}

/**
 * Blocking client for the Sufficit Transcription IPC contract
 * (aidl/com/sufficit/ipc/transcription/, docs/ipc-transcription-contract.md) — talks to
 * sufficit-mobile-ai-models's `TranscriptionIpcService`, if installed, instead of a configured
 * remote/local Whisper backend.
 *
 * Deliberately blocking (not `suspend`) to match this file's siblings
 * ([WhisperApiClient.transcribe], `transcribeLocalWithTimeout` in RoomAudioForegroundService):
 * the transcription dispatch site runs on a dedicated single-thread executor, not a coroutine.
 *
 * One instance per [RoomAudioForegroundService][com.sufficit.ai.gateway.audio.RoomAudioForegroundService]
 * lifetime — keeps the binding alive across segments instead of binding/unbinding per call.
 */
class CompanionTranscriptionClient(private val appContext: Context) {

    @Volatile private var service: ISufficitTranscriptionService? = null
    @Volatile private var bound = false
    private val pendingConnect = AtomicReference<CompletableFuture<Boolean>?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ISufficitTranscriptionService.Stub.asInterface(binder)
            bound = true
            pendingConnect.getAndSet(null)?.complete(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun isCompanionAppInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(COMPANION_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun ensureBound(timeoutMs: Long): Boolean {
        if (bound && service != null) return true
        if (!isCompanionAppInstalled()) return false

        val future = CompletableFuture<Boolean>()
        if (!pendingConnect.compareAndSet(null, future)) {
            // A bind is already in flight from another call on another thread — wait on it.
            return runCatching {
                pendingConnect.get()?.get(timeoutMs, TimeUnit.MILLISECONDS) ?: false
            }.getOrDefault(false)
        }

        val probeIntent = Intent(BIND_ACTION).setPackage(COMPANION_PACKAGE)
        val resolved = appContext.packageManager.resolveService(probeIntent, 0)
        if (resolved == null) {
            pendingConnect.set(null)
            return false
        }
        val explicitIntent = Intent(BIND_ACTION).setClassName(COMPANION_PACKAGE, resolved.serviceInfo.name)
        val requested = runCatching { appContext.bindService(explicitIntent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!requested) {
            pendingConnect.set(null)
            return false
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pendingConnect.set(null)
            false
        }
    }

    /**
     * True if the companion app is installed, reachable, and currently reports a ready
     * (model-loaded) transcription engine. Cheap presence/status check for UI display — does
     * not attempt an actual transcription.
     */
    fun isReady(bindTimeoutMs: Long = 3_000L): Boolean {
        if (!ensureBound(bindTimeoutMs)) return false
        return try {
            service?.isReady() == true
        } catch (_: Exception) {
            false
        }
    }

    fun transcribe(
        wavBytes: ByteArray,
        languageHint: String,
        bindTimeoutMs: Long = 5_000L,
        resultTimeoutMs: Long = 180_000L
    ): WhisperTranscriptionResult {
        if (!ensureBound(bindTimeoutMs)) {
            throw CompanionTranscriptionException(
                ISufficitTranscriptionService.ERROR_NOT_READY,
                "sufficit-mobile-ai-models nao instalado ou servico indisponivel"
            )
        }
        val svc = service ?: throw CompanionTranscriptionException(
            ISufficitTranscriptionService.ERROR_NOT_READY,
            "sem conexao com o companion app"
        )

        val requestId = UUID.randomUUID().toString()
        val result = CompletableFuture<WhisperTranscriptionResult>()
        val callback = object : ISufficitTranscriptionCallback.Stub() {
            override fun onResult(id: String, text: String) {
                if (id == requestId) result.complete(WhisperTranscriptionResult(text = text))
            }

            override fun onError(id: String, errorCode: Int, message: String) {
                if (id == requestId) {
                    result.completeExceptionally(CompanionTranscriptionException(errorCode, message))
                }
            }
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        // Write on a plain thread, not the calling thread: a pipe write blocks until the far
        // side reads, and the far side (the companion app's process) only starts reading once
        // svc.transcribe() below actually reaches it — writing inline here would deadlock.
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(wavBytes) }
            }
        }, "companion-transcribe-pipe-writer").start()

        try {
            svc.transcribe(requestId, readEnd, languageHint, callback)
        } catch (ex: android.os.RemoteException) {
            runCatching { readEnd.close() }
            throw CompanionTranscriptionException(
                CompanionTranscriptionException.ERROR_CLIENT,
                ex.message ?: "falha ao chamar o companion app",
                cause = ex
            )
        }

        return try {
            result.get(resultTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            runCatching { svc.cancel(requestId) }
            throw CompanionTranscriptionException(ISufficitTranscriptionService.ERROR_TIMEOUT, "tempo esgotado")
        } catch (ex: java.util.concurrent.ExecutionException) {
            throw (ex.cause as? CompanionTranscriptionException) ?: CompanionTranscriptionException(
                CompanionTranscriptionException.ERROR_CLIENT,
                ex.message ?: "falha desconhecida",
                cause = ex
            )
        }
    }

    fun release() {
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        service = null
    }
}
