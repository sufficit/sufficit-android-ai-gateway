package com.sufficit.ai.gateway.vision

/**
 * Gestos de comando reconhecidos pela camera frontal.
 *
 * Contrato de comportamento (definido pelo usuario):
 *
 * 1. [OpenHandCalm] — mao aberta (sinal de "calma"): interrompe IMEDIATAMENTE
 *    o audio de resposta do assistente, se ele estiver falando. Nao mexe na
 *    gravacao do microfone.
 *
 * 2. [IndexRaised] — dedo indicador levantado (indicando "1") OU apontando
 *    para a tela: "vou falar". Abre a gravacao de audio (gate do microfone +
 *    tela acesa) somente quando a escuta ambiente de nivel 2 esta parada.
 *    Enquanto ela ja estiver ativa, o reconhecedor ignora esta pose.
 *
 * 3. [FistClosed] — mao fechada (punho): interrompe a deteccao de voz na
 *    hora. Equivale ao botao de parar o microfone: com palavra de ativacao
 *    configurada, o servico entra em espera; caso contrario, para de vez.
 *
 * 4. [FistHeldStop] — compatibilidade para clientes antigos que ainda
 *    aguardam o punho mantido. O punho curto ja interrompe a escuta.
 *
 * Cada gesto reconhecido tambem acende uma linha colorida no rodape da tela
 * (ver GestureCommandFooter): laranja = calma, verde = retomar a escuta,
 * azul = parar a escuta.
 */
sealed interface CameraGestureEvent {
    val debugLabel: String

    /** Mao aberta: pare de falar agora. */
    data object OpenHandCalm : CameraGestureEvent {
        override val debugLabel: String = "Mao aberta (calma)"
    }

    /** Indicador levantado: vou falar, abra o microfone. */
    data object IndexRaised : CameraGestureEvent {
        override val debugLabel: String = "Indicador levantado"
    }

    /** Punho fechado: interrompa a escuta/deteccao de voz. */
    data object FistClosed : CameraGestureEvent {
        override val debugLabel: String = "Punho fechado"
    }

    /** Compatibilidade: punho mantido tambem para a escuta. */
    data object FistHeldStop : CameraGestureEvent {
        override val debugLabel: String = "Punho mantido (parar escuta)"
    }
}

/**
 * Identificadores estaveis dos gestos para fluxo continuo (estado "gesto
 * ativo agora"), usados pelo servico de audio e pela barra do rodape.
 */
object GestureCommandIds {
    const val OPEN_HAND = "open_hand"
    const val INDEX_UP = "index_up"
    const val FIST = "fist"

    /**
     * Tempo com o punho MANTIDO fechado para parar a escuta. Contrato fixo
     * de 5s (independente de configuracao): compartilhado entre o
     * reconhecedor (dispara FistHeldStop) e o overlay de contagem regressiva
     * da UI (3..2..1 nos ultimos 3s) — os dois precisam do MESMO relogio.
     */
    const val FIST_HOLD_STOP_MS = 5_000L

    /** Janela final do hold em que a contagem regressiva aparece na tela. */
    const val FIST_COUNTDOWN_WINDOW_MS = 3_000L
}

/**
 * Politica unica dos comandos gestuais por estado da interface.
 *
 * O nivel 1 (wake word local) nao aparece como [listening]: nele o indicador
 * ainda pode retomar o nivel 2. Com [listening] ativo, liberar a fala seria
 * redundante. Fora do Chat ou durante digitacao, nenhum comando de camera
 * deve atravessar.
 */
object GestureCommandPolicy {
    fun filter(
        gestureId: String?,
        listening: Boolean,
        textInputModeActive: Boolean,
        interactionActive: Boolean
    ): String? = when {
        !interactionActive -> null
        textInputModeActive -> null
        gestureId == GestureCommandIds.FIST && !listening -> null
        gestureId == GestureCommandIds.INDEX_UP && listening -> null
        else -> gestureId
    }
}
