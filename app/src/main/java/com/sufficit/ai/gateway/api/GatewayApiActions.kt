package com.sufficit.ai.gateway.api

import com.sufficit.ai.gateway.config.GatewaySettings
import com.sufficit.ai.gateway.config.GatewaySettingsPatchResult
import com.sufficit.ai.gateway.network.WakeOnLanDiscoveryResult
import com.sufficit.ai.gateway.network.WakeOnLanKnownDevice
import com.sufficit.ai.gateway.network.WakeOnLanResult
import com.sufficit.ai.gateway.network.WakeOnLanVerificationResult
import org.json.JSONObject

/**
 * Superficie de comandos do gateway, compartilhada entre a UI, os gestos e a
 * API HTTP. O servico de audio (RoomAudioForegroundService) e a unica
 * implementacao — centralizar aqui garante que qualquer caminho (toque na
 * tela, gesto da camera ou comando HTTP) execute exatamente a mesma acao.
 *
 * Metodos imperativos (sem retorno) sao "fire-and-forget": o efeito aparece
 * nos flows do GatewayRuntime, que a API le para montar respostas de estado.
 */
interface GatewayApiActions {

    /** Configuracao efetiva atual (persistida + defaults). */
    fun currentSettings(): GatewaySettings

    /**
     * Aplica um patch de configuracao (mesmo formato seccionado do
     * config.json ou chaves planas) e persiste. Retorna o resultado com as
     * chaves aplicadas/ignoradas e os efeitos colaterais necessarios
     * (restart de captura, reconexao, refresh de TTS, restart da API).
     */
    fun applyConfigPatch(patch: JSONObject): GatewaySettingsPatchResult

    /** Inicia/retoma a captura de audio. */
    fun startListening()

    /** Para a escuta (entra em standby se houver palavra de ativacao). */
    fun stopListening()

    /** Forca o modo de espera (so palavra de ativacao reabre). */
    fun standby()

    /** Sai do standby e acorda a tela. */
    fun wake()

    /** Fala um texto arbitrario pelo TTS do assistente. */
    fun say(text: String)

    /**
     * Participa da conversa: injeta o texto como um turno do usuario
     * (enderecamento direto) e despacha para o agente. A resposta chega
     * assincrona nos flows de chat/estado. Quando speak=true a resposta
     * tambem e falada (comportamento normal do assistente).
     */
    fun injectConversation(text: String, speak: Boolean)

    /** Interrompe a fala do assistente em andamento. */
    fun interruptAssistant()

    /** Dispara um gesto de comando como se viesse da camera. */
    fun triggerGesture(gestureId: String)

    /** Finaliza imediatamente o segmento de fala em captura e envia. */
    fun finalizeSegment()

    /** Limpa o historico de conversa exibido. */
    fun clearChat()

    /**
     * Captura a tela do app (com efeito visual de flash + som). Retorna os
     * bytes PNG, ou null se nao houver tela capturavel (app em segundo plano).
     */
    fun screenshot(label: String): ByteArray?

    /** Dispara o efeito visual (flash) + som sem capturar nada. */
    fun playEffect(label: String)

    /**
     * Tira uma FOTO com a camera do aparelho (frontal por padrao, ou traseira)
     * e a publica no chat como se o agente a tivesse enviado: thumbnail
     * clicavel que abre a imagem real. Fire-and-forget — o resultado aparece
     * nos flows de chat. Requer a Activity em primeiro plano (o agente deve
     * acordar a tela antes, ou usar a tool wake).
     */
    fun takePhoto(useBackCamera: Boolean, label: String)

    /** Envia magic packets Wake-on-LAN pela rede local atual do telefone. */
    fun wakeOnLan(
        macAddress: String,
        broadcastAddress: String = "",
        port: Int = 9,
        repeat: Int = 3
    ): WakeOnLanResult

    /**
     * Descobre vizinhos IPv4 atualmente visiveis na LAN do telefone.
     *
     * O resultado identifica MAC/IP ativos pela tabela ARP. A capacidade
     * efetiva de Wake-on-LAN do destino continua dependendo da NIC/BIOS e e
     * retornada como "unknown" ate ser confirmada por um teste real.
     */
    fun discoverWakeOnLanDevices(activeProbe: Boolean = true): WakeOnLanDiscoveryResult

    /** Inventario persistido de MACs aprendidos na LAN. */
    fun wakeOnLanKnownDevices(): List<WakeOnLanKnownDevice>

    /** Nomeia um destino no inventario local e na memoria Sufficit autenticada. */
    fun nameWakeOnLanDevice(macAddress: String, name: String): WakeOnLanKnownDevice

    /** Envia WOL aos alvos conhecidos e monitora presença por até 30 segundos. */
    fun verifyWakeOnLanDevices(
        macAddresses: List<String> = emptyList(),
        waitSeconds: Int = 30
    ): WakeOnLanVerificationResult
}
