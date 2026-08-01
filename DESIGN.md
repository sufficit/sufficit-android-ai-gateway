---
name: Sufficit Android AI Gateway
description: Um mapa noturno e tátil para configurar a ponte entre pessoas e agentes.
colors:
  night-deep: "#050B14"
  night-blue: "#0B1826"
  panel: "#102033"
  panel-raised: "#172B42"
  control: "#1B314A"
  border: "#2A435D"
  text-primary: "#F1F6FB"
  text-secondary: "#B4C5D6"
  text-muted: "#8298AD"
  success: "#35D08C"
  connection: "#55B8F5"
  voice: "#FFC857"
  identity: "#C8A5FF"
  energy: "#5DD7C6"
  danger: "#FF8A80"
typography:
  headline:
    fontFamily: "Roboto, sans-serif"
    fontSize: "24sp"
    fontWeight: 700
    lineHeight: 1.25
  title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "16sp"
    fontWeight: 700
    lineHeight: 1.5
  body:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: 1.45
  label:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: 1.4
rounded:
  control: "12dp"
  card: "16dp"
  round: "999dp"
spacing:
  tight: "8dp"
  standard: "12dp"
  screen: "16dp"
  card: "16dp"
components:
  path-card:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.card}"
    padding: "16dp 18dp"
    height: "104dp"
  path-card-featured:
    backgroundColor: "{colors.panel-raised}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.card}"
    padding: "20dp 18dp"
    height: "132dp"
  control-row:
    backgroundColor: "{colors.control}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "10dp 14dp"
    height: "64dp"
---

# Design System: Sufficit Android AI Gateway

## Overview

**Creative North Star: "O mapa noturno de missões"**

O sistema transforma uma configuração técnica em quatro caminhos reconhecíveis. A sensação de jogo vem de escolher uma rota, avançar por etapas e enxergar o estado de cada capacidade; nunca de pontos, ranking ou recompensas artificiais.

O cenário é um celular usado numa sala, muitas vezes à noite ou à distância. O visual é escuro, firme e legível, com superfícies tonais, nós circulares e cores de rota que facilitam orientação sem tornar a interface infantil.

**Key Characteristics:**

- quatro áreas principais e poucas escolhas por tela;
- uma ação dominante por bloco;
- detalhes técnicos somente nas folhas da navegação;
- estados reais no lugar de placares;
- ícones Material e alvos de toque de pelo menos 48 dp.

## Colors

A paleta combina uma base azul-noturna com texto frio e acentos funcionais de alta legibilidade.

### Primary

- **Sinal Verde** (`#35D08C`): prontidão, sucesso, descoberta e ação principal.
- **Conexão Azul** (`#55B8F5`): agente remoto, sessão e transporte.

### Secondary

- **Voz Âmbar** (`#FFC857`): captura, transcrição e conversa por voz.
- **Identidade Lilás** (`#C8A5FF`): conta, preferências e ações secundárias.
- **Energia Turquesa** (`#5DD7C6`): tela e comportamento do aparelho.

### Neutral

- **Noite Profunda** (`#050B14`) e **Noite Azul** (`#0B1826`): fundo em gradiente vertical.
- **Painel** (`#102033`), **Painel Elevado** (`#172B42`) e **Controle** (`#1B314A`): três níveis tonais de superfície.
- **Borda Azul-Aço** (`#2A435D`): separação estrutural de 1 dp.
- **Texto Primário** (`#F1F6FB`), **Secundário** (`#B4C5D6`) e **Silencioso** (`#8298AD`): hierarquia textual.
- **Alerta Coral** (`#FF8A80`): perigo e diagnóstico que requer atenção.

**The Route Color Rule.** Cada caminho mantém um único acento; verde continua reservado à ação principal ou ao sucesso comprovado.

## Typography

**Display Font:** Roboto (fallback do sistema Android)
**Body Font:** Roboto (fallback do sistema Android)

**Character:** direta, familiar e altamente legível. O peso, o tamanho e a cor criam a hierarquia; não há fonte decorativa.

### Hierarchy

- **Headline** (700, 24–28 sp): título de tela e estado geral.
- **Title** (700, 16–22 sp): caminho, seção e ação relevante.
- **Body** (400, 14–16 sp): explicações com no máximo duas linhas nos cartões de rota.
- **Label** (500–700, 12–14 sp): estado, métrica e controle compacto.

**The Plain Language Rule.** Títulos descrevem a intenção humana — “Entender sua voz” — antes do nome do protocolo ou modelo.

## Layout

As telas usam uma única coluna rolável, 16 dp nas bordas e ritmo vertical de 12 dp. A home apresenta cabeçalho, um caminho destacado e três caminhos regulares. Categorias contêm de duas a quatro rotas; telas folha podem rolar e preservar os controles técnicos existentes.

O retorno respeita a hierarquia folha → categoria → home. Cartões regulares medem no mínimo 104 dp, o cartão inicial destacado mede 132 dp e controles interativos nunca têm menos de 48 dp.

## Elevation & Depth

O sistema não usa sombras. Profundidade vem da sequência tonal fundo → painel → painel elevado → controle, apoiada por bordas de 1 dp e acentos translúcidos nos nós circulares.

**The Tonal Depth Rule.** Elevação é informação de hierarquia, não decoração; não adicionar sombras, brilho difuso ou vidro.

## Shapes

Cartões e contêineres usam 16 dp. Controles internos usam 12 dp. Nós, estados e botões de destaque podem ser totalmente circulares. Pequenos marcadores quadrados de 10 dp, com raio de 3 dp, identificam o início de uma seção sem competir com seu título.

## Components

### Buttons

- **Shape:** pílula ou controle Material com alvo mínimo de 48 dp.
- **Primary:** Sinal Verde sobre base noturna; somente uma ação principal por bloco.
- **Secondary:** contorno neutro e texto lilás ou da cor da rota.
- **Focus/press:** ripple e estados nativos do Material 3.

### Chips

- **Style:** fundo Controle, borda Azul-Aço e altura mínima de 48 dp.
- **State:** selecionado ganha fundo translúcido e ícone de confirmação na cor Sinal Verde.

### Cards / Containers

- **Corner Style:** 16 dp.
- **Background:** Painel ou Painel Elevado.
- **Shadow Strategy:** nenhuma sombra; usar contraste tonal.
- **Border:** 1 dp; cartões de rota recebem a cor da rota com transparência.
- **Internal Padding:** 16–20 dp.

### Inputs / Fields

- **Style:** fundo Controle, borda Azul-Aço e rótulo Material.
- **Focus:** borda e cursor Sinal Verde.
- **Secrets:** sempre mascarados; resumos mostram apenas estado protegido e, quando indispensável, os quatro últimos caracteres.

### Navigation

O mapa usa cartões inteiros como alvo de toque, ícone circular à esquerda e seta à direita. O cabeçalho de categoria e folha possui botão Voltar independente de 48 dp e respeita o botão de sistema do Android.

### Path Card

É o componente assinatura: título humano, descrição curta, estado real colorido e ícone Material. Nunca exibe XP, porcentagem inventada ou uma chave secreta.

## Do's and Don'ts

### Do:

- **Do** mostrar estado real e próximo passo em cada rota.
- **Do** manter entre duas e quatro escolhas em categorias.
- **Do** preservar controles avançados nas telas folha.
- **Do** usar português do Brasil e linguagem orientada à tarefa.
- **Do** garantir alternativa visível para gestos e alvos de toque de 48 dp.

### Don't:

- **Don't** reintroduzir uma lista única de cartões técnicos na home.
- **Don't** usar pontos, XP, ranking ou recompensas falsas.
- **Don't** usar emoji como ícone estrutural.
- **Don't** revelar tokens, IDs sensíveis ou credenciais em resumos e screenshots.
- **Don't** usar gradientes em componentes, sombras ou efeitos de vidro; o único gradiente é o fundo noturno.
