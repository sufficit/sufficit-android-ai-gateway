package com.sufficit.ai.gateway

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sufficit.ai.gateway.config.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HandleModelAvailabilityEffects(
    context: Context,
    settingsState: GatewaySettingsState,
    transcriptionModelLabel: String,
    optionsRefreshTick: Int,
    currentModelState: () -> GatewayModelState,
    updateModelState: (GatewayModelState) -> Unit,
    updateLocalModelOptions: (List<LocalModelOption>) -> Unit
) {
    LaunchedEffect(settingsState.localModelName) {
        updateModelState(
            currentModelState().copy(localModelExists = isLocalModelReady(context, settingsState.localModelName))
        )
        val normalizedName = settingsState.localModelName.trim()
        if (normalizedName.isBlank()) {
            updateModelState(
                currentModelState().copy(
                    huggingFaceModelExists = null,
                    huggingFaceCheckInProgress = false
                )
            )
            return@LaunchedEffect
        }
        updateModelState(currentModelState().copy(huggingFaceCheckInProgress = true))
        val exists = withContext(Dispatchers.IO) { checkHuggingFaceModelExists(normalizedName) }
        updateModelState(
            currentModelState().copy(
                huggingFaceModelExists = exists,
                huggingFaceCheckInProgress = false
            )
        )
    }

    LaunchedEffect(optionsRefreshTick) {
        updateModelState(currentModelState().copy(localOptionsLoading = true))
        updateLocalModelOptions(withContext(Dispatchers.IO) { loadLocalModelOptions(context) })
        updateModelState(currentModelState().copy(localOptionsLoading = false))
    }

    LaunchedEffect(settingsState.transcriptionMode, transcriptionModelLabel) {
        if (TranscriptionMode.fromPersistedValue(settingsState.transcriptionMode) != TranscriptionMode.LOCAL) {
            return@LaunchedEffect
        }

        val actualModel = transcriptionModelLabel.trim()
        if (actualModel.isNotBlank() && actualModel != settingsState.localModelName) {
            settingsState.localModelName = actualModel
        }
    }
}
