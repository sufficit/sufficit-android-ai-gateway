# PLAN: Teste de stream de voz (VoxCPM remoto + Kokoro on-device) no Android AI Gateway

**Data**: 2026-06-23 00:24
**Status**: Draft (pronto para execução por fases)
**Projeto**: `sufficit-android-ai-gateway` (`com.sufficit.ai.gateway`)
**Sessão**: e3dc057d-a689-4409-b4d8-1c8fdb00ad46
**Referência**: [OpenBMB/VoxCPM](https://github.com/OpenBMB/VoxCPM)
**Substitui/consolida**: `PLAN-202606230004-voxcpm-voice-stream-test.md` (draft anterior)

**Objetivo**: experimentar voz de assistente de melhor qualidade no app, com dois caminhos
complementares — (A) **Kokoro on-device** via sherpa-onnx (caminho rápido, já viável hoje) e
(B) **VoxCPM como serviço remoto de stream** (caminho de qualidade/clonagem de voz). Critérios
de sucesso claros, sem acoplamento prematuro.

---

## 0. TL;DR / Decisão arquitetural

- O ponto de inserção de TTS alternativo é o **backend de fala do assistente**, não o pipeline de
  captura/STT. Hoje a fala usa o `TextToSpeech` nativo do Android
  (`RoomAudioForegroundService.speakAssistantReply`, ~linha 4533; init em ~397 com `Locale("pt","BR")`).
- **Achado-chave desta investigação**: o AAR já embarcado (`app/libs/sherpa_onnx-nnapi-release.aar`,
  declarado em `app/build.gradle.kts`) **já contém as classes de TTS** —
  `OfflineTts`, `OfflineTtsConfig`, `OfflineTtsModelConfig`, `OfflineTtsKokoroModelConfig`,
  `OfflineTtsVitsModelConfig`, `OfflineTtsMatchaModelConfig`, `OfflineTtsKittenModelConfig`,
  `GeneratedAudio`, `GenerationConfig`. Ou seja: **não precisa adicionar dependência nova** para
  rodar Kokoro/VITS on-device. A mesma lib que já faz STT (Whisper local) faz TTS.
- **VoxCPM on-device é inviável hoje**: é um modelo PyTorch/Python (tokenizer + difusão local de
  fala), sem export ONNX oficial nem runtime mobile. Para o app, VoxCPM só faz sentido como
  **serviço HTTP/WebSocket remoto** que devolve áudio (stream PCM/Opus). Isso não muda o app além
  de um "modo de fala remoto".
- **Plano recomendado**: Fase A (Kokoro on-device) primeiro — entrega valor real e testável sem
  servidor; Fase B (VoxCPM remoto) como experimento de qualidade/clonagem depois.

---

## 1. Estado atual relevante (verificado em código)

### 1.1 Fala do assistente (TTS atual)
- `RoomAudioForegroundService.kt`:
  - init TTS: ~linha 397 (`status != TextToSpeech.SUCCESS`), idioma `pt-BR` (~403).
  - `speakAssistantReply(replyText)`: ~linha 4533; `tts.speak(...)` ~4556.
- A resposta do OpenClaw chega como **JSON textual** (`replyText` / `spokenReplyText` /
  `shouldSpeak` / `detailsText` / `actions`) — sem áudio binário. O app sintetiza localmente.

### 1.2 Runtime ONNX já presente
- `app/build.gradle.kts` → `implementation(files("libs/sherpa_onnx-nnapi-release.aar"))`,
  `abiFilters = ["arm64-v8a"]`, NDK 27, minSdk 28, compileSdk 35.
- STT local já usa o mesmo runtime: `transcription/local/LocalSherpaOnnxEngine.kt`
  (`OfflineRecognizer`, Whisper).
- Catálogo de modelos: `config/LocalModelCatalog.kt` — hoje só Whisper STT
  (`sherpa-whisper-base/small/medium/turbo/tiny/tiny.en`), baixados de HuggingFace
  (`csukuangfj/...`) para `filesDir/models/<id>/`, com `requiredFiles` e `isInstalled()`.

### 1.3 API Kokoro do sherpa-onnx (Kotlin, confirmada no exemplo oficial `test_tts.kt`)
```kotlin
val config = OfflineTtsConfig(
  model = OfflineTtsModelConfig(
    kokoro = OfflineTtsKokoroModelConfig(
      model   = ".../model.onnx",
      voices  = ".../voices.bin",
      tokens  = ".../tokens.txt",
      dataDir = ".../espeak-ng-data",
      // multi-lang usa também: lexicon = "lexicon-us-en.txt,lexicon-zh.txt"
    ),
    numThreads = 2,
    debug = true,
  ),
)
val tts = OfflineTts(config = config)
val gen = GenerationConfig(silenceScale = 0.2f)
val audio = tts.generateWithConfigAndCallback(text = "...", config = gen, callback = ::callback)
audio.save(filename = "out.wav")  // ou audio.samples (FloatArray) + sampleRate → AudioTrack
tts.release()
```
- `generateWithConfigAndCallback` entrega áudio por **callback em chunks** → permite **streaming**
  (tocar via `AudioTrack` à medida que gera, sem esperar a frase inteira).
- Modelos Kokoro: ver releases `tts-models` do sherpa-onnx
  (`kokoro-en-v0_19`, `kokoro-multi-lang-v1_0`). Pacote ~300 MB (multi-lang) / menor para en.

---

## 2. Fase A — Kokoro on-device (caminho recomendado, sem servidor)

### A.1 Catálogo de modelo TTS
- Estender `LocalModelCatalog` (ou criar `LocalTtsModelCatalog` análogo) com um bundle Kokoro:
  - `id = "sherpa-kokoro-multi-lang-v1_0"` (ou `kokoro-en-v0_19` para começar menor),
  - `requiredFiles = ["model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/..."]`
    (o `espeak-ng-data` é um diretório — tratar como pasta obrigatória, não 1 arquivo),
  - origem do download: release `tts-models` do sherpa-onnx (tar.bz2), não HF repo simples.
- Reaproveitar o mecanismo de download/instalação já usado para Whisper, adaptando para
  **descompactar um tarball** e validar a pasta `espeak-ng-data`.

### A.2 Engine de TTS local
- Criar `tts/local/LocalKokoroTtsEngine.kt` (espelhando `LocalSherpaOnnxEngine`):
  - `load(bundle, executionMode)` → monta `OfflineTtsConfig` com caminhos em `filesDir/models/<id>/`;
  - `speakStreaming(text, speakerId, speed, onPcmChunk)` → usa
    `generateWithConfigAndCallback` e empurra `FloatArray`→`AudioTrack` (PCM 16-bit, sampleRate
    do `GeneratedAudio`); retornar `false` no callback aborta a geração (barge-in/interrupção).
  - `release()`.

### A.3 Integração no serviço de fala
- Em `speakAssistantReply`: selecionar engine conforme config
  (`settings.ttsEngine = SYSTEM | KOKORO_LOCAL | REMOTE_VOXCPM`).
  - `SYSTEM` → comportamento atual (`TextToSpeech`).
  - `KOKORO_LOCAL` → `LocalKokoroTtsEngine.speakStreaming(...)`.
- **Coordenação obrigatória** (reusar regras já existentes do TTS atual):
  - parar/abortar a fala em **OPEN_HAND** / toque / `interrupt` (já há `interruptAssistant`);
  - **não capturar a própria voz** (gate de eco enquanto fala — mesmo flag do TTS atual);
  - respeitar `configScreenActive` (silenciar agente na tela de config);
  - respeitar `shouldSpeak=false` e o gate de standby.

### A.4 UI (mínima, "modo de teste")
- Nova seção em Config: "Voz do assistente" com:
  - seletor de engine (Sistema / Kokoro local / VoxCPM remoto);
  - botão "Baixar modelo de voz (Kokoro)" + progresso + `isInstalled`;
  - seletor de voz (speakerId) e velocidade;
  - botão "Testar voz" (fala uma frase fixa).
- Tudo gated por download do modelo (como o speaker/whisper já fazem).

### A.5 Critérios de sucesso (Fase A)
- [ ] App baixa e instala o pacote Kokoro (valida `model.onnx`, `voices.bin`, `tokens.txt`,
      `espeak-ng-data/`).
- [ ] "Testar voz" reproduz áudio inteligível em pt/en no A51 (arm64).
- [ ] Streaming: primeiro áudio sai em < ~1.5 s após o texto (não espera a frase inteira).
- [ ] Latência/RTF aceitável no A51 (medir RTF; numThreads ajustável).
- [ ] Barge-in funciona (OPEN_HAND / interrupt aborta a fala no meio).
- [ ] Não há realimentação (o mic não transcreve a própria fala).
- [ ] Fallback automático para `TextToSpeech` se o modelo não estiver instalado.

### A.6 Riscos / mitigação (Fase A)
- **Tamanho do modelo** (multi-lang ~300 MB): começar com `kokoro-en-v0_19` (menor) para validar
  o pipeline; multi-lang depois. Download opcional/sob demanda.
- **`espeak-ng-data` como diretório**: ajustar `isInstalled()` para checar pasta + arquivo-âncora.
- **Qualidade pt-BR**: Kokoro multi-lang cobre en/zh bem; sotaque pt pode ficar via espeak.
  Avaliar; se ruim, manter pt no `TextToSpeech` e usar Kokoro só onde brilha — ou ir para VoxCPM.
- **Contenção de threads** com STT local + MediaPipe: TTS roda em executor próprio; medir CPU.

---

## 3. Fase B — VoxCPM como serviço remoto de stream (qualidade / clonagem de voz)

### B.1 Por que remoto
- VoxCPM (OpenBMB) é PyTorch/Python; sem ONNX/mobile oficial. On-device hoje = inviável.
- Servidor expõe HTTP/WebSocket: recebe `text` (+ opcional `voice_prompt`/`speaker_ref` para
  clonagem) e devolve **áudio em stream** (PCM16 mono ou Opus), idealmente em chunks.

### B.2 Contrato sugerido
- `POST /tts/stream` (ou WS `tts.stream`): body `{ text, voice, sampleRate, format }`;
  resposta: stream de chunks de áudio (chunked transfer / frames WS binários).
- Reaproveitar `OkHttp` (já no app) para consumir o stream e tocar via `AudioTrack`.
- Endpoint/credenciais na config (como `whisperUrl`/token já são).

### B.3 Integração
- `ttsEngine = REMOTE_VOXCPM` → `RemoteVoxCpmTtsClient.speakStreaming(text, onPcmChunk)`.
- Mesmas regras de coordenação da Fase A (barge-in, eco, standby, configScreenActive).
- Timeout + **fallback** para Kokoro local (ou `TextToSpeech`) se o servidor falhar/atrasar.

### B.4 Critérios de sucesso (Fase B)
- [ ] Servidor VoxCPM responde stream de áudio para um texto de teste.
- [ ] App toca o stream incremental (time-to-first-audio < ~2 s em LAN/Tailscale).
- [ ] Clonagem de voz opcional (voice prompt) audível e estável.
- [ ] Fallback transparente quando o servidor cai.

### B.5 Riscos (Fase B)
- Latência de rede + cold start do modelo; precisa stream real, não "gera tudo e envia".
- Custo/infra de GPU no lado servidor (fora do escopo do app).
- Transporte: WS binário é mais simples para barge-in (fechar o socket aborta).

---

## 4. Sequência de execução proposta

1. **A.1–A.2** Catálogo + `LocalKokoroTtsEngine` (download `kokoro-en-v0_19`, load, `speakStreaming`).
2. **A.4** Botão "Testar voz" isolado (valida síntese + AudioTrack streaming, sem mexer no fluxo do agente).
3. **A.3** Plugar no `speakAssistantReply` com seletor de engine + fallback.
4. **A.5** Validar critérios no A51 (RTF, barge-in, eco).
5. **B** Só depois: cliente remoto VoxCPM + endpoint na config.

---

## 5. Observações de implementação (gotchas já mapeados)

- **Sandbox/dados**: modelos vivem em `filesDir/models/<id>/` → sobrevivem a `install -r`, mas
  **somem se o pacote for renomeado** (foi o que apagou enrollment de voz/wake word antes). Avisar na UI.
- **arm64-only**: build já restringe a `arm64-v8a`; modelos/AAR coerentes com isso.
- **NNAPI vs CPU**: o AAR é `-nnapi-`; expor `executionMode` (CPU/NNAPI) como no STT e medir.
- **Não adicionar dependência**: TTS Kokoro usa o AAR já presente — zero mudança em `build.gradle`.
- **Interrupção**: o callback do sherpa retorna `Int`/`Boolean`; retornar 0/false **aborta** a
  geração → usar isso para barge-in em vez de só parar o `AudioTrack`.

---

## 6. Definição de pronto (do plano)

- Este documento salvo em `docs/`. ✅
- Fase A é executável sem servidor e sem novas dependências.
- Fase B documentada como serviço remoto opcional (VoxCPM), com contrato e fallback.
