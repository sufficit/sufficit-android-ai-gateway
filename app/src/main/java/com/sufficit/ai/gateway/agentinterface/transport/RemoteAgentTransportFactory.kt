package com.sufficit.ai.gateway.agentinterface.transport

enum class RemoteAgentProvider {
    OPENCLAW
}

object RemoteAgentTransportFactory {
    fun create(
        provider: RemoteAgentProvider,
        listener: RemoteAgentTransport.Listener
    ): RemoteAgentTransport = when (provider) {
        RemoteAgentProvider.OPENCLAW -> OpenClawAgentTransport(listener)
    }
}

