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
 *    tela acesa). Enquanto o indicador PERMANECER levantado/apontado a
 *    gravacao NAO e finalizada por silencio; ao abaixar o dedo, o fluxo
 *    normal de deteccao de silencio volta a valer.
 *
 * 3. [FistClosed] — mao fechada (punho): "terminei de falar". Finaliza o
 *    segmento de fala em andamento na hora e envia para processamento
 *    (transcricao), sem esperar o tempo de silencio.
 *
 * 4. [FistHeldStop] — punho fechado MANTIDO por 5 segundos: "pare de ouvir".
 *    Equivale ao botao de parar o microfone: com palavra de ativacao
 *    configurada o servico entra em espera (standby), senao para de vez.
 *    Dispara uma unica vez por pose; e preciso soltar o punho e fechar de
 *    novo para repetir. Nota: o punho curto (FistClosed) ja disparou no
 *    inicio da pose — a sequencia "enviar e depois parar" e intencional.
 *
 * Cada gesto reconhecido tambem acende uma linha colorida no rodape da tela
 * (ver GestureCommandFooter): laranja = calma, verde = gravar, azul = enviar.
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

    /** Punho fechado: terminei, envie para processamento. */
    data object FistClosed : CameraGestureEvent {
        override val debugLabel: String = "Punho fechado"
    }

    /** Punho mantido por 5s: pare de ouvir (standby/parada do microfone). */
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
