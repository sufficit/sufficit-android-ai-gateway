package com.sufficit.ai.gateway

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tela de configuracao = agente em silencio: para de despachar/falar (para
 * nao se intrometer no cadastro de voz/palavra de ativacao) e interrompe
 * qualquer fala em andamento ao entrar. O microfone segue para amostras.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HandleConfigScreenActiveEffect(
    activity: ComponentActivity,
    pagerState: PagerState,
    configPageIndex: Int
) {
    LaunchedEffect(pagerState.currentPage) {
        val onConfig = pagerState.currentPage == configPageIndex
        GatewayRuntime.setConfigScreenActive(onConfig)
        if (onConfig) {
            RoomAudioForegroundService.interruptAssistant(activity)
        }
    }
    DisposableEffect(Unit) {
        onDispose { GatewayRuntime.setConfigScreenActive(false) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GatewayBackHandler(
    activity: ComponentActivity,
    context: Context,
    uiScope: CoroutineScope,
    pagerState: PagerState,
    initialPage: Int,
    configPageIndex: Int,
    exitConfirmationWindowMs: Long,
    currentUiState: () -> GatewayUiState,
    updateUiState: (GatewayUiState) -> Unit
) {
    BackHandler {
        val uiState = currentUiState()
        when {
            pagerState.currentPage == configPageIndex &&
                uiState.configDestination != ConfigSectionDestination.HOME -> {
                updateUiState(uiState.copy(configDestination = uiState.configDestination.parent()))
            }

            pagerState.currentPage != initialPage -> {
                uiScope.launch {
                    pagerState.animateScrollToPage(0)
                }
            }

            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - uiState.lastBackPressedAt <= exitConfirmationWindowMs) {
                    activity.finish()
                } else {
                    updateUiState(uiState.copy(lastBackPressedAt = now))
                    Toast.makeText(
                        context,
                        "Aperte novamente para sair.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
