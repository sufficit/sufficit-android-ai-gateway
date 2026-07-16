# 00 — Visão Geral e Objetivos

## 1. O que é

**Sufficit Android AI Gateway** é um aplicativo Android nativo (Kotlin + Jetpack
Compose) que converte um celular Android ocioso — tipicamente um aparelho antigo
parado em cima de uma mesa — num **endpoint de áudio inteligente para uma sala**.

O celular fica ligado, ouvindo o ambiente continuamente, decide localmente quando há
fala relevante, transcreve o trecho (no próprio aparelho ou num servidor remoto),
envia o texto para um agente conversacional (a stack **OpenClaw** da Sufficit), recebe
a resposta e a fala de volta pela caixa de som do celular. Tudo isso rodando de forma
resiliente em segundo plano, o que um navegador/PWA não consegue sustentar no Android.

Nome de pacote: `com.sufficit.ai.gateway`. Rótulo do app: "Sufficit AI Gateway".
Versão atual: `0.1.0` (`versionCode 1`).

## 2. Por que existe (motivação)

O README (`README.md:18-27`) documenta a razão: microfone contínuo em background via
navegador falha no Android por política agressiva de bateria, limpeza de memória com
tela inativa, perda de prioridade do processo e controle insuficiente de wake lock e
reconexão. A resposta de engenharia foi um **app nativo com Foreground Service** que
segura `PARTIAL_WAKE_LOCK` durante toda a captura (`RoomAudioForegroundService.kt:1315-1335`),
garantindo que a escuta sobreviva com a tela apagada.

## 3. Para quem / público-alvo

- **Uso interno Sufficit / OpenClaw**: o design pressupõe a stack OpenClaw como
  cérebro. Endereços default apontam para `openclaw.sufficit.com.br` e
  `whisper.sufficit.com.br`.
- **Topologia doméstica/lab pretendida** (`README.md:40-45`): Android antigo = sensor
  de áudio da sala; Raspberry Pi = gateway local; Whisper = STT; OpenClaw =
  orquestração e resposta.
- **Reaproveitamento de hardware**: o alvo explícito de validação é um Samsung Galaxy
  A51 (`SM-A515F`), um aparelho de gama média de 2019 — a proposta de valor é dar
  utilidade a celulares aposentados.

## 4. Objetivos, esclarecidos

O README descreve o escopo original de forma modesta; o código foi muito além. Objetivos
reais, inferidos do que está implementado:

### 4.1 Objetivo primário
Ser um **agente de voz de sala sempre-ligado, mãos-livres**: ouvir, entender quem
fala, conversar com o assistente OpenClaw e responder por voz — sem toque, sem
telefone na mão.

### 4.2 Objetivos secundários já materializados no código
1. **Autonomia de STT**: transcrever tanto remotamente (Whisper/ElevenLabs) quanto
   **no próprio dispositivo** (sherpa-onnx e, na intenção, whisper.cpp com GPU Vulkan),
   para funcionar sem depender de rede.
2. **Discriminação de locutor**: distinguir a voz do dono da casa de vozes de fundo,
   TV e do próprio TTS do app, para não reagir a áudio que não lhe é dirigido.
3. **Interação multimodal**: controle por **gestos de mão** (câmera frontal +
   MediaPipe) para abrir/fechar o microfone, interromper a fala do assistente e
   confirmar comandos sem falar.
4. **Controle e automação externos**: uma **API HTTP embarcada** que expõe *todas* as
   funções (inclusive injetar turnos de conversa, falar, tirar foto, capturar tela)
   para integração com outros sistemas.
5. **Presença física ("liveness")**: correlacionar voz com movimento labial para
   confirmar que há uma pessoa real falando, não uma gravação/TV.

### 4.3 Fora de escopo declarado (README.md:80-86)
Hotword sofisticada, wake word offline avançada, TTS no próprio Android (na verdade
**já implementado** via TTS nativo pt-BR), configuração remota complexa (também **já
implementada** via WebSocket settings patch), e publicação na Play Store.

> **Observação importante:** o README e o `roadmap.md` estão ~3-4 meses defasados do
> código. Vários itens listados como "fora de escopo" ou "pendentes" já existem e
> funcionam. Ver [02-funcionalidades-existentes](./02-funcionalidades-existentes.md).

## 5. Estado de maturidade

**Protótipo avançado / alfa funcional**, validado em campo num único aparelho.

| Dimensão | Estado |
|----------|--------|
| Funcional | Roda de ponta a ponta: captura → VAD → transcrição → OpenClaw → TTS, validado no Galaxy A51 |
| Cobertura de dispositivos | Somente `arm64-v8a`; testado em 1 aparelho físico |
| Testes automatizados | **Zero** (nenhum `src/test` ou `src/androidTest`) |
| CI/CD | 1 workflow que publica APK **debug-assinado** por tag `v*` |
| Documentação | Rica em logs de atividade, mas docs "perenes" (README/roadmap) defasadas |
| Segurança de dados | Praticamente nula em repouso (segredos em texto puro) |
| Versionamento | `versionCode` nunca incrementado apesar de 12 commits de feature após o release inicial |

## 6. Ecossistema e dependências externas

- **OpenClaw** — cérebro conversacional remoto, alcançado por WebSocket
  `wss://<host>/ws/android`. É um serviço/nome de terceiro no ecossistema Sufficit.
- **Whisper server** (`whisper.sufficit.com.br`) — STT remoto compatível com OpenAI
  (`/v1/audio/transcriptions`), presumivelmente faster-whisper.
- **ElevenLabs Scribe** — provedor STT alternativo, detectado por substring de URL.
- **Hugging Face** — origem dos modelos sherpa-onnx baixados em runtime
  (`csukuangfj/sherpa-onnx-whisper-*`).
- **k2-fsa GitHub releases** — modelo de locutor CAM++ (3D-Speaker, ~28 MB).

## 7. Uma frase para memorizar

> Um celular velho vira o ouvido, os olhos e a boca de um agente de IA numa sala —
> ouvindo com discernimento de quem fala, transcrevendo com ou sem internet,
> obedecendo a gestos e conversando por voz, tudo controlável por uma API HTTP.
