package com.sufficit.ai.gateway.state

import com.sufficit.ai.gateway.ConfigSectionDestination

/**
 * Navigation state for top-level pager/config routing.
 */
data class GatewayNavigationState(
    val pagerPage: Int,
    val configDestination: ConfigSectionDestination
)
