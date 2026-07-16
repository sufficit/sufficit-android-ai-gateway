# 02 — Funcionalidades Existentes

Inventário do que **já está implementado e funcionando** hoje (commit `c6161a8`), com
referências de arquivo. No fim, o que está **morto ou quebrado**. Isto corrige o
`roadmap.md`, que marca como pendentes várias coisas prontas.

Legenda: ✅ funcional · ⚠️ funcional com ressalva · ❌ morto/quebrado.

---

## A. Captura e processamento de áudio

- ✅ **Foreground Service com wake lock** — `AudioRecord` 16 kHz mono PCM16 em thread
  dedicada; `PARTIAL_WAKE_LOCK` durante toda captura → escuta com tela apagada
  (`RoomAudioForegroundService.kt:1315`). Echo canceler + noise suppressor de
  plataforma quando disponíveis (`:2148`).
- ✅ **VAD adaptativo** — limiar dinâmico combinando RMS, noise floor com EMA, banda de
  ZCR (0.015–0.24), crest-factor anti-impulso (≤5.8) e pico mínimo de corpo (0.035)
  (`isSpeechLikeFrame`, `:2842`). Limiares recomendados por tier de modelo.
- ✅ **AGC (ganho automático)** — normalização de pico alvo 0.70, tiers de ganho de
  fundo por noise floor, compensação de ambiente, ataque instantâneo + decaimento
  assimétrico (`resolveAutomaticMicrophoneGain`, `:2870`), limitador tanh soft-knee
  (`:3000`).
- ✅ **Classificador de ruído ambiente / música** — score de estabilidade com histerese
  (6 hold / 4 release), e o **quebrador de deadlock música/AGC** (dois scores: um com
  penalidade de fala para detecção, outro sem, só para redução de ganho) — `:779-791`,
  `:2319`.
- ✅ **Pre-roll ring de 1,2 s** — prefixa áudio pré-fala a cada segmento para recuperar
  o início perdido pela latência do VAD; exclui o pre-roll do embedding de locutor
  (`:3025`).
- ✅ **Segmentação com perfis por modo** — hold/maxSegment/minTranscribe/phraseBreak
  distintos para remote/fast/balanced/heavy (`:1975`), com overrides de debug.
- ✅ **Commit de duplo-âncora** — silêncio clássico + quietude pós-transcrição, com
  detecção de fala "inacabada" via caudas de conectores PT-BR que esticam as janelas
  2,6–4× (`:1173`, `:3805`).

## B. Wake word (palavra de ativação) — sem rede neural

- ✅ **MFCC clássico** — 26 filtros mel 80–7600 Hz, FFT 512 radix-2, DCT-II mantendo
  c1..c12 (`MfccExtractor.kt`).
- ✅ **Detector por DTW de subsequência** com distância **cosseno** entre vetores MFCC
  (comentado como 3,6× melhor separação que Euclidiana), start/end livres, mais três
  defesas anti-falso-positivo: gate de span energético contíguo (≥0.6 do template),
  confirmação por 2 janelas consecutivas, e refratário de 3 s (`WakeWordDetector.kt`).
- ✅ **Limiar auto-calibrado** — deriva o threshold da variância DTW entre os próprios
  templates do usuário × 1.6 (`suggestedThreshold`).
- ✅ **Enrolamento por amostras** — grava até 5 amostras PCM de 2,2 s da voz do usuário
  dizendo a palavra (`WakeWordStore.kt`), FIFO. Termos default incluem "xuxu" e
  variantes coloquiais.
- ✅ **Standby com wake screen** — em standby, só a wake word acorda; ela dispara
  full-screen intent para acender a tela (`USE_FULL_SCREEN_INTENT` no manifest).

## C. Verificação de locutor ("Minha voz")

- ✅ **Embeddings CAM++ via sherpa-onnx** — modelo 3D-Speaker zh_en 16k (~28 MB) baixado
  em runtime; extração ~100–300 ms/segmento na `transcriptionExecutor`
  (`SpeakerVerifier.kt`).
- ✅ **Perfil = média L2-normalizada** de até 10 embeddings, FIFO (`SpeakerVoiceStore.kt`).
- ✅ **Gate com zona-cinza adaptativa** — threshold reduzido 0.08 para segmentos <2 s;
  banda cinza de 0.10 onde o trecho é **encaminhado com o score** em vez de rejeitado,
  deixando o servidor fundir com lip-activity/continuidade (`:2368`). **Fail-open** em
  qualquer erro.
- ✅ **Rastreamento de continuidade de locutor** — similaridade ponderada
  (pitch/pitchStd/energia/voicedRatio) com âncora móvel e confiança, emitindo
  `initialized/accepted/held/reset` (`SpeakerContinuityTracker.kt`).
- ✅ **Análise de voz local** — pitch por autocorrelação normalizada, gênero (limiares
  145/185 Hz), emoção (tabela de regras), e sinal de "múltiplas vozes"
  (`LocalVoiceAnalyzer.kt`).

## D. Transcrição

### Remoto ✅
- **`WhisperApiClient`** — cliente OpenAI-compatível (`/v1/audio/transcriptions`) com
  multipart montado à mão sobre `HttpURLConnection`; envia `model`, `language=pt`
  (fixo), `response_format=verbose_json`, `temperature=0`, knobs de VAD/no-speech/
  compression/repetition, `beam_size=5`, prompt opcional, e o WAV. Timeouts 20 s
  conexão / 120 s leitura. **Sem retry.**
- **ElevenLabs Scribe** — provedor alternativo detectado por substring `api.elevenlabs.io`
  na URL; usa `xi-api-key` e `scribe_v1` (`WhisperApiClient.kt:111`).

### Local ⚠️ / ❌
- ✅ **sherpa-onnx (CPU e NNAPI)** — caminho local **funcional**: `OfflineRecognizer`
  com bundles Whisper INT8 (encoder/decoder/tokens), `provider="cpu"|"nnapi"`
  (`LocalSherpaOnnxEngine.kt`). Este é o motor local que realmente roda.
- ❌ **whisper.cpp via JNI (CPU) e Vulkan (GPU)** — **QUEBRADO**. Os símbolos JNI são
  `Java_com_sufficit_openclaw_gateway_...` mas a classe é `com.sufficit.ai.gateway.
  LocalWhisperLib` → `UnsatisfiedLinkError`. No ramo LOCAL+CPU+modelo-não-bundle vira
  parada fatal do serviço. Toda a infra Vulkan (lib de 38 MB, headers, doc de
  experimento) está presente mas inalcançável. **É o principal bug de "feature morta".**
- ✅ **Seleção de motor** — árvore de decisão por `TranscriptionMode` (REMOTE/LOCAL) e
  `LocalExecutionMode` (CPU/NNAPI) em `:1527`; override que força REMOTE quando o host
  é o default gerenciado. **Sem fallback entre motores**: falha não-HTTP → serviço para.
- ✅ **Catálogo de modelos** — 6 bundles sherpa-onnx (tiny/base/small/medium/turbo/
  tiny.en) baixados de Hugging Face, armazenados em `filesDir/models/<id>/`, validados
  por tamanho (não por checksum). Default `sherpa-whisper-tiny`.

## E. Pipeline de texto pós-STT

- ✅ **Prompt de priming** PT-BR a partir de termos/dicionário do usuário, com instrução
  anti-alucinação de cortesia (`TranscriptTextPipeline.buildPrompt`).
- ✅ **Normalização coloquial** dirigida por asset (`colloquial-normalization-safe.txt`,
  gated por força), dicionário do usuário (regex `\bwrong\b→right`, 3 separadores), e
  supressão de repetição alucinada.
- ✅ **Windowing/dedup** — merge de sufixo-prefixo por sobreposição de palavras, cap
  1200 chars (`TranscriptWindowing.kt`).
- ⚠️ **Duas funções neutralizadas** — `sanitizeImplausibleShortTranscript` e
  `discardLikelyHallucinatedCourtesyOnlyTranscript` têm ambos os ramos retornando o
  mesmo valor (no-op silencioso). Ver [03](./03-engenharia.md).

## F. Conversa com o agente (OpenClaw)

- ✅ **WebSocket persistente** (`OpenClawGatewayPersistentConnection`) — handshake
  `hello` com deviceToken/sessionKey/userId/installationId, `heartbeat` a cada 15 s,
  fila de mensagens pendentes, `transcript.final` com metadados ricos.
- ✅ **Envelope de resposta** — parser tolerante com muitos fallbacks de campo
  (`replyText`, `spokenReplyText`, `shouldSpeak`, `tags`, `settingsPatch`, `actions`…).
- ✅ **Ações de agente** — `executeAgentActions` (`:4090`): screenshot, foto,
  wake, effect, say, listen, standby, interrupt, config, clearChat, gesture.
- ✅ **Patch de settings remoto** — o servidor pode reconfigurar o app via
  `settingsPatch` (~38 campos, com clamps).
- ⚠️ **`actions` descartadas no caminho persistente** — `buildGatewayReply` da conexão
  persistente **não** passa `envelope.actions`, então ações entregues pelo stream ativo
  são silenciosamente ignoradas (`OpenClawGatewayPersistentConnection.kt:252`). Ver
  [03](./03-engenharia.md).
- ⚠️ **Sem reconnect/backoff e sem ping/pong** — queda de conexão só atualiza texto de
  status; recuperação é preguiçosa (só na próxima fala).

## G. Síntese de voz (TTS)

- ✅ **TTS Android pt-BR** com seleção de voz por estilo (Sistema/Feminina/Masculina),
  rate e pitch configuráveis; supressão de mic durante a fala + 1,5 s de graça;
  wake screen por 25 s para permitir interrupção por gesto.

## H. Visão e gestos (câmera frontal)

- ✅ **Pipeline CameraX + MediaPipe** (Hands + FaceMesh, GPU com fallback CPU)
  (`MediaPipeCameraGestureRecognizer.kt`, 1.058 linhas).
- ✅ **4 gestos** com classificação geométrica sobre 21 landmarks:
  - Mão aberta calma → **interrompe a fala do assistente** (debounce 350 ms).
  - Indicador levantado/apontando → "vou falar": abre gate, `markDirectAddress`,
    pede atenção de tela, começa a ouvir; enquanto segurado, não fecha por silêncio.
  - Punho fechado → `markDirectAddress` + `finalizeSegment` (envia já).
  - Punho segurado 5 s → para de ouvir (standby se wake word ativa), com countdown.
- ✅ **Verificação de "liveness" por lábios** — FaceMesh mede variação de abertura labial
  (1 a cada 2 quadros, só durante fala) e a correlaciona com o áudio: voz + lábios
  mexendo = pessoa; voz sem lábios = TV/gravação/eco de TTS. Score vai nos metadados.
- ✅ **Overlays visuais** — luva cartoon/holograma suavizada sobre a mão, countdown
  estilo fighting-game nos últimos 3 s do punho, footer colorido por gesto, efeito de
  flash para screenshots.
- ✅ **Captura de foto** — front/back via `ImageCapture` com EXIF correto.

## I. Chat e histórico

- ✅ **Chat estilo WhatsApp** (`GatewayChatUi.kt`) — bolhas usuário/assistente/sistema,
  bolha de transcrição provisória, bolha de "processando" animada, cards de mídia
  (screenshot/foto/documento) com decode+EXIF, barra de entrada dual-mode (espectro ao
  vivo quando ouvindo / texto quando parado).
- ✅ **Persistência de chat** — `filesDir/chat_history.json`, cap de 200 mensagens em
  memória, reescrita completa a cada append.
- ✅ **Loggers de histórico** — transcrição (CSV), continuidade de locutor (JSONL),
  diagnósticos de espectro (JSONL). ⚠️ os JSONL **não têm rotação** (ver
  [03](./03-engenharia.md) e [04](./04-seguranca.md)).

## J. API HTTP de controle embarcada

- ✅ **Servidor NanoHTTPD dentro do serviço** (`GatewayApiServer.kt`), desligado por
  padrão, recusa iniciar sem token, comparação de token em tempo (quase) constante.
- ✅ **Endpoints**: `/api/health` (sem auth), `/api/status`, `/api/config` (GET+PATCH),
  `/api/chat`, `/api/transcripts`, `/api/chat/clear`, `/api/listen/start|stop`,
  `/api/standby`, `/api/wake`, `/api/say`, `/api/conversation`, `/api/interrupt`,
  `/api/gesture`, `/api/finalize`, `/api/screenshot` (PNG), `/api/effect`, `/api/photo`.
- ⚠️ **Padrões inseguros** — bind em `0.0.0.0` por default, HTTP cleartext, CORS `*`.
  Ver [04-seguranca](./04-seguranca.md).

## K. Configuração e identidade

- ✅ **Config JSON única** com patch/merge, export/import por FileProvider, migração de
  SharedPreferences legado.
- ✅ **Identidade** — `installationId` (UUID estável por instalação), `sessionKey`
  canonicalizado (`manufacturer:model:android_id`), `userId` para resolução de pessoa
  no servidor.
- ✅ **Guia de dispositivo** — catálogo (`DeviceModelGuideCatalog`) com recomendações
  por Build.MANUFACTURER/MODEL a partir de asset.
- ✅ **UI de configuração completa** — seções Geral, OpenClaw, Transcrição, Voz do
  Assistente, Tela, Histórico, Depuração, Dicionário (inventário completo de settings
  em [03](./03-engenharia.md) e no relatório de config).

---

## L. Resumo do que está MORTO ou QUEBRADO

| Item | Status | Onde |
|------|--------|------|
| whisper.cpp/Vulkan local (JNI) | ❌ `UnsatisfiedLinkError`, pode matar serviço | `whisper_jni.cpp:42+` (pacote `openclaw` vs `ai`) |
| `actions` no reply WebSocket persistente | ❌ descartadas | `OpenClawGatewayPersistentConnection.kt:252` |
| `sanitizeImplausibleShortTranscript` | ❌ no-op (ambos ramos iguais) | `TranscriptTextPipeline.kt:262` |
| `discardLikelyHallucinatedCourtesyOnlyTranscript` | ❌ no-op | `TranscriptTextPipeline.kt:327` |
| `VoiceChannelSkill.routedText/shouldDispatch` | ❌ ignorados, envia frase original | `RoomAudioForegroundService.kt:3718` |
| `gatewayToken` no handshake OpenClaw | ❌ exigido mas nunca transmitido | `OpenClawGatewayClient.kt:20` |
| Dependência `net.i2p.crypto:eddsa` | ❌ declarada, zero uso (handshake Ed25519 planejado?) | `app/build.gradle.kts` |
| Pipeline de texto legado no serviço (com mojibake UTF-8) | ❌ dead code duplicado | `RoomAudioForegroundService.kt:3073-3404` |
| `GatewayViewModel` draft-state, `GatewayNavigationState`, `GestureGatePhase` FSM | ❌ dead code | `state/*` |
| `computeSha256` de modelos | ⚠️ calculado só para exibição, nunca verificado | `GatewayModelDownloadSupport.kt:208` |
| `assistantLeakBaselineRms` | ❌ acumulado, nunca consumido | `RoomAudioForegroundService.kt:409` |
| `runLocalSelfTestIfNeeded`, `downloadModel` (no serviço) | ❌ dead code | `RoomAudioForegroundService.kt` |
