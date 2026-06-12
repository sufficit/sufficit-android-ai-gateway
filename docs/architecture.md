# Arquitetura

## Objetivo

Usar um celular Android antigo como gateway de captura de voz ambiente para o OpenClaw, com foco em:

- escuta contínua
- processamento local quando fizer sentido
- operação estável com tela apagada
- baixa necessidade de intervenção manual

## Visão geral

O software é dividido em quatro áreas principais:

1. `audio`
   Captura PCM, VAD, segmentação, fila, análise local e integração com o serviço em foreground.

2. `transcription`
   Backends de transcrição remotos e locais, incluindo resolução de modelo, execução e tratamento de erro.

3. `runtime`
   Estado compartilhado da aplicação e sinais exibidos na UI.

4. `ui`
   Componentes visuais da tela principal, configuração, dicionário e badges/ícones auxiliares.

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
7. O `GatewayRuntime` atualiza a UI.
8. Frases consolidadas podem ser registradas no histórico local.

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
