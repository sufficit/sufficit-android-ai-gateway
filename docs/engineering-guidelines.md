# Diretrizes de Engenharia

## Regra principal

Arquivos de código não devem ultrapassar 400 linhas.

Essa regra existe para:

- reduzir confusão durante manutenção
- diminuir regressões em refactors
- facilitar revisão humana
- evitar classes com múltiplas responsabilidades

## Quando extrair

Extraia código quando ocorrer qualquer um destes sinais:

- arquivo acima de 300 linhas e ainda crescendo
- mesma classe contendo UI, lógica, estado e utilidades
- helpers visuais repetidos em mais de um ponto
- regras textuais ou catálogos hardcoded no código
- bloco grande que pode ser nomeado como componente ou serviço próprio

## Estratégia preferida

1. Extrair componentes visuais para arquivos próprios.
2. Extrair estilos, badges, tooltips e cores para arquivos auxiliares.
3. Extrair utilitários de parsing, formatação e catálogos.
4. Manter a activity apenas como composição e coordenação.

## UI

Para telas Compose:

- um arquivo por área grande da tela
- um arquivo para componentes compartilhados
- um arquivo para estilos/formatadores quando necessário

Evitar:

- dezenas de `@Composable` grandes no mesmo arquivo
- cores espalhadas em múltiplos pontos sem centralização
- helpers privados gigantes dentro da `MainActivity`

## Configuração textual

Sempre que possível:

- mover mapas e regras para `assets/`
- usar formatos simples e legíveis
- evitar listas hardcoded longas no Kotlin

## Refatoração incremental

Antes de abrir uma nova frente grande:

1. garantir que o projeto compila
2. extrair um bloco de cada vez
3. validar build novamente
4. só então seguir para a próxima extração

## Regra operacional deste projeto

Sempre que houver reinstalação do APK durante desenvolvimento:

- reabrir o app
- confirmar que a `MainActivity` ficou em foreground

Isso evita validar uma build nova com o app fechado ou preso em tela anterior.
