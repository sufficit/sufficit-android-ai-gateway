# Deteccao de movimento labial para reforcar a verificacao de locutor

> Resolvido em 2026-06-12: FaceMesh no pipeline da camera dos gestos,
> `lipActivityScore`/`lipActivitySamples` no metadata do despacho e
> pre-agente v7 no servidor (voz verificada + labios mexendo = forward;
> voz sem labios com rosto visivel = retencao `voice_without_lip_motion`).

A camera frontal ja roda continuamente para os gestos de mao (MediaPipe).
Adicionar deteccao de rosto/labios para correlacionar **movimento labial**
com a atividade de fala do microfone:

- **Sinal**: enquanto um segmento de fala esta ativo, medir abertura/variacao
  dos labios (MediaPipe Face Landmarker — landmarks da boca, ex.: distancia
  labio superior/inferior normalizada pelo tamanho do rosto, variancia ao
  longo do segmento).
- **Uso**: novo campo no metadata do despacho (`lipActivityScore` ou flag
  `lipsMovingDuringSpeech`) somado ao `speakerVerifiedScore` (embedding
  CAM++) para o pre-agente do servidor:
  - voz verificada + labios mexendo no quadro = confianca maxima (dono
    presente e falando para o aparelho) → encaminhar sem confirmacao;
  - voz verificada mas SEM movimento labial = audio pode ser TV/gravacao da
    voz do dono → manter regras atuais;
  - tambem ajuda a derrubar falsos positivos de eco do TTS.
- **Implementacao**:
  - MediaPipe Face Landmarker (tasks vision) ou o FaceMesh legado no mesmo
    pipeline de camera do `MediaPipeCameraGestureRecognizer` (compartilhar
    frames RGBA ja capturados — nao abrir segunda sessao de camera);
  - publicar em `GatewayRuntime` (mesmo padrao do `gestureCommand`):
    `lipActivity(score, atEpochMs)` renovado por quadro;
  - no `RoomAudioForegroundService`, amostrar durante o segmento ativo e
    agregar (media/max) ao finalizar; anexar em `buildOpenClawMetadata`;
  - servidor: `voice-pre-agent.js` — elevar/derrubar confianca conforme o
    novo campo (constante propria, mesmo padrao do
    `SPEAKER_VERIFIED_FORWARD_THRESHOLD`).
- **Cuidados**: tela apagada = camera parada (sem sinal labial — campo deve
  ser opcional/nullable); multiplas pessoas no quadro = usar o rosto mais
  proximo/maior; custo de CPU do Face Landmarker junto com Hands no A51
  (avaliar alternar frames entre os dois detectores).
