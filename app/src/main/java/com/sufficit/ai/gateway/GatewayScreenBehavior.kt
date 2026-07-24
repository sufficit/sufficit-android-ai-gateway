package com.sufficit.ai.gateway

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.sufficit.ai.gateway.config.ScreenMode
import kotlinx.coroutines.delay

/**
 * Deriva keepScreenOn/wakeRequested a partir de [screenAttentionUntilEpochMs]
 * e chama [HandleScreenBehavior] — isolado num composable proprio de
 * proposito: o timer de 500ms so precisa recompor ESTE leaf, nao a arvore
 * inteira (HorizontalPager/DashboardPage/chat) que o chamava antes. Achado
 * real de performance: um produceState incondicional vivendo no mesmo escopo
 * do resto da tela recompunha tudo a cada 500ms, mesmo parado/ocioso.
 *
 * O timer so tickeia ENQUANTO a janela de atencao de tela estiver no futuro
 * (mesmo padrao do nowForSystemInfo em GatewayDashboardUi.kt) — parado/ocioso
 * nao gera nenhuma recomposicao daqui.
 */
@Composable
fun HandleScreenAttentionBehavior(
    activity: ComponentActivity,
    effectiveScreenMode: ScreenMode,
    screenAttentionUntilEpochMs: Long,
    textInputModeActive: Boolean
) {
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = screenAttentionUntilEpochMs
    ) {
        while (screenAttentionUntilEpochMs > System.currentTimeMillis()) {
            value = System.currentTimeMillis()
            delay(500)
        }
        value = System.currentTimeMillis()
    }
    val screenAttentionActive = screenAttentionUntilEpochMs > now
    // O nivel 1 continua vivo com PARTIAL_WAKE_LOCK, que mantem apenas CPU e
    // microfone. Quando o usuario troca o chat para digitacao, o display deve
    // voltar imediatamente para a politica normal de timeout do Android,
    // inclusive se a preferencia geral estiver em "Sempre ligado".
    val keepScreenOn = when (effectiveScreenMode) {
        ScreenMode.ALWAYS_ON -> !textInputModeActive
        ScreenMode.ALWAYS_OFF -> false
        ScreenMode.ACTIVITY -> screenAttentionActive && !textInputModeActive
    }

    HandleScreenBehavior(
        activity = activity,
        screenMode = effectiveScreenMode,
        keepScreenOn = keepScreenOn,
        wakeRequested = effectiveScreenMode == ScreenMode.ACTIVITY &&
            screenAttentionActive &&
            !textInputModeActive
    )
}

@Composable
fun HandleScreenBehavior(
    activity: ComponentActivity,
    screenMode: ScreenMode,
    keepScreenOn: Boolean,
    wakeRequested: Boolean
) {
    DisposableEffect(activity, keepScreenOn, screenMode) {
        val window = activity.window
        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            if (!keepScreenOn) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    LaunchedEffect(activity, screenMode, wakeRequested) {
        if (!wakeRequested) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(false)
                activity.setTurnScreenOn(false)
            }
            return@LaunchedEffect
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(activity, null)
        }
    }
}
