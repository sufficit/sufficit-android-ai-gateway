package com.sufficit.ai.gateway.network

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import br.com.sufficit.vpn.ipc.ISufficitVpnService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Cliente AIDL do VpnService compartilhado; não contém implementação VPN própria. */
class SufficitVpnClient(private val appContext: Context) {
    @Volatile private var service: ISufficitVpnService? = null
    @Volatile private var bound = false
    private val pendingConnect = AtomicReference<CompletableFuture<Boolean>?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ISufficitVpnService.Stub.asInterface(binder)
            bound = service != null
            pendingConnect.getAndSet(null)?.complete(bound)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) {
            onServiceDisconnected(name)
            pendingConnect.getAndSet(null)?.complete(false)
        }
    }

    fun connectAndRegister(port: Int): Boolean {
        if (!ensureBound()) return false
        return runCatching {
            val remote = requireNotNull(service)
            check(remote.protocolVersion >= PROTOCOL_VERSION)
            remote.connect()
            remote.registerLocalService(SERVICE_NAME, port, "tcp")
        }.onFailure { Log.w(TAG, "Falha ao registrar API do Gateway na Sufficit VPN", it) }
            .getOrDefault(false)
    }

    fun unregister(port: Int) {
        runCatching { service?.unregisterLocalService(SERVICE_NAME, port, "tcp") }
            .onFailure { Log.d(TAG, "Registro VPN já não estava disponível", it) }
    }

    fun release() {
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
    }

    private fun ensureBound(timeoutMs: Long = BIND_TIMEOUT_MS): Boolean {
        if (bound && service != null) return true
        val future = CompletableFuture<Boolean>()
        return if (!pendingConnect.compareAndSet(null, future)) {
            awaitConnection(pendingConnect.get(), timeoutMs)
        } else {
            val requested = runCatching {
                appContext.bindService(
                    Intent(BIND_ACTION).setPackage(VPN_PACKAGE),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
            if (!requested) {
                pendingConnect.set(null)
                false
            } else {
                awaitConnection(future, timeoutMs)
            }
        }
    }

    private fun awaitConnection(future: CompletableFuture<Boolean>?, timeoutMs: Long): Boolean =
        try {
            future?.get(timeoutMs, TimeUnit.MILLISECONDS) ?: false
        } catch (_: TimeoutException) {
            pendingConnect.set(null)
            false
        }

    companion object {
        private const val TAG = "SufficitVpnClient"
        private const val VPN_PACKAGE = "br.com.sufficit.vpn"
        private const val BIND_ACTION = "br.com.sufficit.vpn.BIND"
        private const val CONSENT_ACTION = "br.com.sufficit.vpn.action.REQUEST_CONSENT"
        private const val PROTOCOL_VERSION = 1
        private const val SERVICE_NAME = "android-ai-gateway"
        private const val BIND_TIMEOUT_MS = 5_000L

        fun requestConsent(activity: Activity) {
            runCatching {
                activity.startActivity(Intent(CONSENT_ACTION).setPackage(VPN_PACKAGE))
            }.onFailure { Log.w(TAG, "Sufficit VPN ainda não está instalada", it) }
        }
    }
}
