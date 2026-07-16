# 03 — Engenharia

Qualidade de código, dívida técnica, concorrência e bugs. Ordenado por impacto.
Cada item cita `arquivo:linha`.

---

## 1. God class

`audio/RoomAudioForegroundService.kt` — **5.038 linhas**, ≥15 responsabilidades:
captura, VAD, AGC, classificador de ambiente, pre-roll, segmentação, wake word, gate
de locutor, enrolamento, orquestração de transcrição, pipeline de texto, windowing,
dispatch WebSocket + resposta + ações de agente, TTS + barge-in, 2 canais de
notificação, wake screen, ciclo de vida da API HTTP, patch de settings, fusão de
lip-activity, 4 loggers, download de modelo, EXIF de foto.

O restante do pacote `audio/` é bem fatorado (objetos puros pequenos e testáveis); este
arquivo absorveu todo o resíduo. É o alvo #1 de refatoração. Sugestão de quebra em
[07-ideias](./07-ideias-e-roadmap.md).

Outros arquivos acima do limite de 400 linhas (que a própria
`docs/engineering-guidelines.md` estabelece):
`MediaPipeCameraGestureRecognizer.kt` (1.058), `config/GatewaySettings.kt` (768),
`MainActivity.kt` (708), `GatewayChatUi.kt` (668), `config/GatewaySettingsPatch.kt`
(603), `HandGloveOverlay.kt` (555), `runtime/GatewayRuntime.kt` (457),
`audio/TranscriptTextPipeline.kt` (432), `openclaw/OpenClawGatewayClient.kt` (402).

## 2. Bugs de lógica neutralizada (silenciosos — prioridade alta)

Funções onde **ambos os ramos retornam o mesmo valor**, anulando a intenção sem erro
visível. Testes unitários triviais teriam pego:

- `TranscriptTextPipeline.sanitizeImplausibleShortTranscript` (`:262-287`): calcula
  `hasHyphenatedUnknown` e faz `if (hasHyphenatedUnknown) return text` — o outro ramo
  também retorna `text`. O callback `onDiscardedShortTranscript` nunca dispara. A cópia
  morta no serviço (`:3296`) retornava `""` — a intenção real de descartar foi perdida.
- `TranscriptTextPipeline.discardLikelyHallucinatedCourtesyOnlyTranscript` (`:327-358`):
  `if (normalized in courtesyPatterns) return trimmed` e o não-match também retorna
  `trimmed`. Descarte de transcrição só-cortesia é no-op.
- `VoiceChannelSkill` — `decision.shouldDispatch` nunca é consultado e
  `decision.routedText` (texto com wake term removido) é descartado;
  `dispatchTranscriptToOpenClaw` sempre envia a `phrase` original
  (`RoomAudioForegroundService.kt:3718`). `stripWakeTerm` é efetivamente morto.

## 3. Bug funcional: ações do agente perdidas no stream

`OpenClawGatewayPersistentConnection.buildGatewayReply` (`:252-268`) constrói o reply
**sem** `actions = envelope.actions` → default `emptyList()`. Esse é o caminho vivo
(`onReply` → `handleOpenClawReply` → `executeAgentActions`). Resultado: comandos de
ferramenta do agente (screenshot/photo/wake/say/config…) entregues pela conexão
persistente são **silenciosamente descartados**. A versão one-shot passa `actions`
corretamente, mas não é o caminho de streaming. Correção de alto valor, baixo custo.

## 4. Defeito crítico no JNI (feature morta cara)

`whisper_jni.cpp` exporta `Java_com_sufficit_openclaw_gateway_..._LocalWhisperLib_*`
(`:42,83,101,157,165,175,183,190`), mas a classe é
`com.sufficit.ai.gateway.transcription.local.LocalWhisperLib`. Rename de pacote
`openclaw → ai` nunca propagou ao C/C++ (evidência: `assets/config.json:22` ainda cita
`com.sufficit.openclaw.gateway`). Primeira chamada a qualquer `external fun` →
`UnsatisfiedLinkError`. Em `MainActivity.kt:243` sobrevive via `runCatching`; no serviço
vira `ExecutionException` → parada fatal. Todo o investimento em Vulkan (lib de 38 MB,
22 headers vendorados, doc de experimento) está inalcançável.

Correção: renomear os símbolos para `Java_com_sufficit_ai_gateway_...` e rebuildar. Ver
[07](./07-ideias-e-roadmap.md).

## 5. Duplicação de estado (custo de manutenção)

O shape de settings (~37 campos) existe em **≥5 formas paralelas**; adicionar um
setting toca ~8 arquivos:
- `GatewaySettingsState.kt:13-235` (ctor + 37 `mutableStateOf` + `toSnapshot` +
  `applyFrom` + `listSaver` indexado por 37 posições);
- `state/GatewaySettingsDraftState.kt` + `...Saver.kt` — **duplicata morta** na camada
  ViewModel;
- `GatewaySettingsInputSnapshot` (`GatewayModelSupport.kt:127`);
- `ConfigPageState` + `currentConfigPageState` de 56 parâmetros (`GatewayConfigPage.kt`);
- `ConfigPageActions` — 38 callbacks (`GatewayConfigPage.kt:65`) fiados por
  `GatewayConfigPageHost.kt`.

Ambos os savers indexados restauram `null` se `values.size != 37` — **adicionar um campo
descarta silenciosamente o estado salvo** (`GatewaySettingsState.kt:192`).

Duplicação também no mapeamento JSON seção↔flat (`GatewaySettingsJson.kt` vs
`GatewaySettingsPatch.kt`), com clamps já divergentes: `voiceChannelIdlePromptSeconds`
30..1800 no patch vs 30..3600 na UI; `screenHoldSeconds` 1..30 vs 1..120.

## 6. Dead code (limpeza)

- Pipeline de texto legado inteiro duplicado no serviço (`:3073-3404`), inclusive com
  **mojibake UTF-8** (`"mÃºsica"`, `"Ã¡Ã Ã¢Ã£"`) — acidente de encoding congelado. As
  cópias vivas (`TranscriptTextPipeline`/`TranscriptWindowing`) estão corretas.
- `GatewayViewModel`: `settingsDraftState`, `permissionState()`, `cameraCaptureState()`,
  `whisperAuthState()`, `gestureGateState()`, e 6 setters de pending — zero call sites.
- `GatewaySettingsDraftState`+Saver, `GatewayNavigationState`,
  `GestureGatePhase.GESTURE_MATCHED/ERROR` — mortos.
- `SpeakerContinuityTracker.update`/`estimateSameSpeakerProbability` — API sem chamador.
- `whisper_jni.c` — variante antiga não compilada (só o `.cpp` está no CMake); diverge
  do vivo (sem guardas de exceção, perfil Vulkan diferente). Convida a editar o arquivo
  errado.
- Constantes mortas: bloco `AMBIENT_*`, `MIN_SPEECH_*`, `OPENCLAW_UNCERTAIN_PREFIX`,
  `OPENCLAW_REASONING_HOLD_MS`. Dependência `net.i2p.crypto:eddsa` inteira.

## 7. Concorrência — corridas e leaks {#concorrência}

| # | Risco | Local | Impacto |
|---|-------|-------|---------|
| R1 | **use-after-close** no `SpeakerVerifier`: `close()`→`extractor.release()` no main thread enquanto `embed()` roda na transcriptionExecutor; `shutdownNow()` vem depois e não espera | `RoomAudioForegroundService.kt:365-368` | SIGSEGV nativo no teardown |
| R2 | TOCTOU em `phraseCommitPending`/`phraseAdvanceReady` (`@Volatile` escritos por 2 threads, check-then-act não atômico) | `:1092,1222,1372,1712` | double-commit ou commit perdido |
| R3 | `lastTranscriptCommittedAtEpochMs` é `var` simples (não volatile), escrito por 2 threads e lido pelo scheduler | `:198,3493,2102` | visibilidade/word-tearing em 32-bit |
| R5 | `restartApiServer` posta stop+start com 250 ms de delay no main Handler; se `onDestroy` roda no meio, o servidor HTTP ressuscita após a morte do serviço | `:4303` | socket vazado |
| R6 | timeout de STT local (`shutdownNow`) não interrompe `whisper_full`/onnxruntime; thread + CPU/GPU seguem até o fim (180 s) e um segundo segmento pode rodar transcrição nativa concorrente | `:1788-1809` | thread/CPU leak, corrida nativa |
| R7 | acumulação como `Thread.sleep(window)` no único `openClawExecutor`: N frases serializam N sleeps, e bloqueiam handshake/dispatch atrás | `:3572` | latência acumulada |
| R8 | settings recarregados do disco **a cada iteração** do loop de captura (~2-4×/s) enquanto outros campos só no início do loop → hot-reload inconsistente | `:726` vs `:611` | I/O e semântica inconsistente |
| R9 | exceção no loop de captura sai do loop mas o serviço fica foreground com notificação velha | `:1294` | "meia-morte" silenciosa |
| — | executor do `ChatHistoryStore` **nunca é encerrado**; novo criado por `onCreate` do serviço, antigo pinado por `GatewayRuntime.chatPersister` | `ChatHistoryStore` / `:209` | 1 thread vazada por restart |
| — | `OpenHandInterruptDebounce` singleton com Handler do main-looper; interrupção pendente não é cancelada por `stop()`/`close()` do recognizer | `GatewayCameraGestureSupport.kt:121` | `interruptAssistant` pode disparar após câmera parar |

## 8. I/O na main thread (jank)

- `settingsStore.load()` dentro de `remember{}` na 1ª composição (`MainActivity.kt:182`).
- `TranscriptHistoryLogger.snapshot()` em `produceState` **relido a cada 2 s para sempre**
  no dispatcher main, contando todas as linhas do CSV (`MainActivity.kt:252`).
- Autosave debounced faz `buildSettings()` (que relê arquivos de config) + `save()` no
  main (`MainActivity.kt:317`).
- `BitmapFactory.decodeFile` + EXIF dentro de `remember{}` por card de mídia
  (`GatewayChatUi.kt:315`).
- `produceState` tickando `System.currentTimeMillis()` a cada 500 ms **incondicional**,
  recompondo `GatewayScreen` inteiro 2×/s pela vida do app, só para avaliar um timestamp
  (`MainActivity.kt:229`).

## 9. Crescimento de disco sem limite (operacional — alta severidade)

- `SpectrumDiagnosticsLogger` — append a cada 400 ms de captura, **sem cap/prune/rotação
  em lugar nenhum** → ~0,5 KB × 2,5/s ≈ **100+ MB/dia** de escuta contínua
  (`SpectrumDiagnosticsLogger.kt:44`, chamado em `:978`).
- `SpeakerContinuityHistoryLogger` — sem cap **e** relê o arquivo inteiro
  (`readLines().asReversed()`) a cada dispatch OpenClaw → latência cresce linearmente
  (`SpeakerContinuityHistoryLogger.kt:72`).
- `TranscriptHistoryLogger` CSV — sem cap (só `clear()` manual).
- Fotos/screenshots do agente em `getExternalFilesDir(Pictures)` — **nunca limpos**.

Contraste positivo: `AudioDebugStore` faz certo (prune por mtime de 5 min, caps de
tamanho).

## 10. Tratamento de erro frágil

- `handleFatalError` (`:1811`) mata o Foreground Service inteiro para qualquer exceção
  de transcrição local que não "pareça" HTTP-4xx/5xx — e a classificação é por
  **string matching** (`msg.contains("HTTP 4")`, `:1768`). Um único OOM/erro nativo/blip
  de rede/DNS derruba a escuta sempre-ligada permanentemente.
- `WakeWordStore.loadConfig`/`SpeakerVoiceStore.loadConfig` engolem JSON corrompido para
  defaults sem telemetria.
- `GatewayConfigCatalog.loadRuntime` engole erro de parse com `getOrNull()` → config
  corrompido cai silenciosamente para migração de legado (agora vazia) → **reset de
  fábrica silencioso de todos os settings, inclusive tokens**; e `saveRuntime` não é
  atômico (`writeText` direto), então crash no meio corrompe o único store.

## 11. Trabalho computado e jogado fora

- `segmentLooksLikeSpeech` — re-scan de quadros + análise de pitch a custo cheio só para
  uma linha de log (`:1447`).
- `computeSha256` de modelos — só exibição, integridade real é por igualdade de tamanho.

## 12. Acoplamento

`GatewayRuntime` é importado e mutado por **toda** camada — composables, MainActivity,
suporte de gesto, a **camada de visão** (o recognizer lê
`GatewayRuntime.state().value.listening/speechDetected` e escreve 8 setters), e a god
class. Sem interface, sem injeção → intestável sem o grafo inteiro. Composables chamam
`RoomAudioForegroundService.*` estáticos diretamente (dependência dura UI↔serviço nos
dois sentidos). Seções de config instanciam o próprio `GatewaySettingsStore` em vez do
`settingsState` compartilhado → duas fontes de verdade para o mesmo arquivo.

## 13. Pontos fortes de engenharia (o que está bem feito)

Para equilíbrio — o projeto **não** é ruim; é um protótipo ambicioso com dívida
localizada. Bem feito:
- Objetos de áudio puros e pequenos (`TranscriptWindowing`, `MfccExtractor`,
  `SpeakerContinuityTracker`, `LocalVoiceAnalyzer`) — coesos, testáveis, single-owner
  documentado.
- `AudioDebugStore` — black-box de campo com auto-prune e one-liner de exfil por adb
  documentado; excelente para disputas de qualidade de STT.
- Contratos de threading documentados em KDoc nos objetos single-thread.
- Pipeline de patch único servindo UI/HTTP/agente — design coerente de fonte única.
- Fallback GPU→CPU no MediaPipe; guardas anti-NaN no overlay de mão.
- Auto-calibração e defesas anti-falso-positivo do wake word (engenharia de sinal séria).
- Disciplina de log de atividades em `docs/` (rastro de decisão rico).
