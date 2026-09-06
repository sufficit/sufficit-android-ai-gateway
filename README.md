# Sufficit Android AI Gateway

> **Worktrees (padrão Sufficit):** toda árvore de trabalho deste projeto (humanos ou agentes de IA) deve ser criada dentro da pasta do próprio projeto: `git worktree add .worktrees/<nome>`. A pasta `.worktrees/` é ignorada pelo git (`.gitignore` → `**/.worktrees/`) e nunca deve ser versionada ou criada fora da raiz do repositório.


[![Release APK](https://github.com/sufficit/sufficit-android-ai-gateway/actions/workflows/release.yml/badge.svg)](https://github.com/sufficit/sufficit-android-ai-gateway/actions/workflows/release.yml)

Interface Android multimodal entre uma pessoa e um agente de inteligência artificial remoto, como OpenClaw ou Hermes Agent.

O aplicativo captura voz, texto e gestos, preserva o contexto da interação, anuncia capacidades do aparelho e apresenta a resposta em uma experiência móvel curta, audível, visível e interrompível. O raciocínio, o planejamento, a memória cognitiva e a autonomia pertencem ao agente remoto.

> Projeto aberto. Contribuições são bem-vindas — veja [Build](#build-local) e [Contribuindo](#contribuindo).

## Objetivo

Este projeto transforma um celular Android em uma interface permanente de sala para conversar com um agente remoto:

- recebe voz, texto e gestos de uma pessoa;
- usa wake word, VAD, segmentação e transcrição para formar turnos humanos;
- envia ao agente remoto o texto e os sinais necessários para interpretar a interação;
- informa que a resposta será consumida em uma tela pequena e, muitas vezes, por TTS;
- mostra fila, processamento, ação, sucesso, retenção e falha;
- executa somente capacidades locais explicitamente anunciadas como ferramentas cliente;
- devolve resultados e evidências ao agente remoto.

## Responsabilidade do produto

O Gateway é um **orquestrador de interação e adapter de plataforma**, não um segundo agente.

Pertencem ao Gateway:

- áudio, wake word, transcrição, gestos, câmera e UI;
- agrupamento e transporte dos turnos;
- adaptação da resposta para chat/TTS/anexos;
- catálogo e execução segura de ferramentas do aparelho;
- autenticação e transporte de MCP quando o aparelho é o cliente;
- feedback visual, cancelamento e auditoria local.

Pertencem ao agente remoto:

- interpretação, raciocínio e planejamento;
- memória cognitiva e contexto de longo prazo;
- skills, subagentes, automações e ferramentas remotas;
- decisão sobre quais ferramentas usar;
- produção da resposta final.

Não devem ser introduzidos no APK um planner cognitivo geral, runtime de subagentes, shell irrestrito, skills autoexecutáveis ou uma memória paralela à Sufficit.

A decisão completa, o contrato de mensagens e o prompt base independente de OpenClaw/Hermes estão em [Fronteira do produto e contrato de interface com agentes](./docs/product-boundary-and-agent-interface.md).

## Motivação

O uso de navegador/PWA para microfone contínuo em Android tende a falhar em background por:

- política agressiva de economia de bateria
- limpeza de memória quando a tela fica inativa
- perda de prioridade do browser
- menor controle de wake lock, foreground execution e reconexão

Por isso, a solução adotada aqui é um **app Android nativo** com **Foreground Service**.

## Arquitetura resumida

Fluxo principal:

1. Android capta áudio com `AudioRecord`
2. wake word/VAD/gestos delimitam a interação
3. STT local, companion ou remoto produz a transcrição
4. o app consolida pausas humanas em um turno
5. o Gateway envia texto, contexto de interação e capacidades cliente
6. OpenClaw, Hermes ou outro agente compatível interpreta e responde
7. o app separa texto curto, fala, detalhes, anexos e ações
8. ferramentas cliente são executadas no aparelho e seus resultados voltam ao agente

Fronteira preferida:

- Android = interface humana, sensores, apresentação e capacidades do cliente
- STT/TTS/MCP = serviços especializados intercambiáveis
- OpenClaw/Hermes = agente, memória, raciocínio, autonomia e resposta

O contrato entre Android e agente é a fronteira estável: nenhum dos lados deve depender da implementação interna do outro.

## API HTTP de controle

Servidor HTTP embarcado controla **todas** as funções e configurações por
comandos HTTP — inclusive participar da conversa (injetar turnos do usuário e
ouvir a resposta do agente). Desligado por padrão; exige token; opção de bind
em LAN. Habilite em Configuração → "API HTTP de controle".

```bash
curl -X POST http://<ip>:8765/api/conversation \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"text": "que horas são?", "speak": true}'
```

Referência completa: [docs/http-control-api.md](./docs/http-control-api.md).

## Documentação inicial

- [Fronteira do produto e contrato de interface com agentes](./docs/product-boundary-and-agent-interface.md)
- [Plano de implementação inspirado no Hermes Agent](./docs/hermes-inspired-gateway-implementation-plan.md)
- [API HTTP de controle](./docs/http-control-api.md)
- [Visão de arquitetura](./docs/architecture.md)
- [Roadmap da primeira versão](./docs/roadmap.md)
- [Instalação e teste em Android](./docs/android-testing.md)

## Princípios da interface com o agente

- Respostas devem ser curtas por padrão e começar pelo resultado.
- Conteúdo falado deve ser pronunciável; URLs, JSON, tabelas e logs não vão para o TTS.
- Conteúdo longo deve preferir documento, link ou anexo com um resumo curto no chat.
- `awakened=true` significa que a sessão foi iniciada pela wake word e está dirigida ao agente até parada explícita.
- O agente só pode acionar ferramentas anunciadas pelo aparelho.
- O histórico deve distinguir ação despachada de resultado confirmado.
- Mensagens internas de compactação, manutenção e memória não são respostas e nunca devem ser faladas.
- A mensagem do usuário nunca deve permanecer sem estado de atividade, resposta ou falha visível.

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

Projeto iniciado em 2026-03-17 e validado principalmente em um Samsung Galaxy A51 (`SM-A515F`). A árvore atual já inclui:

- `ForegroundService` e captura PCM 16 kHz mono com tela apagada;
- ganho automático, VAD, segmentação e wake words treináveis;
- transcrição remota, local e por companion AIDL;
- agrupamento de trechos por pausa humana;
- WebSocket persistente e metadados de interação para OpenClaw;
- chat persistente com áudio, atividade, auditoria, timeout e falha;
- TTS com interrupção por toque/gesto;
- câmera frontal, gestos e atividade labial;
- ferramentas cliente para câmera, tela, áudio, configuração e Wake-on-LAN;
- descoberta MCP de tools, prompts e resources;
- contrato canônico de turno/resposta independente do agente e adapter OpenClaw;
- registro único de capacidades nativas e MCP, com validação, timeout e cancelamento;
- ledger Room/SQLite por `turnId` e `callId`, recuperação após reinício e bloqueio de duplicatas;
- anexos canônicos persistidos e relatório `doctor` JSON sanitizado;
- autenticação OAuth e memória Sufficit por MCP;
- API HTTP local opcional.

O contrato, o transporte, o registro de capacidades e o ledger já foram extraídos. O trabalho de manutenção continua reduzindo o tamanho do `RoomAudioForegroundService`, sem transferir raciocínio ou autonomia do agente remoto para o APK.

## Experimento GPU local

Em `2026-03-17`, o caminho de `whisper.cpp` com `ggml-vulkan` foi validado para o Galaxy A51:

- device expõe `android.hardware.vulkan.compute`
- o backend Vulkan compilou para `arm64-v8a`
- os artefatos nativos foram gerados com sucesso fora do app

Referências:

- [Experimento Whisper local com Vulkan](./docs/on-device-whisper-vulkan.md)
