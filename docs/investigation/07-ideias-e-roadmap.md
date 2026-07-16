# 07 — Ideias e Roadmap

Novas ideias de produto e engenharia, priorizadas. Divididas em: (0) higiene urgente,
(A) evolução de produto, (B) evolução de engenharia, (C) apostas de longo prazo. Cada
ideia diz **por que** e **onde encostar** no código.

---

## 0. Higiene urgente (fazer antes de qualquer feature nova)

Consolidado de [04](./04-seguranca.md), [03](./03-engenharia.md), [05](./05-melhoria-continua.md).

1. **Rotacionar os 3 tokens de produção** e parar de semear credenciais por
   `assets/config.json`. Substituir por **pareamento no primeiro boot** (device mostra um
   código/QR; o OpenClaw emite token de device com escopo). Mata [04](./04-seguranca.md) C1.
2. **Corrigir o mismatch JNI** — renomear símbolos para `Java_com_sufficit_ai_gateway_...`
   em `whisper_jni.cpp`, apagar `whisper_jni.c`, rebuildar. Ressuscita o caminho local
   whisper.cpp/Vulkan e remove o crash de disponibilidade.
3. **Gatear a gravação de áudio rolling** por `settings.development`
   (`RoomAudioForegroundService.kt:866`). Privacidade.
4. **Rotação de logs** nos 3 loggers JSONL/CSV sem cap. Estanca ~100 MB/dia.
5. **Assinatura de release estável** + **CI de PR** + **primeira suíte de testes**
   unitários dos objetos puros (pega as funções neutralizadas de [03](./03-engenharia.md#2)).
6. **API**: default `apiBindAllInterfaces=false`, checagem de Origin/Host, tirar segredos
   de `GET /api/config`.

---

## A. Evolução de produto

### A1. TTS on-device (Kokoro) — já 90% viável
O `PLAN-202606230024` descobriu que o AAR sherpa-onnx **já contém** `OfflineTts`/Kokoro/
VITS/Matcha — TTS neural on-device com **zero nova dependência**. Substituir/complementar
o TTS Android por Kokoro dá voz consistente e independente do device, com barge-in via
callback. **Encostar**: `speakAssistantReply` (~`:4533`). Alto valor, custo baixo.

### A2. Diarização e multi-locutor real
Hoje há sinal de "múltiplas vozes" (`LocalVoiceAnalyzer`) mas a continuidade só congela
no overlap. Evoluir para **diarização leve** (clustering de embeddings CAM++ já
extraídos) → rotular turnos por pessoa ("Hugo:", "visitante:") nos metadados e no chat.
Aproveita infra existente de embeddings. Diferencia numa sala com várias pessoas.

### A3. Wake words múltiplas / comandos diretos offline
O detector DTW já suporta múltiplos templates. Expandir para **mini-comandos offline**
("parar", "silêncio", "foto") reconhecidos localmente sem ir ao servidor — latência zero
e funciona sem rede. **Encostar**: `WakeWordDetector` + roteamento em `handleWakeWordAudio`.

### A4. Modo privacidade / push-to-talk visível
Dado que é um mic sempre-ligado numa sala, um **indicador físico de escuta** (LED de tela,
ícone persistente já existe) + um **modo "só com gesto/wake"** explícito aumentam
confiança e reduzem superfície de privacidade. Vender a discrição como feature.

### A5. Fila offline e reconexão robusta
Hoje não há reconnect/backoff nem ping/pong ([03](./03-engenharia.md#3)/rede). Adicionar
**reconexão com backoff exponencial + jitter**, `pingInterval` no OkHttp, e **fila
persistente** de transcrições não entregues (sobrevive a queda de rede/reboot). Crítico
para um device de sala que fica sozinho.

### A6. Painel web de controle
Já existe a API HTTP. Servir um **mini-dashboard** (HTML estático pelo NanoHTTPD) para
configurar/monitorar o device pelo navegador de outro aparelho na LAN — depois de fechar
os buracos de segurança de [04](./04-seguranca.md). Transforma a API num produto.

### A7. Perfis de sala / cenas
Presets de configuração por ambiente (cozinha barulhenta vs escritório silencioso) que
trocam VAD/AGC/segmentação de uma vez, em vez de ~30 sliders. Aproveita o pipeline de
patch único. Reduz a fricção de setup que hoje é enorme.

### A8. Confirmação visual de comandos sensíveis
Ações de agente destrutivas/sensíveis (foto, config) poderiam exigir **confirmação por
gesto** (já há a gramática de gestos) antes de executar — casando o multimodal com
segurança.

## B. Evolução de engenharia

### B1. Quebrar a god class (guiado por teste)
`RoomAudioForegroundService.kt` (5.038 linhas) → extrair colaboradores com contrato
claro, atrás de testes:
- `AudioCaptureLoop` (captura + VAD + AGC + pre-roll)
- `SegmentationController` (segmentação + commit de duplo-âncora)
- `TranscriptionOrchestrator` (seleção de motor + fila + timeout + fallback)
- `OpenClawDispatcher` (acumulação + metadados + envio)
- `AgentActionExecutor` (ações de resposta)
- `ApiServerController` (ciclo de vida do NanoHTTPD)
O serviço vira um **coordenador fino**. Meta: nenhum arquivo > 400 linhas (a própria
guideline).

### B2. Unificar o modelo de estado
Colapsar as ≥5 representações paralelas de settings ([03](./03-engenharia.md#5)) numa
fonte única (o `GatewaySettings` + um único `Saver` gerado). Eliminar a duplicata morta
no ViewModel. Substituir os savers indexados por 37 posições (frágeis) por
serialização por nome. Reduz de ~8 arquivos para ~2 o custo de um setting novo.

### B3. Introduzir fallback entre motores de STT
Hoje falha não-HTTP mata o serviço. Cadeia: remoto → (falha) → local sherpa → (falha) →
enfileira e tenta depois. Nunca `stopSelf()` por erro transitório
([03](./03-engenharia.md#10)). Trocar o string-matching de exceção por tipos.

### B4. Corrigir os no-ops e ligar o `VoiceChannelSkill`
Restaurar a intenção de `sanitizeImplausibleShortTranscript` e
`discardLikelyHallucinatedCourtesyOnlyTranscript`, e **consumir** `routedText`/
`shouldDispatch` do `VoiceChannelSkill` (hoje envia a frase original com o wake term).
Cada um é um bug de qualidade de transcrição silencioso.

### B5. Consertar o bug de `actions` no stream
Passar `envelope.actions` em `OpenClawGatewayPersistentConnection.buildGatewayReply`
(`:252`). Sem isso, ferramentas de agente pelo canal vivo não funcionam.

### B6. Reprodutibilidade nativa
Receita de build Dockerizada para os `.so` (commit pinado), manifest de versões+checksums,
binários grandes em Git LFS ou baixados em build. Ver [05](./05-melhoria-continua.md#4).

### B7. Corrigir os leaks
Encerrar o executor do `ChatHistoryStore`; esperar transcrições em voo antes de
`speakerVerifier.close()` (R1); tornar `lastTranscriptCommittedAtEpochMs` volatile;
não recarregar settings do disco por chunk (R8).

### B8. NNAPI/GPU de verdade
Depois do B6, validar o caminho Vulkan ressuscitado e medir CPU vs NNAPI vs Vulkan por
tier de modelo no A51 e em pelo menos mais um device. Publicar um guia de "qual modelo/EP
por classe de aparelho" (o `DeviceModelGuideCatalog` já existe para isso).

## C. Apostas de longo prazo

### C1. Endpoint de voz genérico (desacoplar do OpenClaw)
Abstrair o `OpenClawGatewayClient` atrás de uma interface de "agente" para plugar
qualquer backend (LLM local, outro orquestrador). Amplia o mercado além da stack
Sufficit e casa com a memória de projeto de "agentes vendor-neutral".

### C2. VoxCPM / TTS expressivo remoto (streaming)
O `PLAN-202606230024` esboça VoxCPM como serviço remoto de stream (on-device inviável,
só PyTorch). Voz expressiva/clonada para o assistente. Depende de A1 como base local.

### C3. Barramento multi-device de sala
Vários celulares aposentados como array de microfones da mesma sala, com o servidor
fundindo os fluxos (beamforming/seleção do melhor canal). O `installationId` +
`sessionKey` já modelam identidade de device — a base está lá.

### C4. Ação local sem round-trip
Comandos determinísticos ("acender/apagar", "que horas são") resolvidos no device (ou num
Raspberry gateway local) sem ir ao LLM — latência e resiliência. Casa com A3.

### C5. On-device wake-to-intent
Combinar o wake word DTW com um classificador de intenção minúsculo on-device para rotear
sem transcrição completa quando a rede cair.

---

## Matriz de priorização (impacto × esforço)

| Ideia | Impacto | Esforço | Quando |
|-------|---------|---------|--------|
| 0.1 rotacionar segredos + pareamento | alto | baixo | **agora** |
| 0.2 corrigir JNI | alto | baixo | **agora** |
| 0.3 gate áudio rolling | alto | trivial | **agora** |
| 0.4 rotação de logs | alto | baixo | **agora** |
| 0.5 assinatura release + CI PR + testes puros | alto | médio | **agora** |
| B4 no-ops + VoiceChannelSkill | alto (qualidade) | baixo | curto |
| B5 actions no stream | alto | trivial | curto |
| A1 Kokoro TTS on-device | alto | baixo | curto |
| A5 reconexão + fila offline | alto | médio | curto |
| B1 quebrar god class | médio (dívida) | alto | médio |
| B2 unificar estado | médio | médio | médio |
| A2 diarização | médio (diferencial) | médio | médio |
| A6 painel web | médio | médio | médio (pós-segurança) |
| B8 GPU/NNAPI validado | médio | médio | médio |
| C1 agente vendor-neutral | alto (estratégico) | alto | longo |
| C3 array multi-device | alto (visão) | alto | longo |

## Sequência recomendada em uma frase

Estancar segurança e disponibilidade (0.1–0.4) → rede de segurança de CI/testes (0.5) →
consertar os bugs silenciosos baratos (B4/B5) e entregar Kokoro (A1) → resiliência de
rede (A5) → então pagar a dívida estrutural (B1/B2) e perseguir os diferenciais
(A2/diarização, C1/vendor-neutral).
