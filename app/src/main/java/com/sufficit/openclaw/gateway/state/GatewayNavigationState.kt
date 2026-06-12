package com.sufficit.openclaw.gateway.state

import com.sufficit.openclaw.gateway.ConfigSectionDestination

/**
 * Navigation state for top-level pager/config routing.
 */
data class GatewayNavigationState(
    val pagerPage: Int,
    val configDestination: ConfigSectionDestination
)
