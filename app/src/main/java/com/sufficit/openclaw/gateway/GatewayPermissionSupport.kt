package com.sufficit.openclaw.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sufficit.openclaw.gateway.audio.RoomAudioForegroundService
import com.sufficit.openclaw.gateway.config.GatewaySettings
import com.sufficit.openclaw.gateway.config.GatewaySettingsStore
import com.sufficit.openclaw.gateway.runtime.GatewayRuntime

fun persistSettingsAndStartListening(
 context: Context,
 settingsStore: GatewaySettingsStore,
 input: GatewaySettingsInputSnapshot,
 statusText: String
) {
 val settings = buildSettings(
 context = context,
 input = input
 )
 settingsStore.save(settings)
 GatewayRuntime.clearError(statusText)
 RoomAudioForegroundService.start(context)
}

fun handleNotificationPermissionResult(
 granted: Boolean,
 hasMicrophonePermission: Boolean,
 pendingServiceStart: Boolean,
 isNotificationPermissionFullyGranted: () -> Boolean,
 clearPendingServiceStart: () -> Unit,
 openNotificationSettings: () -> Unit,
 showPersistentNotificationToast: () -> Unit,
 showDeniedNotificationToast: () -> Unit,
 startListening: () -> Unit
) {
 GatewayRuntime.update {
 it.copy(
 statusText = if (granted) {
 "Permissao de notificacoes concedida."
 } else {
 "Permissao de notificacoes negada. A escuta precisa da notificacao fixa."
 }
 )
 }

 if (granted && hasMicrophonePermission && pendingServiceStart) {
 if (!isNotificationPermissionFullyGranted()) {
 clearPendingServiceStart()
 openNotificationSettings()
 showPersistentNotificationToast()
 return
 }
 clearPendingServiceStart()
 startListening()
 return
 }

 if (!granted) {
 clearPendingServiceStart()
 openNotificationSettings()
 showDeniedNotificationToast()
 }
}

fun handleMicrophonePermissionResult(
 granted: Boolean,
 hasNotificationPermission: Boolean,
 autoStartEnabled: Boolean,
 requestServiceStart: () -> Unit,
 clearPendingServiceStart: () -> Unit,
 launchNotificationPermission: () -> Unit,
 startListeningAutomatically: () -> Unit
) {
 GatewayRuntime.update {
 it.copy(
 statusText = if (granted) {
 "Permissao de microfone concedida."
 } else {
 "Permissao de microfone negada."
 }
 )
 }

 if (!granted) {
 return
 }

 if (!hasNotificationPermission) {
 if (autoStartEnabled) {
 requestServiceStart()
 } else {
 clearPendingServiceStart()
 }
 launchNotificationPermission()
 return
 }

 if (autoStartEnabled) {
 startListeningAutomatically()
 }
}

fun handleStartForegroundListeningRequest(
 hasPermission: Boolean,
 hasNotificationPermission: Boolean,
 hasNotificationRuntimePermission: Boolean,
 requestServiceStart: () -> Unit,
 clearPendingServiceStart: () -> Unit,
 launchMicrophonePermission: () -> Unit,
 launchNotificationPermission: () -> Unit,
 openNotificationSettings: () -> Unit,
 showNotificationSettingsToast: () -> Unit,
 startListening: () -> Unit
) {
 if (!hasPermission) {
 requestServiceStart()
 launchMicrophonePermission()
 return
 }

 if (!hasNotificationPermission) {
 requestServiceStart()
 if (hasNotificationRuntimePermission) {
 openNotificationSettings()
 showNotificationSettingsToast()
 } else {
 launchNotificationPermission()
 }
 return
 }

 clearPendingServiceStart()
 startListening()
}

fun Context.hasMicrophonePermission(): Boolean {
 return ContextCompat.checkSelfPermission(
 this,
 Manifest.permission.RECORD_AUDIO
 ) == PackageManager.PERMISSION_GRANTED
}

fun Context.hasCameraPermission(): Boolean {
 return ContextCompat.checkSelfPermission(
 this,
 Manifest.permission.CAMERA
 ) == PackageManager.PERMISSION_GRANTED
}

fun Context.hasNotificationRuntimePermission(): Boolean {
 if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
 return true
 }
 return ContextCompat.checkSelfPermission(
 this,
 Manifest.permission.POST_NOTIFICATIONS
 ) == PackageManager.PERMISSION_GRANTED
}

fun Context.hasNotificationPermission(): Boolean {
 if (!hasNotificationRuntimePermission()) {
 return false
 }
 val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
 ?: return false
 if (!manager.areNotificationsEnabled()) {
 return false
 }
 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
 val channel = manager.getNotificationChannel("room-audio-gateway-v2")
 if (channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
 return false
 }
 }
 return true
}

fun Context.openNotificationSettings() {
 val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
 putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 }
 startActivity(intent)
}

fun handleImportedSettingsResult(
 settings: GatewaySettings,
 appliedKeys: List<String>,
 ignoredKeys: List<String>,
 saveSettings: (GatewaySettings) -> Unit,
 reapplyImportedSettings: (GatewaySettings) -> Unit,
 updateStatus: (String) -> Unit
) {
 if (appliedKeys.isEmpty()) {
 updateStatus(
 if (ignoredKeys.isNotEmpty()) {
 "JSON lido, mas sem campos validos para aplicar."
 } else {
 "JSON importado sem alteracoes."
 }
 )
 return
 }

 saveSettings(settings)
 reapplyImportedSettings(settings)
 updateStatus(
 buildString {
 append("Configuracao importada: ")
 append(appliedKeys.joinToString(", "))
 if (ignoredKeys.isNotEmpty()) {
 append(". Ignorados: ")
 append(ignoredKeys.joinToString(", "))
 }
 }
 )
}

fun showNotificationSettingsToast(context: Context) {
 Toast.makeText(
 context,
 "Ative as notificacoes do OpenClaw nas configuracoes do Android.",
 Toast.LENGTH_LONG
 ).show()
}

fun showPersistentNotificationToast(context: Context) {
 Toast.makeText(
 context,
 "Ative as notificacoes do OpenClaw para manter a escuta visivel.",
 Toast.LENGTH_LONG
 ).show()
}

fun showDeniedNotificationToast(context: Context) {
 Toast.makeText(
 context,
 "A notificacao fixa e necessaria para manter a escuta visivel.",
 Toast.LENGTH_LONG
 ).show()
}
