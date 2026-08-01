# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Pessoas que usam um celular Android como interface permanente ou ocasional para conversar por voz, texto e gestos com um agente remoto. A configuração precisa continuar utilizável por pessoas sem conhecimento de modelos, protocolos, MCP ou redes.

## Product Purpose

O Sufficit Android AI Gateway traduz a interação humana e o contexto do aparelho para um agente remoto, apresenta respostas de forma adequada a uma tela pequena e executa capacidades autorizadas do cliente. Sucesso significa conversar e configurar o sistema sem confundir o gateway com o agente.

## Positioning

O aplicativo é uma interface multimodal e um orquestrador de capacidades do aparelho. Cognição, planejamento, memória geral e autonomia pertencem ao OpenClaw, Hermes ou outro agente remoto substituível.

## Operating Context

- celular em uma sala com microfone aberto, ruído, música e conversas que podem não ser dirigidas ao agente;
- monitor local permanente de wake word e captura ambiente separada;
- interação por voz, texto, gestos, câmera e anexos;
- agentes remotos com respostas curtas, ações cliente e conteúdo longo entregue como arquivo;
- configuração no próprio celular, frequentemente feita uma única vez e revisitada apenas quando algo falha.

## Capabilities and Constraints

- Jetpack Compose e Material 3;
- OpenClaw é o adapter remoto padrão, mas não pode contaminar o contrato da UI;
- autenticação Sufficit protege MCP, memória e preferências por usuário;
- tools, prompts e resources MCP são descobertos dinamicamente;
- ações locais precisam de progresso, resultado final, timeout e cancelamento visíveis;
- a tela de chat é a única superfície de gestos;
- configurações avançadas continuam disponíveis, mas não devem dominar o caminho principal;
- segredos, tokens e conteúdo privado não aparecem em logs, diagnósticos ou telas resumidas.

## Brand Commitments

- nome do produto: Sufficit Android AI Gateway;
- português do Brasil como linguagem principal;
- configuração com sensação de jogo, simples e amigável, sem pontuação, ranking competitivo ou infantilização;
- poucas escolhas e poucas perguntas por tela;
- ícones vetoriais Material, nunca emoji estrutural.

## Evidence on Hand

- implementação funcional em `app/src/main/java/com/sufficit/ai/gateway`;
- arquitetura e fronteira em `docs/architecture.md` e `docs/product-boundary-and-agent-interface.md`;
- aparelho de validação Samsung A51;
- fluxos existentes de chat, Wake Lab, MCP, identidade, voz, câmera, API e diagnóstico.

## Product Principles

1. Uma escolha clara por vez.
2. Mostrar resultado e próximo passo, não detalhes internos primeiro.
3. Tornar o caminho comum lúdico e manter o caminho técnico acessível sob demanda.
4. Nunca esconder falha, espera, permissão ou indisponibilidade.
5. Preservar a fronteira entre interface local e agente remoto.

## Accessibility & Inclusion

Material 3, alvos de toque de pelo menos 48 dp, suporte ao tamanho de fonte do sistema, contraste legível, rótulos de acessibilidade e alternativa visível para qualquer gesto crítico.
