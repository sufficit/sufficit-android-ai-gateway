package com.sufficit.ai.gateway

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import kotlinx.coroutines.delay

@Composable
fun HandleSettingsPersistenceEffect(
    context: Context,
    settingsStore: GatewaySettingsStore,
    settingsInputSnapshot: GatewaySettingsInputSnapshot
) {
    LaunchedEffect(settingsInputSnapshot) {
        delay(300)
        val settings = buildSettings(
            context = context,
            input = settingsInputSnapshot
        )
        settingsStore.save(settings)
    }
}
