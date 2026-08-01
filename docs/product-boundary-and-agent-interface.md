# Fronteira do produto e contrato de interface com agentes

## Status desta decisão

Este documento é a referência canônica para o propósito do Sufficit Android AI Gateway.

Qualquer proposta de arquitetura, feature ou integração deve ser confrontada com esta decisão antes de ser implementada. Em caso de conflito com documentos mais antigos, este documento e a seção **Responsabilidade do produto** do `README.md` prevalecem.

## Intenção do produto

O Sufficit Android AI Gateway é uma **interface multimodal e um orquestrador de interação** entre uma pessoa e um agente de inteligência artificial remoto.

O agente remoto pode ser OpenClaw, Hermes Agent ou qualquer outro runtime que implemente o contrato de comunicação. O Gateway não depende da personalidade, do modelo ou da arquitetura interna desse agente.

O aplicativo existe para tornar natural uma conversa entre:

- uma pessoa, frequentemente falando em uma sala;
- um telefone Android com tela pequena, microfone, câmera, alto-falante e acesso à rede local;
- um agente remoto que interpreta, raciocina, decide e responde.

Em uma frase:

> O Gateway traduz pessoas e capacidades do aparelho para o agente remoto e traduz a resposta do agente para uma experiência móvel curta, audível, visível e interrompível.

## Responsabilidades

### O Gateway é responsável por

- capturar voz, texto e gestos;
- detectar wake word e conservar o contexto de que o usuário acordou o agente;
- segmentar áudio e transcrever localmente ou por serviços especializados;
- agrupar trechos que pertencem ao mesmo turno humano;
- transportar o turno e seus sinais de contexto ao agente remoto;
- informar ao agente as restrições da interface móvel;
- anunciar capacidades locais disponíveis, com schemas claros;
- executar no aparelho apenas ações explicitamente expostas como ferramentas cliente;
- devolver ao agente o resultado e a evidência dessas ações;
- mostrar estados de fila, processamento, ação, sucesso, retenção, timeout e falha;
- apresentar respostas em chat, TTS, anexos e detalhes visuais;
- permitir interrupção imediata por toque, texto ou gesto;
- proteger identidade, tokens, mídia e permissões do aparelho;
- ocultar do usuário e do TTS eventos internos de manutenção.

### O agente remoto é responsável por

- interpretar o significado do turno;
- decidir se a fala é dirigida ao agente quando não há uma sessão acordada;
- raciocinar, planejar e decompor tarefas;
- escolher ferramentas locais ou remotas;
- usar memória e contexto cognitivo;
- decidir quando pedir confirmação;
- produzir a resposta final;
- adaptar conteúdo longo para um documento, link ou anexo;
- manter autonomia, subagentes, skills e tarefas agendadas, quando suportados;
- respeitar os limites e capacidades anunciados pelo Gateway.

### Serviços especializados são responsáveis por

- STT, TTS e modelos locais ou remotos;
- armazenamento de memória Sufficit;
- servidores MCP;
- integrações externas;
- execução de ferramentas que não pertencem ao aparelho.

## Não responsabilidades do Gateway

O Gateway não deve se tornar:

- um segundo agente concorrente ao OpenClaw/Hermes;
- um planner cognitivo geral;
- um runtime de subagentes;
- um sistema de skills autoexecutáveis;
- uma memória cognitiva paralela à memória Sufficit;
- um terminal ou shell genérico;
- um executor de código baixado dinamicamente;
- o dono de cron, tarefas autônomas ou workflows do agente;
- um repositório de prompts específicos de um único modelo;
- um processador de documentos longos na UI do chat.

Heurísticas locais são permitidas quando pertencem à interface — VAD, AGC, wake word, agrupamento de fala, política de gestos, expiração de áudio, formatação e validação de protocolo. Elas não devem decidir a resposta intelectual do agente.

## A fronteira arquitetural

```text
Pessoa
  │ voz, texto, gesto
  ▼
Sufficit Android AI Gateway
  │
  ├── normaliza entrada e preserva sinais de interação
  ├── anuncia restrições da superfície móvel
  ├── anuncia capacidades locais
  ├── executa ferramentas cliente explicitamente solicitadas
  └── renderiza/fala a resposta e o estado
  │
  ▼
Contrato de agente remoto
  │
  ├── OpenClaw
  ├── Hermes Agent
  └── outro agente compatível
      │
      ├── raciocínio
      ├── memória
      ├── planejamento
      ├── ferramentas remotas
      └── resposta final
```

O contrato é o “narrow waist” do produto: sensores e UI podem evoluir de um lado; agentes e modelos podem ser trocados do outro.

## Contexto que deve acompanhar cada turno

O agente precisa saber como a mensagem chegou e em qual superfície responder. Campos opcionais só devem ser enviados quando há valor real; o contrato mínimo de wake word continua sendo apenas `awakened` e `wakeWord`.

Exemplo conceitual:

```json
{
  "type": "interaction.turn",
  "turnId": "uuid",
  "text": "o texto consolidado do turno humano",
  "interaction": {
    "inputMode": "voice",
    "surface": "android_mobile_chat",
    "voiceReplyAvailable": true,
    "awakened": true,
    "wakeWord": "xuxu",
    "multipleVoicesLikely": false
  },
  "presentation": {
    "preferConcise": true,
    "preferSpeakable": true,
    "preferredTextChars": 480,
    "preferredSpeechSeconds": 25,
    "supportsDetails": true,
    "supportsAttachments": true,
    "supportsClientActions": true
  },
  "availableTools": []
}
```

Os valores de tamanho são preferências de apresentação, não limites que autorizam truncar informação essencial.

### Semântica dos principais sinais

| Campo | Significado |
|---|---|
| `inputMode` | `voice`, `text`, `gesture` ou combinação conhecida |
| `surface` | a resposta será consumida em uma tela pequena de chat Android |
| `voiceReplyAvailable` | o aparelho pode falar a resposta naquele momento |
| `awakened` | a escuta deste contexto foi iniciada por wake word; o turno é dirigido ao agente |
| `wakeWord` | termo efetivamente reconhecido, ou `null` quando não houve |
| `multipleVoicesLikely` | possível sobreposição; é evidência, não ordem para descartar |
| `preferConcise` | usar resposta curta por padrão |
| `supportsDetails` | conteúdo visual adicional pode ir fora do texto falado |
| `supportsAttachments` | conteúdo longo pode ser entregue como arquivo/link |
| `availableTools` | catálogo atual das capacidades executáveis no cliente |

Quando `awakened=true`, o agente deve tratar o conteúdo subsequente da sessão acordada como dirigido a ele até a parada explícita da escuta ambiente. A wake word pode ter sido consumida pelo detector e não precisa aparecer na transcrição.

## Contrato de resposta

O agente deve separar quatro coisas diferentes:

1. **texto curto do chat** — resposta principal para leitura rápida;
2. **texto falado** — versão natural e pronunciável;
3. **detalhes visuais** — informações que não devem ser lidas pelo TTS;
4. **anexos/ações** — documentos, links, imagens ou ferramentas cliente.

Exemplo:

```json
{
  "type": "reply",
  "turnId": "uuid",
  "message": {
    "text": "Preparei o relatório. O principal risco está na autenticação da API local.",
    "speech": "Preparei o relatório. O principal risco está na autenticação da API local.",
    "details": "Resumo técnico expandido opcional para a tela.",
    "attachments": [
      {
        "kind": "document",
        "name": "relatorio-seguranca.md",
        "uri": "..."
      }
    ]
  },
  "actions": []
}
```

### Regras de apresentação para o agente

- responder como em um chat normal, não como em um terminal;
- começar pelo resultado, não por uma longa explicação do processo;
- preferir uma a três mensagens/parágrafos curtos;
- usar listas apenas quando forem realmente mais legíveis;
- não ler URLs extensas, JSON, stack traces, tabelas ou metadados;
- colocar conteúdo visual não pronunciável em `details`;
- transformar conteúdo longo em documento ou anexo e enviar um resumo curto;
- não descrever integralmente um documento que pode ser entregue para abertura no celular;
- declarar claramente quando uma ação foi apenas enviada e quando foi confirmada;
- se não houver resposta final, emitir estado ou falha explícita;
- nunca devolver mensagens de compactação, manutenção, prompt, memória interna ou controle como resposta ao usuário;
- não fingir que uma ferramenta foi executada: aguardar o resultado cliente quando ele for necessário.

## Prompt base independente do agente

Este bloco deve ser aplicado no adaptador do agente remoto ou enviado como contexto de canal estável. Ele não deve ser repetido como mensagem visível ao usuário.

```text
Você está conversando com uma pessoa por meio do Sufficit Android AI Gateway.

O Gateway é uma interface móvel de voz, texto e gestos. A pessoa normalmente está
em uma sala, pode falar em voz alta e verá sua resposta em uma tela pequena de chat.
Quando voiceReplyAvailable=true, sua resposta também poderá ser falada por TTS.

Responda como em uma conversa natural. Seja curto por padrão, comece pelo resultado e
evite conteúdo difícil de consumir por voz. Separe texto falado de detalhes visuais.
Não leia URLs longas, JSON, tabelas, logs, metadados ou mensagens de sistema.

Quando o conteúdo for extenso, gere ou forneça um documento/anexo e apresente apenas
um resumo curto com a orientação para abri-lo no celular. Não tente despejar o conteúdo
inteiro no chat.

O campo awakened=true significa que a sessão foi iniciada por uma wake word e que as
mensagens seguintes são dirigidas a você até uma parada explícita. A wake word pode não
estar dentro da transcrição porque foi consumida localmente.

availableTools descreve capacidades executadas no aparelho. Use apenas ferramentas
anunciadas, respeite seus schemas e aguarde o resultado. Diferencie ação despachada de
resultado confirmado. Se uma ferramenta falhar, expirar ou ficar indisponível, informe
isso de forma curta e acionável.

Eventos internos de compactação, manutenção, memória, prompt ou controle não são
respostas para a pessoa: não os fale nem os apresente como mensagem normal.
```

## Ferramentas cliente

Ferramentas cliente são capacidades do aparelho, não capacidades cognitivas do Gateway.

O aplicativo pode:

- descobrir e anunciar ferramentas;
- validar argumentos;
- aplicar permissões e política local;
- executar a ação;
- verificar uma pós-condição quando possível;
- devolver resultado e evidência ao agente;
- mostrar progresso e falha.

O agente remoto continua responsável por escolher a ferramenta e decidir o que fazer com o resultado.

Cada resultado deve distinguir:

- `accepted`: pedido aceito localmente;
- `dispatched`: ação entregue à pilha do aparelho/rede;
- `confirmed`: pós-condição observada;
- `unverified`: não foi possível provar o resultado;
- `failed`: execução falhou;
- `timed_out`: prazo esgotado;
- `canceled`: pessoa interrompeu;
- `denied`: política ou permissão impediu.

## MCP e memória Sufficit

O Gateway pode funcionar como ponte autenticada para MCP porque ele possui a identidade do usuário e capacidades locais. Essa ponte não torna o Gateway dono da memória cognitiva.

Regras:

- sessão e `userId` derivam do token Sufficit autenticado;
- o aplicativo descobre tools, prompts e resources, sem catálogo remoto hardcoded;
- o agente decide quando consultar ou salvar memória;
- o Gateway somente transporta a chamada e devolve o resultado;
- preferências estritamente locais podem ser sincronizadas quando o usuário pediu ou quando o contrato da feature determina isso;
- não manter uma segunda memória geral em arquivos locais;
- indisponibilidade da memória deve aparecer como erro de ferramenta, não travar voz/chat;
- conteúdo recuperado é dado de contexto, nunca mensagem para TTS.

## Eventos internos

O protocolo deve classificar mensagens antes da renderização:

| Classe | Chat | TTS | Histórico técnico |
|---|---:|---:|---:|
| `USER_MESSAGE` | sim | não | sim |
| `AGENT_REPLY` | sim | conforme resposta | sim |
| `AGENT_ACTIVITY` | sim, como estado | não | sim |
| `ACTION_RESULT` | sim, resumido | opcional | sim |
| `SYSTEM_NOTICE` | banner/bolha adequada | somente se explicitamente seguro | sim |
| `SYSTEM_INTERNAL` | não | nunca | sim |
| `CONTEXT_COMPACTION` | não | nunca | sim |
| `MEMORY_INTERNAL` | não | nunca | sim |

Compactação e manutenção não podem ocupar o papel de uma resposta do agente nem interromper indefinidamente câmera, gestos ou áudio.

## O que aproveitar do Hermes Agent sem misturar responsabilidades

O roteiro incremental, com fases, arquivos, testes e critérios de aceite, está em [Plano de implementação do Gateway inspirado no Hermes Agent](./hermes-inspired-gateway-implementation-plan.md).

### 1. Gateway/adapters de plataforma

O Hermes separa o núcleo do agente das plataformas de chat. O Android deve aplicar o mesmo princípio no lado oposto: ser um adapter de plataforma estável para qualquer agente remoto.

### 2. Registro e disponibilidade de ferramentas

Centralizar schema, disponibilidade, handler e resultado impede que o Gateway anuncie uma ação que não consegue executar. Isso melhora a interface; não cria um agente local.

### 3. Execução observável e interrompível

Toda chamada precisa produzir progresso, cancelamento ou resultado final. Esse padrão pertence à experiência móvel e deve ser adotado.

### 4. Contexto estável e compactação invisível

O Hermes preserva a estabilidade do prompt e trata compactação como mecanismo interno. O Gateway deve classificar esses eventos e impedir que cheguem ao chat/TTS.

### 5. Descoberta progressiva

Catálogos grandes não precisam acompanhar todos os turnos. O Gateway pode anunciar somente toolsets relevantes ou um resumo estável, mantendo a descoberta completa sob demanda.

### 6. Segurança de MCP e resultados

Validação em configuração e execução, sanitização de erros, isolamento de credenciais e bloqueio de colisões de nomes são padrões úteis para a ponte MCP do aparelho.

### 7. Evidência de pós-condição

O agente precisa saber se a ferramenta apenas despachou uma ação ou se o resultado foi realmente observado. Isso é responsabilidade do adapter/executor local.

## Critério para novas features

Antes de implementar uma proposta, responder:

1. Isso traduz uma interação humana, uma capacidade do aparelho ou uma resposta do agente?
2. Ou isso interpreta, planeja e decide como um agente?
3. A feature continuaria válida se OpenClaw fosse substituído por Hermes?
4. O estado será compreensível em uma tela pequena e por TTS?
5. Há resultado/falha explícito e possibilidade de interrupção?
6. O contrato separa conteúdo visível, falado e interno?

Se a resposta à pergunta 2 for “sim”, a implementação provavelmente pertence ao agente remoto. Se a resposta à pergunta 3 for “não”, há acoplamento indevido a um agente específico.

## Invariantes

- o Gateway é substituível do ponto de vista do agente;
- o agente é substituível do ponto de vista do Gateway;
- raciocínio geral não mora no APK;
- capacidades locais são ferramentas tipadas;
- toda atividade termina em sucesso, falha, timeout ou cancelamento visível;
- `awakened=true` mantém o endereçamento até parada explícita;
- texto longo vira documento/anexo quando possível;
- eventos internos nunca são falados;
- memória geral do usuário permanece na Sufficit;
- nenhuma feature nova deve transformar silenciosamente o Gateway em agente.
