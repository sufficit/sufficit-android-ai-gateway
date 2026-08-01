# Plano de implementação do Gateway inspirado no Hermes Agent

## Status

**Tipo:** plano técnico incremental e registro de execução

**Estado:** implementação e smoke test físico concluídos; cenários conversacionais extensos permanecem como regressão operacional

**Atualizado em:** 1º de agosto de 2026

**Hermes Agent analisado:** commit [`fed098bbf0c104edbe232002901c43491b0c6155`](https://github.com/NousResearch/hermes-agent/tree/fed098bbf0c104edbe232002901c43491b0c6155)

**Android AI Gateway usado como baseline:** commit `d279df64a430b7c9a4a30496304a25467ccda7ae`, acrescido das alterações locais existentes na data deste plano

**Decisão canônica do produto:** [Fronteira do produto e contrato de interface com agentes](./product-boundary-and-agent-interface.md)

Este documento substitui a comparação exploratória anterior. A análise do Hermes foi mantida somente onde justifica uma decisão de implementação.

## 1. Objetivo

Evoluir o Sufficit Android AI Gateway para uma interface multimodal mais substituível, observável, segura e testável, aproveitando padrões arquiteturais do Hermes sem transformar o aplicativo em agente.

Ao final do plano:

- OpenClaw continuará sendo o agente remoto padrão;
- um futuro Hermes poderá ser conectado por outro adapter, sem alterar áudio, gestos ou UI;
- o contrato de turno e resposta será independente do agente;
- catálogo e execução de ferramentas cliente usarão uma única fonte;
- toda ação local terminará em sucesso, falha, timeout, cancelamento ou resultado não verificado;
- compactação e outros eventos internos nunca entrarão no chat ou TTS;
- turnos, chamadas e entregas terão correlação e idempotência persistentes;
- MCP continuará sendo uma ponte autenticada, não uma memória cognitiva local;
- `RoomAudioForegroundService` deixará de concentrar protocolo, catálogo, dispatch e apresentação.

### Estado da implementação em 1º de agosto de 2026

| Fase | Estado | Evidência principal |
|---|---|---|
| 0 — baseline | concluída | fixtures sanitizados e testes JVM do contrato/legado |
| 1 — contrato canônico | concluída | `agentinterface/`, codec, política móvel e filtro interno |
| 2 — transporte | concluída | `RemoteAgentTransport`, adapter OpenClaw e mock |
| 3 — capacidades | concluída | catálogo e dispatch derivados do mesmo registro |
| 4 — coordenador | concluída | estados, timeout, cancelamento e WOL verificável |
| 5 — ledger | concluída | Room/SQLite, schema v1, correlação, recuperação e idempotência por `callId` |
| 6 — MCP | concluída | tools, prompts e resources entram como grupo dinâmico do registro |
| 7 — doctor/anexos | concluída | `export_diagnostics` sanitizado e anexos canônicos persistidos/renderizados |
| 8 — legado | concluída no código | atalhos paralelos removidos; aliases de compatibilidade preservados |

Validação automatizada executada: `./gradlew testDebugUnitTest :app:assembleDebug`. Em 1º de agosto
de 2026, o APK foi instalado com preservação de dados (`install -r`) no A51 `SM-A515F`, abriu sem
exceções e criou o schema Room v1. A migração importou 398 correlações históricas sem imprimir o
conteúdo das conversas. O monitor local de wake word e o ganho adaptativo retomaram normalmente.

O Detekt foi executado, mas ainda não é gate: a árvore completa possui 397 ocorrências sem baseline,
distribuídas entre código anterior e novo. `git diff --check` passou. A API HTTP estava desabilitada
na configuração do aparelho, e o refresh do MCP Sufficit registrou erro de rede; por isso os testes
físicos de doctor/MCP e a regressão conversacional completa permanecem na matriz operacional abaixo.

## 2. Fronteira obrigatória

### Pertence ao Gateway

- captura de voz, texto, gestos e câmera;
- wake word, AGC, VAD, segmentação e formação do turno;
- transporte do turno ao agente remoto;
- contexto de superfície móvel e preferências de apresentação;
- catálogo, permissão e execução de ferramentas do aparelho;
- progresso, cancelamento, evidência, auditoria e apresentação;
- autenticação e transporte MCP quando o Android é o cliente;
- adaptação da resposta para chat, TTS, detalhes e anexos.

### Pertence ao OpenClaw, Hermes ou outro agente remoto

- interpretação semântica;
- raciocínio e planejamento;
- memória cognitiva;
- seleção de ferramentas;
- skills e subagentes;
- cron e autonomia;
- produção da resposta final.

### Fora de escopo

- embarcar o runtime Python/Termux do Hermes;
- criar um loop cognitivo Kotlin;
- instalar skills autoexecutáveis no APK;
- criar uma memória geral paralela à Sufficit;
- executar código ou shell enviado pelo agente;
- mover cron, planejamento ou subagentes para o Android.

Qualquer item futuro que atravesse essa fronteira deve ser interrompido e revisado antes da implementação.

## 3. Diagnóstico do baseline anterior à implementação

### 3.1 Pontos fortes que serão preservados

- captura contínua, wake word, ganho adaptativo, VAD e segmentação;
- transcrição local, companion e remota;
- agrupamento por pausa humana;
- metadados mínimos `awakened` e `wakeWord`;
- WebSocket persistente com reconexão;
- bolha operacional que impede mensagem de usuário órfã;
- áudio persistido nas mensagens;
- filtro defensivo inicial de compactação;
- autenticação Sufficit e MCP com tools, prompts e resources;
- Wake-on-LAN com plano abrangente de envio e verificação;
- testes unitários para envelope OpenClaw, conexão, chat, gestos, wake word e WOL.

### 3.2 Acoplamentos atuais

| Ponto | Situação atual | Risco |
|---|---|---|
| `RoomAudioForegroundService.kt` | 6.362 linhas; coordena áudio, transporte, catálogo, dispatch, TTS e chat | mudanças pequenas afetam muitos fluxos |
| `buildAgentToolCatalog()` | catálogo manual dentro do serviço | schema pode divergir do handler |
| `executeAgentActions()` | grande `when` separado do catálogo | ferramenta anunciada pode não executar como descrito |
| `OpenClawReplyEnvelope` | modelo canônico e detalhes OpenClaw misturados | UI e TTS ficam presos ao fornecedor |
| `OpenClawGatewayPersistentConnection` | transporte e payload OpenClaw concretos | troca de agente exige tocar o serviço de áudio |
| `ChatHistoryStore` | histórico inteiro regravado em JSON | correlação e idempotência são frágeis em interrupções |
| `SufficitMcpToolBridge` | catálogo dinâmico paralelo às tools nativas | disponibilidade e resultado não são uniformes |
| estados de ação | handlers escrevem mensagens de modos diferentes | atividade pode desaparecer ou duplicar |

### 3.3 Padrões do Hermes selecionados

| Padrão do Hermes | Aplicação no Gateway |
|---|---|
| platform adapters | interface `RemoteAgentTransport` e adapters por agente |
| central tool registry | `ClientCapabilityRegistry` como fonte de catálogo e execução |
| disponibilidade defensiva | estados estruturados sem desaparecimento silencioso |
| callbacks e interrupção | ciclo uniforme de ações cliente |
| delivery ledger | correlação persistente de turno, chamada e entrega |
| contexto/compactação | classificador local antes de chat e TTS |
| MCP dinâmico | namespace, cache, sanitização e descoberta progressiva |
| doctor | relatório exportável da saúde da interface |

Memória provider, skills, cron, loop do agente e subagentes foram deliberadamente excluídos.

## 4. Arquitetura-alvo

```text
voz/texto/gesto
      │
      ▼
formação do turno humano
      │
      ▼
AgentTurnEnvelope ───────────────┐
      │                          │
      ▼                          │
RemoteAgentTransport             │
  ├── OpenClawAgentTransport     │
  ├── HermesAgentTransport (*)   │
  └── MockAgentTransport         │
      │                          │
      ▼                          │
RemoteAgentEvent                 │
      │                          │
      ├── reply ──► apresentação móvel/TTS
      ├── activity ─► bolha operacional
      ├── internal ─► histórico técnico, nunca chat/TTS
      └── action request
              │
              ▼
      ClientCapabilityRegistry
              │
              ▼
      ClientActionCoordinator
              │
              ├── progresso/cancelamento
              ├── resultado/evidência
              └── retorno ao agente

InteractionLedger correlaciona todos os blocos.

(*) somente adapter remoto; Hermes não será embarcado no APK.
```

### 4.1 Pacotes-alvo

```text
com.sufficit.ai.gateway.agentinterface/
├── AgentProtocolVersion.kt
├── AgentTurnEnvelope.kt
├── AgentReplyEnvelope.kt
├── AgentPresentationHints.kt
├── AgentChannelContext.kt
├── AgentAttachment.kt
├── RemoteAgentEvent.kt
├── RemoteAgentEventClassifier.kt
├── MobilePresentationPolicy.kt
└── InternalEventFilter.kt

com.sufficit.ai.gateway.agentinterface.transport/
├── RemoteAgentTransport.kt
├── RemoteAgentTransportFactory.kt
├── OpenClawAgentTransport.kt
└── MockAgentTransport.kt

com.sufficit.ai.gateway.capabilities/
├── ClientCapabilityRegistry.kt
├── ClientCapabilityDescriptor.kt
├── CapabilityAvailability.kt
├── ClientActionRequest.kt
├── ClientActionState.kt
├── ClientActionResult.kt
├── CapabilityEvidence.kt
├── ClientActionCoordinator.kt
└── adapters/
    ├── GatewayApiActionsAdapter.kt
    ├── WakeOnLanCapability.kt
    └── McpCapabilityAdapter.kt

com.sufficit.ai.gateway.ledger/
├── InteractionLedger.kt
├── InteractionEvent.kt
├── InteractionSnapshot.kt
├── LedgerDatabase.kt
└── migration/

com.sufficit.ai.gateway.diagnostics/
├── GatewayDoctor.kt
├── GatewayHealthProbe.kt
├── GatewayHealthReport.kt
└── probes/
```

## 5. Ordem e dependências

```text
Fase 0 — proteção do comportamento atual
   ├── Fase 1 — contrato canônico e apresentação
   │      └── Fase 2 — transporte independente do agente
   └── Fase 3 — registro central de capacidades
          └── Fase 4 — execução observável e interrompível

Fases 2 + 4 ──► Fase 5 — ledger e idempotência
Fase 3 ───────► Fase 6 — MCP pelo registro central
Fases 1–6 ───► Fase 7 — diagnóstico e compatibilidade Hermes
Fase 7 ──────► Fase 8 — remoção dos caminhos legados
```

Nenhuma fase depende de uma troca imediata do servidor OpenClaw. Cada fase precisa deixar o APK instalável e funcional no A51.

## 6. Plano por fases

### Fase 0 — Baseline e testes de caracterização

**Objetivo:** congelar os comportamentos que não podem regredir antes das extrações.

**Complexidade:** média

#### Entregas

1. Criar fixtures JSON reais e sanitizadas para:
   - turno de texto;
   - turno de voz acordado;
   - resposta normal;
   - resposta com `details`;
   - resposta com ação cliente;
   - compactação interna;
   - erro remoto;
   - resultado de ferramenta.
2. Cobrir o payload atual do OpenClaw:
   - `awakened` e `wakeWord` sobrevivem ao lote;
   - catálogo é enviado;
   - `callId` retorna no resultado;
   - dados sensíveis não aparecem na fixture.
3. Cobrir a garantia visual:
   - toda mensagem de usuário recebe atividade, resposta ou falha;
   - compactação não gera TTS;
   - reinício fecha processamento órfão como falha explícita.
4. Registrar tamanho e responsabilidades do `RoomAudioForegroundService` como baseline de redução.

#### Arquivos previstos

- `app/src/test/resources/agent-protocol/`
- `OpenClawReplyEnvelopeTest.kt`
- `OpenClawGatewayPersistentConnectionTest.kt`
- `GatewayChatRuntimeTest.kt`
- novos testes de payload e integração do serviço extraídos para helpers puros

#### Critérios de aceite

- nenhuma mudança de comportamento em produção;
- fixtures não contêm token, usuário, URL privada, áudio ou MAC real;
- todos os testes atuais e novos passam;
- existe teste explícito para evento interno não falado.

### Fase 1 — Contrato canônico e política de apresentação móvel

**Objetivo:** separar o significado do protocolo dos detalhes do OpenClaw.

**Complexidade:** alta

#### Entregas

1. Criar modelos canônicos:
   - `AgentTurnEnvelope`;
   - `AgentReplyEnvelope`;
   - `AgentPresentationHints`;
   - `AgentAttachment`;
   - `RemoteAgentEvent`.
2. Versionar o contrato com `schemaVersion`.
3. Preservar o contrato mínimo de wake word:
   - `awakened`;
   - `wakeWord`;
   - nenhuma flag paralela com o mesmo significado.
4. Separar resposta em:
   - `text`;
   - `speech`;
   - `details`;
   - `attachments`;
   - `actions`.
5. Criar `MobilePresentationPolicy` para:
   - texto curto por padrão;
   - TTS somente de conteúdo pronunciável;
   - detalhes fora do áudio;
   - anexos para conteúdo longo.
6. Generalizar o filtro atual em `RemoteAgentEventClassifier` e `InternalEventFilter`.
7. Manter `OpenClawReplyEnvelopeParser` como adapter temporário para os modelos canônicos.

#### Compatibilidade

- o parser continua aceitando o envelope legado;
- o app não exige que o servidor envie `schemaVersion` imediatamente;
- resposta em texto puro continua funcionando durante a migração;
- campos desconhecidos são ignorados e registrados somente em debug sanitizado.

#### Testes

- round-trip das fixtures do contrato;
- versão conhecida, ausente e incompatível;
- separação entre `speech` e `details`;
- anexos sem leitura pelo TTS;
- `SYSTEM_INTERNAL`, `CONTEXT_COMPACTION` e `MEMORY_INTERNAL` invisíveis;
- erro remoto convertido em falha visível, não em resposta falada.

#### Critérios de aceite

- UI e TTS recebem somente modelos canônicos;
- classes novas não importam `openclaw`;
- compactação e memória interna não criam bolha normal;
- o prompt de canal descrito no documento canônico tem uma versão identificável.

### Fase 2 — Transporte remoto independente do agente

**Objetivo:** permitir trocar OpenClaw por outro agente sem tocar nos sensores ou na UI.

**Complexidade:** alta

#### Entregas

1. Definir `RemoteAgentTransport` com operações de:
   - conectar;
   - desconectar;
   - enviar turno;
   - enviar resultado de ação cliente;
   - observar conexão, atividade, resposta, ação e erro.
2. Criar `OpenClawAgentTransport` envolvendo `OpenClawGatewayPersistentConnection`.
3. Criar `RemoteAgentTransportFactory` a partir da configuração atual.
4. Criar `MockAgentTransport` para testes instrumentados e demonstração offline.
5. Mover montagem de payload OpenClaw para o adapter.
6. Fazer o serviço depender apenas de `RemoteAgentTransport`.

#### Regras de projeto

- o transporte não renderiza chat;
- o transporte não chama TTS;
- o transporte não escolhe ferramenta;
- reconexão não executa novamente ação não idempotente;
- recebimento e parsing não rodam na thread de áudio.

#### Testes

- conexão e backoff existentes preservados;
- fila durante desconexão;
- reconexão e flush sem duplicar turno;
- troca entre transport real e mock;
- resposta, atividade e ação chegam como `RemoteAgentEvent`;
- `callId` e `turnId` preservados.

#### Critérios de aceite

- `RoomAudioForegroundService` não instancia diretamente a conexão OpenClaw;
- nenhum componente de UI importa pacote `openclaw`;
- OpenClaw continua funcionando sem alteração de servidor;
- um mock completa um turno inteiro no teste sem usar WebSocket.

### Fase 3 — Registro central de capacidades cliente

**Objetivo:** gerar catálogo e execução a partir da mesma definição.

**Complexidade:** alta

#### Entregas

1. Criar `ClientCapabilityDescriptor` contendo:
   - nome canônico;
   - aliases legados;
   - descrição curta;
   - schema de entrada;
   - política de timeout;
   - classificação de sensibilidade;
   - probe de disponibilidade;
   - handler.
2. Criar `ClientCapabilityRegistry`.
3. Gerar `availableTools` pelo registro.
4. Resolver e validar ações pelo mesmo registro.
5. Migrar Wake-on-LAN como capacidade de referência.
6. Migrar as ferramentas nativas restantes em lotes:
   - captura: `photo`, `screenshot`;
   - interação: `wake`, `listen`, `standby`, `interrupt`, `say`, `effect`;
   - configuração: `config`, `clearChat`;
   - inventário: descoberta, verificação e nomeação WOL.
7. Manter aliases durante uma janela de compatibilidade.

#### Modelo de disponibilidade

```text
AVAILABLE
TEMPORARILY_UNAVAILABLE
AUTHENTICATION_REQUIRED
PERMISSION_REQUIRED
UNSUPPORTED_ON_DEVICE
DISABLED_BY_USER
```

Ferramentas indisponíveis permanecem diagnosticáveis. O agente recebe o motivo estruturado em vez de silêncio.

#### Testes

- toda capacidade anunciada possui handler;
- todo handler público possui descriptor;
- argumentos inválidos falham antes do efeito;
- aliases resolvem para o mesmo handler;
- indisponibilidade inclui motivo sem expor segredo;
- WOL gera catálogo e execução pelo mesmo descriptor.

#### Critérios de aceite

- `buildAgentToolCatalog()` é removido do serviço;
- o `when` principal de `executeAgentActions()` deixa de conhecer ferramentas individuais;
- catálogo e dispatch não podem divergir;
- ferramentas existentes continuam aceitando os nomes legados.

### Fase 4 — Execução observável, cancelável e verificável

**Objetivo:** garantir que toda ação cliente possua ciclo de vida e resultado explícitos.

**Complexidade:** alta

#### Entregas

1. Criar `ClientActionCoordinator`.
2. Normalizar estados:

```text
QUEUED
VALIDATING
WAITING_PERMISSION
EXECUTING
VERIFYING
SUCCEEDED
UNVERIFIED
FAILED
TIMED_OUT
CANCELED
DENIED
```

3. Criar `ClientActionResult` com:
   - `callId`;
   - `turnId`;
   - ferramenta;
   - estado final;
   - resumo curto;
   - evidência estruturada;
   - `retryable`;
   - erro sanitizado.
4. Criar cancelamento cooperativo por toque, gesto e comando remoto.
5. Separar:
   - aceito;
   - despachado;
   - confirmado;
   - não verificável.
6. Usar WOL como primeiro fluxo completo:
   - plano de envio;
   - pacotes despachados;
   - espera configurada;
   - presença antes/depois;
   - confirmação ou ausência de resposta.
7. Alimentar a bolha operacional pelo coordenador, não por mensagens soltas nos handlers.

#### Testes

- timeout encerra a bolha e retorna resultado ao agente;
- cancelamento impede etapas posteriores;
- exceção do handler não deixa atividade pendente;
- WOL sem resposta termina como `UNVERIFIED`, não `SUCCEEDED`;
- resultado remoto distingue falha de execução de falha de entrega;
- duas atualizações do mesmo `callId` não criam duas ações.

#### Critérios de aceite

- 100% das ações terminam em estado final;
- mensagem do usuário nunca permanece como última sem atividade/falha;
- sucesso visual só é usado quando há evidência suficiente;
- TTS não lê payload de evidência, JSON ou stack trace.

### Fase 5 — Ledger de interação e idempotência

**Objetivo:** tornar turnos, ações e entregas recuperáveis após reconnect ou reinício.

**Complexidade:** alta

#### Decisão de armazenamento

Usar Room/SQLite para o ledger operacional. `ChatHistoryStore` permanece temporariamente como projeção visual e fonte de migração; ele não será transformado em memória cognitiva.

#### Entidades mínimas

| Entidade | Conteúdo |
|---|---|
| `InteractionTurn` | `turnId`, origem, timestamps, `awakened`, `wakeWord`, estado |
| `InteractionSource` | vínculo ordenado entre turno e IDs das bolhas de usuário |
| `RemoteDelivery` | transporte, tentativa, recibo, estado e timestamps |
| `ClientActionCall` | `callId`, `turnId`, ferramenta, hash de argumentos, estado e resumo |
| `InteractionEvent` | trilha append-only sanitizada para diagnóstico |

#### Entregas

1. Adicionar Room e geração de código em PR isolado.
2. Criar DAOs e índices por `turnId`, `callId`, estado e tempo.
3. Definir transações para mudança de estado.
4. Criar chaves de idempotência:
   - `turnId` para envio ao agente;
   - `callId` para ferramenta;
   - `deliveryId` para retorno.
5. Importar do JSON somente correlações úteis no primeiro boot.
6. Marcar atividades não terminadas como interrompidas após crash/reboot.
7. Aplicar retenção:
   - eventos técnicos limitados por tempo e quantidade;
   - mídia continua com expiração própria;
   - tokens e payloads sensíveis nunca são persistidos.

#### Migração segura

- não apagar `chat_history.json` na primeira versão com Room;
- escrever ledger e histórico em paralelo atrás de feature flag;
- comparar projeções em debug;
- ativar leitura do ledger somente após validação no A51;
- manter rollback capaz de voltar ao JSON sem perder o chat.

#### Testes

- migração de histórico existente;
- reinício durante `PROCESSING`;
- reinício durante `VERIFYING`;
- reconnect não repete ação não idempotente;
- retenção não remove resposta ainda visível;
- dados sensíveis são redigidos antes da persistência.

#### Critérios de aceite

- um turno pode ser rastreado da transcrição até a resposta/TTS;
- uma ação pode ser rastreada do pedido até a evidência final;
- crash não deixa bolha eternamente processando;
- reenvio usa idempotência explícita, não comparação textual heurística.

### Fase 6 — MCP integrado ao registro de capacidades

**Objetivo:** uniformizar ferramentas nativas e MCP sem misturar autenticação ou responsabilidade cognitiva.

**Complexidade:** média/alta

#### Entregas

1. Criar `McpCapabilityAdapter` que produz descriptors para:
   - tools;
   - prompts;
   - resources.
2. Exigir namespace por servidor.
3. Detectar colisões antes de publicar o catálogo.
4. Aplicar disponibilidade estruturada por servidor e item.
5. Criar cache com versão, TTL e invalidação explícita.
6. Validar schema na descoberta e novamente na execução.
7. Sanitizar erros antes do agente, chat e ledger.
8. Manter token limitado ao endpoint configurado.
9. Preservar `userId` derivado da identidade autenticada.
10. Implementar descoberta progressiva:
    - resumo estável por servidor/toolset;
    - schema completo sob demanda ou quando relevante.

#### Regras

- memória recuperada é contexto, nunca mensagem para TTS;
- indisponibilidade MCP não derruba áudio, chat ou tools nativas;
- o Gateway não escolhe qual memória consultar;
- prompts e resources não são convertidos silenciosamente em fala.

#### Testes

- colisão de namespaces;
- token expirado;
- servidor desabilitado;
- catálogo antigo invalidado;
- tool removida entre descoberta e execução;
- erro sanitizado;
- tool MCP percorre o mesmo ciclo de ação das tools nativas.

#### Critérios de aceite

- `SufficitMcpToolBridge` não escreve diretamente no catálogo do serviço;
- tools MCP usam `ClientActionCoordinator`;
- falha MCP é visível e acionável sem bloquear os demais fluxos;
- nenhum token aparece em log, chat, resultado ou ledger.

### Fase 7 — Doctor, contrato Hermes e anexos

**Objetivo:** tornar a interface diagnosticável e provar que o contrato não depende do OpenClaw.

**Complexidade:** média

#### Entregas

1. Criar `GatewayDoctor` com probes para:
   - microfone e captura;
   - wake word;
   - câmera e gestos;
   - TTS;
   - transcrição;
   - transporte remoto;
   - identidade Sufficit;
   - MCP;
   - permissões;
   - bateria/Doze;
   - ferramentas cliente.
2. Gerar relatório sanitizado em JSON e Markdown.
3. Permitir anexar o relatório à conversa de suporte sem falar seu conteúdo.
4. Formalizar `AgentAttachment` e apresentação de documento/link.
5. Criar testes contratuais reutilizáveis para qualquer `RemoteAgentTransport`.
6. Criar fixture/adaptador experimental de Hermes somente quando existir um endpoint remoto definido.
7. Documentar o mapeamento mínimo Hermes:
   - turno recebido;
   - atividade/progresso;
   - action request;
   - action result;
   - reply final;
   - internal event.

#### Critérios de aceite

- relatório não contém token, transcrição privada, áudio ou identificador desnecessário;
- documento longo aparece como anexo com resumo curto;
- OpenClaw e mock passam a mesma suíte de contrato;
- nenhum código Hermes é embarcado;
- adicionar um adapter remoto não exige mudar UI, áudio ou capacidades.

### Fase 8 — Remoção de caminhos legados

**Objetivo:** concluir a migração somente depois dos gates anteriores.

**Complexidade:** média

#### Entregas

1. Remover catálogo e dispatch legados do serviço.
2. Remover apresentação específica do OpenClaw fora do adapter.
3. Encerrar escrita dupla JSON/Room depois do período de validação.
4. Manter importação do histórico antigo por pelo menos uma versão de migração.
5. Remover aliases de tools somente após telemetria mostrar ausência de uso.
6. Atualizar arquitetura, troubleshooting e API.
7. Medir redução final do `RoomAudioForegroundService`.

#### Critérios de aceite

- serviço de áudio coordena captura e ciclo de vida, não protocolo detalhado;
- nenhuma UI importa `openclaw`;
- nenhuma ferramenta individual é conhecida pelo serviço;
- não há dois caminhos ativos para a mesma ação;
- APK atualizado preserva histórico, configuração e autenticação.

## 7. Sequência sugerida de PRs

| PR | Conteúdo | Dependência | Risco |
|---|---|---|---|
| 1 | fixtures e testes de caracterização | nenhuma | baixo |
| 2 | modelos canônicos e codec | PR 1 | médio |
| 3 | filtro de eventos e política móvel | PR 2 | médio |
| 4 | `RemoteAgentTransport` + mock | PR 2 | médio |
| 5 | `OpenClawAgentTransport` | PR 4 | alto |
| 6 | modelos e registro de capacidades | PR 1 | médio |
| 7 | migrar WOL para o registro | PR 6 | médio |
| 8 | migrar tools nativas restantes | PR 7 | alto |
| 9 | coordenador de ações e cancelamento | PR 8 | alto |
| 10 | resultados/evidências uniformes | PR 9 | médio |
| 11 | Room e schema do ledger | PRs 5 e 10 | médio |
| 12 | idempotência, recuperação e migração | PR 11 | alto |
| 13 | MCP pelo registro central | PRs 8 e 10 | alto |
| 14 | doctor e relatório exportável | PRs 12 e 13 | médio |
| 15 | anexos e suíte contratual de adapters | PRs 3, 5 e 14 | médio |
| 16 | remoção dos caminhos legados | todos os gates | alto |

PRs devem ser pequenos o bastante para validar no A51 e reverter isoladamente. Nenhum PR pode misturar extração arquitetural com mudança visual ampla.

## 8. Feature flags e rollout

Flags internas temporárias:

```text
canonical_agent_envelope_v2
remote_agent_transport_v2
client_capability_registry_v1
client_action_coordinator_v1
interaction_ledger_v1
mcp_capability_adapter_v2
mobile_attachments_v1
```

### Estratégia

1. implementar desligado;
2. executar testes unitários;
3. ativar em debug;
4. instalar no A51;
5. comparar logs/resultados sanitizados;
6. ativar por padrão;
7. manter rollback por uma versão;
8. remover flag e caminho antigo na Fase 8.

As flags não são configurações permanentes para o usuário. Elas existem apenas para migração segura.

## 9. Matriz de validação no A51

### Turnos

- texto direto;
- voz sem wake word;
- wake word seguida de pedido sem repetir o nome;
- wake word, pedido e gesto de parada;
- múltiplos trechos agrupados;
- reconnect durante envio;
- resposta longa com anexo;
- erro e timeout remoto.

### Eventos internos

- compactação;
- manutenção;
- memória interna;
- aviso de sistema visível;
- banner temporário sem interromper definitivamente gestos/câmera.

### Ferramentas

- screenshot;
- foto;
- TTS e interrupção;
- configuração válida e inválida;
- WOL confirmado e não confirmado;
- MCP disponível, sem login, expirado e offline;
- cancelamento durante execução;
- repetição do mesmo `callId`.

### Persistência

- reinício durante transcrição;
- reinício durante ação;
- reinstalação com `install -r`;
- expiração do áudio sem perda do texto;
- migração de `chat_history.json`;
- retenção do ledger.

## 10. Métricas e gates

### Métricas funcionais

- 100% dos turnos enviados têm `turnId`;
- 100% das ações têm `callId` e estado final;
- zero evento interno no TTS;
- zero ferramenta anunciada sem handler;
- zero execução de ferramenta ausente do catálogo;
- zero mensagem do usuário sem atividade, resposta ou falha;
- zero token em chat, log exportável ou ledger;
- reconnect não duplica ação não idempotente.

### Métricas arquiteturais

- UI sem import de `openclaw`;
- serviço de áudio sem catálogo ou `when` de ferramentas;
- protocolo canônico sem dependência de Android UI;
- adapter OpenClaw testável isoladamente;
- redução progressiva das 6.362 linhas do serviço;
- classes novas com responsabilidade dominante e testes próprios.

### Gates de liberação

| Gate | Requisito |
|---|---|
| A | contrato canônico passa fixtures sem mudar servidor |
| B | OpenClaw funciona via `RemoteAgentTransport` no A51 |
| C | todas as tools usam o registro e ciclo final explícito |
| D | ledger recupera crash/reconnect sem duplicação |
| E | MCP usa o mesmo protocolo de capacidade e não vaza segredo |
| F | mock e OpenClaw passam a mesma suíte contratual |
| G | caminhos legados removidos com migração validada |

## 11. Riscos e mitigação

| Risco | Mitigação |
|---|---|
| quebrar OpenClaw durante abstração | adapter compatível e fixtures antes da extração |
| duplicar ação em reconnect | `callId`, ledger e política explícita de idempotência |
| Room aumentar risco de migração | escrita dupla, feature flag e rollback temporário |
| catálogo crescer demais | descoberta progressiva e cache versionado |
| ação local ficar sem feedback | coordenador cria atividade antes de executar |
| evento interno escapar pelo texto legado | filtro no codec e segunda defesa na apresentação |
| segredo MCP aparecer em erro | sanitização central antes de log, chat, ledger e agente |
| “adapter” virar agente local | revisão obrigatória contra a fronteira canônica |
| PR grande demais | sequência de PRs por responsabilidade e gate no A51 |

## 12. Definição de pronto do programa

O programa estará concluído quando:

1. OpenClaw operar exclusivamente pelo contrato e transporte canônicos.
2. Um mock de outro agente completar o mesmo fluxo sem alterar UI ou áudio.
3. Catálogo e dispatch vierem do mesmo registro.
4. Toda ação produzir progresso e resultado final com evidência apropriada.
5. Turnos, chamadas e entregas forem correlacionados e recuperáveis.
6. Compactação, memória e manutenção internas nunca forem faladas.
7. MCP compartilhar disponibilidade, ciclo e segurança das capacidades nativas.
8. Conteúdo longo puder chegar como anexo com resumo curto.
9. O doctor produzir relatório sanitizado e anexável.
10. `RoomAudioForegroundService` não concentrar protocolo, catálogo, dispatch e apresentação.
11. O APK continuar sendo somente a interface entre pessoa e agente remoto.

## 13. Primeira fatia recomendada

A primeira execução deve abranger somente os PRs 1 a 3:

1. fixtures e testes de caracterização;
2. modelos canônicos de turno/resposta;
3. filtro de eventos internos e política de apresentação móvel.

Essa fatia resolve a base do contrato, protege chat/TTS contra mensagens internas e não altera ainda o transporte ou as ferramentas. Depois de validada no A51, o trabalho pode seguir em paralelo controlado para transporte e registro de capacidades.

## 14. Fontes do Hermes utilizadas

- [Hermes Agent — repositório](https://github.com/NousResearch/hermes-agent)
- [Architecture](https://hermes-agent.nousresearch.com/docs/developer-guide/architecture)
- [Adding Platform Adapters](https://hermes-agent.nousresearch.com/docs/developer-guide/adding-platform-adapters)
- [Android / Termux](https://hermes-agent.nousresearch.com/docs/getting-started/termux)
- [MCP Servers](https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp)
- [Security](https://hermes-agent.nousresearch.com/docs/user-guide/security)
- [`tools/registry.py`](https://github.com/NousResearch/hermes-agent/blob/fed098bbf0c104edbe232002901c43491b0c6155/tools/registry.py)
- [`gateway/platform_registry.py`](https://github.com/NousResearch/hermes-agent/blob/fed098bbf0c104edbe232002901c43491b0c6155/gateway/platform_registry.py)
- [`gateway/delivery_ledger.py`](https://github.com/NousResearch/hermes-agent/blob/fed098bbf0c104edbe232002901c43491b0c6155/gateway/delivery_ledger.py)
- [`agent/memory_provider.py`](https://github.com/NousResearch/hermes-agent/blob/fed098bbf0c104edbe232002901c43491b0c6155/agent/memory_provider.py)
- [`agent/iteration_budget.py`](https://github.com/NousResearch/hermes-agent/blob/fed098bbf0c104edbe232002901c43491b0c6155/agent/iteration_budget.py)

O reaproveitamento proposto é arquitetural. Código substancial eventualmente portado deve preservar os avisos exigidos pela licença MIT.
