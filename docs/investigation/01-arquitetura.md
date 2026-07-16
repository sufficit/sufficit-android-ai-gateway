# 01 — Arquitetura

## 1. Visão de camadas

O app é **single-Activity, single-Service**, sem biblioteca de navegação e sem
Fragments. Toda a lógica de runtime vive dentro de um Foreground Service; toda a UI é
Compose. A cola entre eles é um **singleton global de estado** (`GatewayRuntime`), não
um binder nem injeção de dependência.

```
┌──────────────────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose, processo do app)                                 │
│  MainActivity → GatewayScreen (HorizontalPager de 4 páginas)           │
│    página 0: Dashboard/Chat   página 1: Configuração                   │
│    página 2: Dicionário       página 3: Debug de Gestos                │
│  Overlays: HandGloveOverlay, FistCountdownOverlay,                     │
│            GestureCommandFooter, ScreenEffectOverlay                    │
└───────────────▲───────────────────────────────┬──────────────────────┘
                │ collectAsState()               │ chamadas estáticas
                │ (fluxos)                        │ RoomAudioForegroundService.start/stop/...
┌───────────────┴───────────────────────────────▼──────────────────────┐
│  GatewayRuntime (object singleton) — "flow bus + blackboard"           │
│  StateFlows: state(63 campos), chatFlow, handTrackingFlow,             │
│  gestureCommandFlow, lipActivityFlow, screenEffectFlow, ...            │
│  Atomics + persister plugável. Escrito por UI, Service E camada visão. │
└───────────────▲───────────────────────────────┬──────────────────────┘
                │ GatewayRuntime.update{}         │ lê listening/speechDetected
┌───────────────┴───────────────────────────────▼──────────────────────┐
│  RoomAudioForegroundService (5.038 linhas — GOD CLASS)                 │
│  Captura AudioRecord → VAD → AGC → wake word → segmentação →           │
│  gate de locutor → transcrição → pipeline de texto → windowing →       │
│  dispatch OpenClaw (WebSocket) → resposta → TTS → ações de agente      │
│  + API HTTP embarcada (NanoHTTPD) + 4 loggers de histórico             │
└──┬───────────┬────────────┬───────────┬───────────┬──────────┬────────┘
   │           │            │           │           │          │
┌──▼───┐  ┌────▼────┐  ┌────▼─────┐ ┌───▼────┐ ┌────▼───┐ ┌────▼─────┐
│audio/│  │transcri-│  │openclaw/ │ │ api/   │ │vision/ │ │ config/  │
│wake  │  │ption/   │  │WebSocket │ │Nano-   │ │Media-  │ │ persist. │
│speaker│ │local+   │  │client    │ │HTTPD   │ │Pipe    │ │ JSON     │
└──────┘  │remoto   │  └──────────┘ └────────┘ └────────┘ └──────────┘
          └─────────┘
```

## 2. Pacotes (mapa de responsabilidades)

Sob `app/src/main/java/com/sufficit/ai/gateway/`:

| Pacote | Papel |
|--------|-------|
| `audio/` | Captura, VAD, AGC, classificador de ruído ambiente, segmentação, pipeline de texto, windowing. **`RoomAudioForegroundService.kt` é o coração de tudo.** |
| `audio/wake/` | Wake word sem rede neural: `MfccExtractor`, `WakeWordDetector` (DTW), `WakeWordStore` |
| `audio/speaker/` | Verificação de locutor via sherpa-onnx CAM++: `SpeakerVerifier`, `SpeakerVoiceStore` |
| `transcription/` | Cliente Whisper remoto (`WhisperApiClient`) |
| `transcription/local/` | Motores locais: `LocalWhisperEngine` (whisper.cpp/JNI), `LocalSherpaOnnxEngine`, `LocalWhisperLib` |
| `openclaw/` | Cliente WebSocket (`OpenClawGatewayClient` one-shot + `OpenClawGatewayPersistentConnection` runtime) + parser de envelope de resposta |
| `api/` | API HTTP embarcada: `GatewayApiServer` (NanoHTTPD) + `GatewayApiActions` (interface de ações) |
| `vision/` | Câmera + gestos: `MediaPipeCameraGestureRecognizer`, eventos de gesto, frame de tracking |
| `config/` | Modelo de settings, JSON, patch/merge, catálogos de modelos/dispositivos, `InstallationId` |
| `state/` | Estado de UI: `GatewayViewModel` (MVI parcial, só startup/permissões), estados de UI, comandos, eventos |
| `runtime/` | `GatewayRuntime` — o singleton store |
| `history/` | 4 loggers: transcrição (CSV), continuidade de locutor (JSONL), diagnósticos de espectro (JSONL), chat (JSON) |
| raiz | Composables da UI (dashboard, chat, config, overlays de gesto, seções de config) |

Nativo em `app/src/main/cpp/`: `whisper_jni.cpp` (shim JNI ativo), `whisper_jni.c`
(variante morta), `CMakeLists.txt`, headers vendorados de whisper.cpp/ggml.

## 3. Fluxo de dados fim-a-fim (o caminho de uma frase)

Numerado, com o arquivo/linha do ponto de entrada. Detalhe completo em
[02-funcionalidades](./02-funcionalidades-existentes.md).

1. **Captura** — `AudioRecord` a 16 kHz mono PCM16, na `captureExecutor` (thread única),
   loop em `runCaptureLoop` (`RoomAudioForegroundService.kt:606`). Fonte preferida
   MIC→VOICE_RECOGNITION→VOICE_COMMUNICATION. `AcousticEchoCanceler`+`NoiseSuppressor`
   se disponíveis.
2. **VAD por quadro** — `isSpeechLikeFrame` (`:2842`): limiar adaptativo (RMS/noise
   floor/ZCR/crest-factor). Modo remoto afrouxa por fatores fixos.
3. **Classificador de ambiente/música** — score de estabilidade (`:2319`) com histerese;
   quebra deadlock música/AGC usando dois scores separados (`:779-791`).
4. **AGC + soft-clip** — ganho por normalização de pico (alvo 0.70) com ataque
   instantâneo/decaimento assimétrico, limitador tanh no joelho 0.85 (`:2870`, `:3000`).
5. **Pre-roll ring** — 1,2 s de áudio pós-ganho prefixado a cada segmento (`:3025`),
   recupera início de frase perdido pela latência do VAD.
6. **Wake word** — `handleWakeWordAudio` (`:922`) → MFCC → DTW subsequência
   (distância cosseno) → sai do standby / abre gate / acorda tela.
7. **Segmentação** — abre segmento quando quadros-candidato ≥ mínimo; fecha por
   silêncio, gesto de punho, fechamento do gate de câmera ou janela máxima. Perfis por
   modo (`:1975`).
8. **Enfileiramento** — `QueuedTranscriptionTask` na `transcriptionExecutor`
   (`ThreadPoolExecutor(1,1)`, fila ≤3, descarta itens >25 s).
9. **Gate de locutor** — `evaluateSpeakerVoiceGate` (`:2368`): embedding CAM++ vs perfil
   médio, com zona-cinza adaptativa por duração. **Fail-open** em erro.
10. **Transcrição** — decisão remoto/local (`:1527`, ver
    [02](./02-funcionalidades-existentes.md#transcrição)); resultado passa por
    `TranscriptTextPipeline.applyCorrections`.
11. **Windowing** — `TranscriptWindowing` faz dedup/merge de resultados consecutivos e
    decide avançar a janela (frase nova).
12. **Análise de voz** — `LocalVoiceAnalyzer` extrai pitch (autocorrelação),
    gênero, emoção, sinal de múltiplas vozes; `SpeakerContinuityTracker` rastreia
    "mesmo locutor".
13. **Commit** — máquina de estados de duplo-âncora (silêncio clássico + quietude
    pós-transcrição), com detecção de fala "inacabada" PT-BR (`:1173-1230`).
14. **Dispatch** — janela de acumulação (coalescência via `Thread.sleep` +
    contador de geração), monta metadados (gênero/emoção/probabilidade de mesmo
    locutor/score de locutor/lip-activity/histórico/catálogo de ferramentas) e envia por
    WebSocket (`:3536`, `:3703`).
15. **Resposta** — callback do WebSocket → `handleOpenClawReply` (`:3978`):
    aplica patch de settings, mostra bolha no chat, fala via TTS, executa ações de
    agente (foto/screenshot/wake/say/config/…).

Entradas alternativas que pulam o áudio: `ACTION_SEND_TEXT` (chat digitado) e
`/api/conversation` (injeção via HTTP) → dispatch imediato.

## 4. Modelo de threads e concorrência

Não há coroutines no pipeline de áudio — tudo é `java.util.concurrent` + `@Volatile` +
3 locks. Threads vivas:

| Thread | Dono de |
|--------|---------|
| **Main** | `onStartCommand`, `onDestroy`, TTS `onInit`, `Handler.postDelayed` |
| **captureExecutor** (única) | `runCaptureLoop`; dono exclusivo do pre-roll ring, wake detector, espectros, noise floor |
| **transcriptionExecutor** (`ThreadPoolExecutor(1,1)`) | gate de locutor, STT, pipeline de texto, continuidade, commit-por-punho |
| **executor descartável** por transcrição local | timeout de 180 s (`transcribeLocalWithTimeout`) |
| **openClawExecutor** (única) | handshake, sleep de acumulação, dispatch |
| **transcriptClearScheduler** (scheduled, 2 s) | limpeza de transcrição |
| **thread de callback do TTS** | muta `assistantSpeaking`, supressão de mic |
| **thread de callback do WebSocket** | `handleOpenClawReply`, updates, ações |
| **`Thread{}` ad-hoc** | assar EXIF de foto |

Comunicação **Serviço → UI** é exclusivamente via StateFlows do `GatewayRuntime` (sem
binder/LiveData/broadcast). **UI → Serviço** é por métodos estáticos
(`RoomAudioForegroundService.start/stop/sendText/...`). **Serviço → Activity** usa
`MainActivity.requestWakeScreen` + `WeakReference` estáticas (`MainActivity.current`
para screenshot da janela; `MediaPipeCameraGestureRecognizer.active` para foto).

Riscos de corrida/leak documentados em detalhe em
[03-engenharia](./03-engenharia.md#concorrência). Os mais relevantes:
- **R1** — use-after-close no `SpeakerVerifier` no teardown (risco de SIGSEGV nativo).
- **R6** — timeout de STT local não interrompe o cálculo nativo (thread + CPU vazam).
- **R8** — settings recarregados do disco a cada iteração do loop de captura (~2-4×/s).

## 5. Persistência (fonte única de verdade)

Um único arquivo JSON: `filesDir/config.json`
(`/data/data/com.sufficit.ai.gateway/files/config.json`), envelope
`{schema:"openclaw-android-settings", version:1, updatedAtUtc, settings:{…}}`.
Semente de defaults vem de `assets/config.json` (se presente) → `bootstrapFallback()`
(`GatewayConfigCatalog.kt:69`). Um único **pipeline de patch**
(`GatewaySettingsPatch.applyWebSocketSettingsPatch`) atende **três escritores**: a UI,
a API HTTP (`/api/config`) e o **agente remoto** (via `settingsPatch` na resposta
OpenClaw). Ver [04-seguranca](./04-seguranca.md) sobre as implicações de o servidor
remoto poder reescrever qualquer configuração.

## 6. Superfície nativa (JNI/GPU)

`CMakeLists.txt` compila **apenas** o shim `whisper_jni.cpp`; as 5 libs
whisper.cpp/ggml (incluindo `libggml-vulkan.so`, 38 MB) são **prebuilts importados** de
`jniLibs/arm64-v8a/`. O motor local via sherpa-onnx vem do AAR em `app/libs/`.

> **Defeito arquitetural crítico:** os símbolos exportados no JNI são
> `Java_com_sufficit_openclaw_gateway_..._LocalWhisperLib_*`, mas a classe Kotlin é
> `com.sufficit.ai.gateway.transcription.local.LocalWhisperLib`. O rename de pacote
> `openclaw → ai` nunca chegou ao C++. Consequência: **todo o caminho whisper.cpp +
> Vulkan está morto** (`UnsatisfiedLinkError` na primeira chamada), e no ramo
> LOCAL+CPU+modelo-não-bundle isso vira parada fatal do serviço. Detalhe em
> [03-engenharia](./03-engenharia.md) e [02](./02-funcionalidades-existentes.md).
