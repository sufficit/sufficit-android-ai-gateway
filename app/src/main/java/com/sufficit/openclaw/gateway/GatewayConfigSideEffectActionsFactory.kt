package com.sufficit.openclaw.gateway

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.sufficit.openclaw.gateway.config.shareGatewaySettingsBackup
import com.sufficit.openclaw.gateway.history.TranscriptHistoryLogger

fun buildConfigPageSideEffectActions(
    context: Context,
    currentHistoryState: () -> GatewayHistoryState,
    updateHistoryState: (GatewayHistoryState) -> Unit,
    currentSettingsInputSnapshot: () -> GatewaySettingsInputSnapshot,
    launchSettingsImport: ActivityResultLauncher<Array<String>>,
    requestMicrophonePermission: () -> Unit,
    requestCameraPermission: () -> Unit,
    openGestureDebug: () -> Unit
): ConfigPageSideEffectActions {
    return ConfigPageSideEffectActions(
        openGestureDebug = openGestureDebug,
        exportSettings = {
            val settings = buildSettings(
                context = context,
                input = currentSettingsInputSnapshot()
            )
            val exported = shareGatewaySettingsBackup(context, settings)
            updateHistoryState(
                currentHistoryState().copy(
                    settingsBackupStatus = if (exported) {
                        "Backup JSON pronto para exportacao."
                    } else {
                        "Falha ao preparar o backup JSON."
                    }
                )
            )
        },
        importSettings = {
            launchSettingsImport.launch(arrayOf("application/json", "text/plain"))
        },
        exportHistory = {
            val exported = shareTranscriptHistory(context)
            updateHistoryState(
                currentHistoryState().copy(
                    actionStatus = if (exported) {
                        "Historico pronto para exportacao."
                    } else {
                        "Ainda nao ha frases registradas para exportar."
                    },
                    refreshTick = currentHistoryState().refreshTick + 1
                )
            )
        },
        clearHistory = {
            TranscriptHistoryLogger.clear(context)
            updateHistoryState(
                currentHistoryState().copy(
                    actionStatus = "Historico apagado.",
                    refreshTick = currentHistoryState().refreshTick + 1
                )
            )
        },
        requestPermission = requestMicrophonePermission,
        requestCameraPermission = requestCameraPermission
    )
}
