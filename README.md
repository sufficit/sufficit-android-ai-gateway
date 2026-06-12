# Sufficit Android AI Gateway

[![Release APK](https://github.com/sufficit/sufficit-android-ai-gateway/actions/workflows/release.yml/badge.svg)](https://github.com/sufficit/sufficit-android-ai-gateway/actions/workflows/release.yml)

Gateway Android dedicado para captar áudio ambiente de uma sala e encaminhar trechos de fala para a stack de voz da Sufficit/OpenClaw.

> Projeto aberto. Contribuições são bem-vindas — veja [Build](#build-local) e [Contribuindo](#contribuindo).

## Objetivo

Este projeto existe para transformar um celular Android parado em mesa em um endpoint de áudio estável para a sala:

- captura contínua de microfone
- detecção local de voz
- envio de trechos de fala para um gateway local
- integração posterior com Whisper e OpenClaw

## Motivação

O uso de navegador/PWA para microfone contínuo em Android tende a falhar em background por:

- política agressiva de economia de bateria
- limpeza de memória quando a tela fica inativa
- perda de prioridade do browser
- menor controle de wake lock, foreground execution e reconexão

Por isso, a solução adotada aqui é um **app Android nativo** com **Foreground Service**.

## Arquitetura resumida

Fluxo pretendido:

1. Android capta áudio com `AudioRecord`
2. VAD local decide quando existe fala
3. app envia chunk de áudio para um endpoint local
4. gateway local encaminha para Whisper
5. transcrição é entregue ao OpenClaw
6. OpenClaw processa intenção e responde

Topologia preferida:

- Android antigo = sensor de áudio da sala
- Raspberry = gateway local
- Whisper = STT
- OpenClaw = orquestração e resposta

## Documentação inicial

- [Visão de arquitetura](./docs/architecture.md)
- [Roadmap da primeira versão](./docs/roadmap.md)
- [Instalação e teste em Android](./docs/android-testing.md)

## Escopo da primeira versão

Primeira entrega técnica:

- app Android em Kotlin
- captura contínua de microfone
- `ForegroundService`
- VAD local simples
- envio via WebSocket para endpoint configurável
- UI mínima com estado de conexão e captura

Fica fora da primeira versão:

- hotword sofisticada
- wake word offline avançada
- TTS no próprio Android
- configuração remota complexa
- publicação em Play Store

## Build local

Requisitos: JDK 17, Android SDK (API 34+). O `gradlew` baixa o resto.

```bash
# clonar
git clone https://github.com/sufficit/sufficit-android-ai-gateway.git
cd sufficit-android-ai-gateway

# apontar o Android SDK (uma vez)
echo "sdk.dir=/caminho/para/Android/sdk" > local.properties

# build do APK debug
./gradlew :app:assembleDebug
# saída: app/build/outputs/apk/debug/app-debug.apk

# instalar em um device conectado
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Configuração

As credenciais do gateway (host, tokens, sessionKey) **não** ficam no
repositório. Na primeira inicialização o app gera um config básico em branco e
você preenche host e tokens pela própria tela de configuração.

O repositório traz `app/src/main/assets/config.example.json` como referência
dos campos. Se quiser embutir um seed no build, copie-o para
`app/src/main/assets/config.json` (ignorado pelo git) e preencha os valores —
mas **nunca** comite esse arquivo com credenciais reais.

## Releases

Cada tag `v*` dispara o workflow de release e publica o `app-debug.apk` nos
[Releases do GitHub](https://github.com/sufficit/sufficit-android-ai-gateway/releases).

```bash
git tag v0.1.0
git push origin v0.1.0
```

## Contribuindo

1. Fork + branch a partir de `main`.
2. `./gradlew :app:assembleDebug` precisa passar.
3. Não comite credenciais reais — `config.json` fica com placeholders.
4. Abra o PR descrevendo a mudança e como testou no device.

## Estado atual

Projeto iniciado em 2026-03-17. O esqueleto Android já saiu do papel:

- build local via Gradle Wrapper
- APK debug gerado com sucesso
- instalação validada por ADB em um Samsung Galaxy A51 (`SM-A515F`)
- `ForegroundService` implementado com `AudioRecord` real em `16 kHz mono`
- notificação persistente atualizando nível RMS/pico da captura
- configuração local persistida para endpoint e limiar de VAD
- VAD inicial por RMS com estado simples de `fala` versus `silencio`

Próximo foco:

- trocar o VAD heurístico por segmentação útil de áudio
- transporte para Raspberry/OpenClaw
- configuração de endpoint e handshake do dispositivo

## Experimento GPU local

Em `2026-03-17`, o caminho de `whisper.cpp` com `ggml-vulkan` foi validado para o Galaxy A51:

- device expõe `android.hardware.vulkan.compute`
- o backend Vulkan compilou para `arm64-v8a`
- os artefatos nativos foram gerados com sucesso fora do app

Referências:

- [Experimento Whisper local com Vulkan](./docs/on-device-whisper-vulkan.md)
- [Script de build Vulkan para Android](./tools/build-whisper-vulkan-android.ps1)
