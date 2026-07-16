# Investigação Completa — Sufficit Android AI Gateway

> Documentação de investigação profunda gerada em **2026-07-12** sobre o estado do
> repositório no commit `c6161a8` (tag mais recente `v0.1.0`). Escrita para servir
> de base a qualquer engenheiro — ou a outra inteligência artificial, mesmo de menor
> capacidade — que precise entender, manter ou evoluir este software sem ter o código
> inteiro em contexto. Todos os apontamentos citam `arquivo:linha` sempre que possível.

## Como este dossiê foi produzido

O código-fonte (~22,5 mil linhas de Kotlin + JNI em C/C++) foi varrido por seis
análises paralelas independentes, uma por subsistema: pipeline de áudio,
transcrição, rede/API embarcada, UI/estado/visão, configuração/persistência, e
documentação/CI/propriedade intelectual. Os achados foram consolidados nos
documentos abaixo.

## Índice

| # | Documento | Do que trata |
|---|-----------|--------------|
| 00 | [Visão geral e objetivos](./00-visao-geral-e-objetivos.md) | O que é o software, por que existe, para que serve, público-alvo, estado de maturidade |
| 01 | [Arquitetura](./01-arquitetura.md) | Componentes, pacotes, fluxo de dados fim-a-fim, modelo de threads, diagramas textuais |
| 02 | [Funcionalidades existentes](./02-funcionalidades-existentes.md) | Inventário do que **já funciona hoje**, com referências de arquivo, mais o que está morto/quebrado |
| 03 | [Engenharia](./03-engenharia.md) | Qualidade de código, dívida técnica, god class, duplicação, dead code, bugs de lógica |
| 04 | [Segurança](./04-seguranca.md) | Modelo de ameaças, achados com severidade, segredos, superfície de rede, privacidade |
| 05 | [Melhoria contínua](./05-melhoria-continua.md) | CI/CD, testes, observabilidade, reprodutibilidade de build, processo |
| 06 | [Propriedade intelectual](./06-propriedade-intelectual.md) | Licença própria, terceiros vendorados, obrigações de atribuição, ativos de PI da Sufficit |
| 07 | [Ideias e roadmap](./07-ideias-e-roadmap.md) | Novas ideias de produto e engenharia, priorização, próximos passos |

## Sumário executivo (leia isto primeiro)

**O que é:** app Android nativo que transforma um celular parado em mesa num sensor
de áudio de sala com IA — capta voz ambiente, decide quem está falando, transcreve
(local ou remoto), conversa com um agente (OpenClaw) e responde por voz. Inclui
controle por gestos de mão via câmera e uma API HTTP embarcada para automação.

**Estado:** protótipo avançado, funcional em campo num único aparelho (Galaxy A51).
Muito mais ambicioso do que o README sugere: o roadmap público lista como pendente
coisas que **já estão implementadas** (WebSocket, TTS, gestos, verificação de
locutor, Whisper local). Versão `0.1.0`, `versionCode 1`.

**Maiores forças:** vários algoritmos próprios genuinamente valiosos — wake word sem
rede neural (DTW sobre MFCC com distância cosseno e limiar auto-calibrado),
verificação de locutor com zona-cinza adaptativa, fusão áudio-visual de "liveness"
(voz + lábios mexendo = pessoa real, não TV), quebrador de deadlock música/AGC, e
commit de fala com duplo-âncora que entende frases "inacabadas" em português.

**Maiores riscos (detalhe em 03/04/06):**
- **Crítico** — credenciais de produção reais embarcadas em `app/src/main/assets/config.json`; ignoradas pelo git, mas **empacotadas em todo APK** e extraíveis por qualquer um.
- **Crítico** — caminho local whisper.cpp/Vulkan está **morto**: mismatch de nome de pacote no JNI (`openclaw` vs `ai`) causa `UnsatisfiedLinkError` e pode derrubar o serviço.
- **Alto** — API HTTP embarcada com padrão de bind em toda a LAN (`0.0.0.0`), em texto puro (sem TLS), CORS `*`, e endpoints de screenshot/foto da câmera.
- **Alto** — `RoomAudioForegroundService.kt` é uma god class de 5.038 linhas com ≥15 responsabilidades.
- **Alto** — zero testes automatizados no repositório inteiro.
- **Médio** — várias funções de lógica "neutralizadas" (ambos os ramos retornam o mesmo valor) → bugs silenciosos; logs em disco sem rotação (~100 MB/dia).

**Compatibilidade de licenças:** limpa (tudo MIT/Apache-2.0/BSD-3/CC0 sob projeto
MIT). O problema de PI é **falta de atribuição** (nenhum NOTICE/terceiros) e
binários nativos sem proveniência/checksum — tudo curável.
