package com.sufficit.ai.gateway.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sufficit.ai.gateway.config.GatewaySettings

/**
 * Simple factory used while the migration still builds the ViewModel from runtime-loaded settings.
 */
class GatewayViewModelFactory(
    private val initialSettings: GatewaySettings
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GatewayViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return GatewayViewModel(initialSettings) as T
    }
}
