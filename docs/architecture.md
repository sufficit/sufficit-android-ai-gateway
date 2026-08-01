# Arquitetura

## Objetivo

Usar um celular Android como interface multimodal entre uma pessoa e um agente remoto — OpenClaw, Hermes ou outro compatível — com foco em:

- interação natural por voz, texto e gestos;
- operação estável com tela apagada;
- transporte claro de contexto e capacidades do cliente;
- respostas adequadas para tela pequena e TTS;
- execução observável e interrompível de ferramentas do aparelho;
- independência entre a interface Android e o runtime do agente.

O Gateway não interpreta nem planeja como um agente. Raciocínio, memória cognitiva, skills, subagentes e autonomia pertencem ao agente remoto. A definição canônica dessa fronteira está em [product-boundary-and-agent-interface.md](./product-boundary-and-agent-interface.md).

## Limite de responsabilidade

O código local pode aplicar heurísticas de interface — VAD, AGC, wake word, agrupamento de fala, política de gestos, validação de schema, apresentação e verificação de uma ação local. Ele não deve criar um segundo loop cognitivo concorrente ao OpenClaw/Hermes.

Uma integração permanece saudável quando:

- o agente pode ser substituído sem reescrever sensores e UI;
- o aplicativo pode ser substituído sem mudar o núcleo do agente;
- a comunicação usa um contrato explícito de turno, resposta, ação e resultado;
- eventos internos nunca são confundidos com resposta ao usuário;
- conteúdo longo é entregue como documento/anexo, não despejado no chat ou TTS.

## Visão geral

O software é dividido em cinco áreas principais:

1. `audio`
   Captura PCM, VAD, segmentação, fila, análise local e integração com o serviço em foreground.

2. `transcription`
   Backends de transcrição remotos e locais, incluindo resolução de modelo, execução e tratamento de erro.

3. `runtime`
   Estado compartilhado da aplicação e sinais exibidos na UI.

4. `ui`
   Componentes visuais da tela principal, configuração, dicionário e badges/ícones auxiliares.

5. `agentinterface` e `agentinterface.transport`
   Contrato canônico, política de apresentação e adapters de transporte. OpenClaw é uma implementação; o restante do app não depende de seu envelope.

6. `capabilities`
   Registro único de catálogo/handler e coordenador do ciclo de ações, incluindo timeout, cancelamento, disponibilidade e aliases.

7. `ledger`
   Room/SQLite para correlação de turnos, fontes, entregas, ações e eventos sanitizados. Não é memória cognitiva.

8. `mcp`, `api` e `diagnostics`
   MCP entra dinamicamente no registro; a API local expõe controle autorizado; o doctor exporta saúde técnica sem segredos ou conteúdo bruto da conversa.

## Fluxo principal

1. A `MainActivity` sobe e carrega as configurações persistidas.
2. O app inicializa o `RoomAudioForegroundService` quando a escuta está habilitada.
3. O serviço captura áudio com `AudioRecord`.
4. O VAD decide quando abrir e fechar segmentos.
5. Cada segmento segue para o backend configurado:
   - remoto
   - local CPU
   - local NNAPI
6. O resultado é reconciliado com a janela de transcrição atual.
7. Pausas e sinais de interação formam um turno humano consolidado.
8. O turno, as restrições da superfície móvel e as capacidades cliente seguem para o agente remoto.
9. O agente devolve resposta, detalhes, anexos e/ou ações locais.
10. O Gateway executa apenas ações anunciadas, devolve resultados ao agente e atualiza o `GatewayRuntime`.
11. Chat, atividade, ação, falha e mídia são persistidos para auditoria.
12. O ledger fecha estados pendentes após reinício e impede repetir uma ação com o mesmo `callId`.

## Persistência operacional

`ChatHistoryStore` permanece como projeção visual compatível em JSON. Em paralelo,
`InteractionLedgerDatabase` registra somente correlações e resumos sanitizados:

- `interaction_turns` e `interaction_sources`;
- `remote_deliveries`;
- `client_action_calls`;
- `interaction_events`.

Texto integral, áudio, tokens e credenciais não entram no ledger. O schema Room exportado
fica em `app/schemas/` e toda evolução do banco exige migração explícita.

## Camadas e responsabilidades

### Activity

`MainActivity` deve coordenar navegação, estado de tela e ligação entre store/runtime/UI.

Ela não deve concentrar:

- heurísticas de transcrição
- regras de análise de voz
- componentes visuais grandes
- lógica de download
- parsing de catálogos

### Serviço de áudio

`RoomAudioForegroundService` é o orquestrador de execução contínua.

Ele pode coordenar:

- captura
- VAD
- despacho para transcrição
- atualização do runtime

Ele não deve crescer indefinidamente. Sempre que um bloco ganhar responsabilidade própria, ele deve ser extraído.

### UI

A UI deve ser separada por área funcional:

- dashboard principal
- componentes de configuração
- componentes de dicionário
- badges, ícones e tooltips

Componentes compartilhados devem ficar em arquivos auxiliares dedicados.

## Regras de decomposição

- Nenhum arquivo de código deve passar de 400 linhas sem justificativa forte.
- Ao se aproximar de 300 linhas, já se deve procurar extração.
- Uma classe ou arquivo deve ter uma responsabilidade dominante clara.
- Helpers visuais, estilos, badges e tooltips não devem ficar espalhados pela activity principal.
- Regras textuais e catálogos configuráveis devem preferir assets legíveis em vez de hardcode em Kotlin.

## Organização desejada

### Já extraído

- `TooltipIcons.kt`
- `TranscriptBadgeStyles.kt`
- `GatewayDashboardUi.kt`
- `GatewayConfigComponents.kt`

### Próximas extrações naturais

- `GatewayConfigPage.kt`
- `GatewayModelDownloads.kt`
- `GatewaySettingsPersistence.kt`
- `GatewayUiFormatting.kt`

## Critérios de manutenção

Uma mudança é considerada saudável quando:

- o arquivo tocado continua pequeno
- a responsabilidade ficou mais nítida
- a UI não depende de helpers escondidos dentro da activity
- a compilação continua simples de validar

Se uma implementação exigir um arquivo grande, a preferência é dividir por responsabilidade antes de acrescentar novos blocos.
