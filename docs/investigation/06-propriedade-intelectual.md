# 06 — Propriedade Intelectual (PI)

Licença própria, componentes de terceiros, obrigações de atribuição, proveniência de
binários e os ativos de PI genuínos da Sufficit. Análise a partir do commit `c6161a8`.

> Aviso: esta é uma análise técnica de engenharia, **não** aconselhamento jurídico.
> Para decisões de licenciamento/patente, consultar um advogado de PI.

---

## 1. Licença do projeto

**MIT**, "Copyright (c) 2026 Sufficit" (`LICENSE`). O README **não** declara nem linka a
licença (correção menor). MIT é permissiva, compatível com todos os terceiros abaixo, e
não reserva marca.

## 2. Compatibilidade de licenças — LIMPA

Todo o stack é permissivo e compatível com um projeto MIT. **Não há copyleft em lugar
nenhum** (nada de GPL/LGPL). A exposição de PI **não** é de incompatibilidade — é de
**atribuição faltante**, que é fácil de curar.

## 3. Inventário de terceiros e obrigações

| Componente | Forma no repo | Licença | Atribuição presente? | Obrigação |
|-----------|---------------|---------|----------------------|-----------|
| whisper.cpp + ggml (ggml-org) | 6 `.so` versionados + 22 headers vendorados, sem fonte | MIT | **Não** | MIT exige o aviso de copyright em cópias substanciais |
| sherpa-onnx (k2-fsa) | AAR prebuilt 26 MB (`app/libs/`) | Apache-2.0 | **Não** | Apache-2.0 espera preservação de NOTICE |
| ONNX Runtime (Microsoft, dentro do AAR) | `libonnxruntime.so` 19 MB | MIT | Não | aviso MIT |
| MediaPipe 0.10.20 (hands/facemesh) | Maven | Apache-2.0 | Não | NOTICE |
| CameraX / AndroidX / Compose | Maven | Apache-2.0 | Não | NOTICE |
| OkHttp 4.12.0 (+Okio) | Maven | Apache-2.0 | Não | NOTICE |
| NanoHTTPD 2.3.1 | Maven | **BSD-3-Clause** | **Não** | **BSD-3 exige reproduzir copyright+disclaimer em distribuição binária** (o APK no Releases é distribuição binária) |
| net.i2p.crypto:eddsa 0.3.0 | Maven (**não usado**) | CC0-1.0 | n/a | nenhuma — **remover a dep** |
| libc++_shared.so (LLVM/NDK) | `.so` versionado | Apache-2.0 c/ LLVM exception | Não | NOTICE |
| Modelos Whisper ONNX (download runtime) | HF `csukuangfj/*` | MIT (pesos OpenAI Whisper) | Não | baixados no device, não redistribuídos pelo repo → obrigação baixa |
| Modelo CAM++ 3D-Speaker (download runtime) | k2-fsa GH release | Apache-2.0 | Não | idem |
| Kokoro TTS (planejado, PLAN-2026-06-23) | releases sherpa | Apache-2.0 | futuro | atenção futura |

## 4. Achados de PI (por severidade)

### ALTO — Atribuição de terceiros ausente (não-conformidade de redistribuição)
O APK publicado nos GitHub Releases é uma **distribuição binária** que embute
sherpa-onnx, ONNX Runtime, MediaPipe, OkHttp, whisper.cpp/ggml e NanoHTTPD. Não há
**nenhum** NOTICE, tela de licenças de terceiros, diretório `licenses/`, nada —
`find -iname "*license*" -o -iname "*notice*"` só encontra o `LICENSE` MIT do próprio
projeto.
- **Cura (baixo esforço)**:
  1. Adicionar `THIRD-PARTY-NOTICES.md` com as licenças de todos os componentes
     redistribuídos.
  2. Copiar `LICENSE.ggml`/`LICENSE.whisper` junto de `cpp/include/` e `jniLibs/`.
  3. Copiar o NOTICE do sherpa-onnx junto de `app/libs/`.
  4. Adicionar tela in-app de "Licenças de código aberto" (o
     `oss-licenses-plugin` do Google ou `licensee` automatiza).

### ALTO — Proveniência de binários prebuilt (cadeia de suprimentos)
Os 6 `.so` whisper/ggml e o AAR sherpa não têm versão/commit upstream, receita de build
in-repo (o `.ps1` referenciado sumiu; a doc tem caminhos `Z:\`) nem checksums. O AAR nem
carrega versão no nome (`sherpa_onnx-nnapi-release.aar`). `strings libwhisper.so` mostra
um build local Windows (`Z:/Desenvolvimento/temp/whisper.cpp`, NDK clang 18). ~130 MB de
código nativo inverificável e não-reproduzível num APG público.
- **Cura**: manifest `THIRD-PARTY/versions.lock` com repo+commit+checksum de cada
  artefato; receita de build reproduzível (ver [05](./05-melhoria-continua.md#4)).

### MÉDIO — Histórico git reescrito
`.git/filter-repo/` presente; `commit-map` mostra **37 commits antigos → reescritos**; o
histórico público tem 13 commits desde "initial public release" (2026-06-12, tag
`v0.1.0`). Docs datadas de março provam ~3 meses de pré-história espremidos.
- Implicações: (a) provavelmente feito para purgar credenciais — sensato, e a árvore
  atual está limpa; (b) proveniência/autoria do código inicial é irrecuperável do repo
  público (ok legalmente, é trabalho próprio da Sufficit); (c) **clones pré-rewrite podem
  ainda ter o que foi purgado** → segredos purgados devem ser considerados
  rotacionados/queimados (reforça [04](./04-seguranca.md) C1).

### BAIXO — Marca e nomes
- "Sufficit" (rótulo, pacote) — consistente; MIT não concede nem reserva marca, sem
  aviso de trademark.
- "OpenClaw" — nome de ecossistema/terceiro referenciado por toda parte (o projeto CMake
  é literalmente `openclaw_gateway_native`). Se for marca externa, o uso aqui é
  nominativo/interoperabilidade (risco baixo).
- "Whisper" (OpenAI) e "ElevenLabs" — referências descritivas de interoperabilidade (uso
  nominativo, risco baixo).

### BAIXO — Higiene
- `local.properties` (caminho de SDK da máquina) está na árvore, corretamente gitignored.
- `.gitignore` tem o padrão excêntrico `.s*` que silenciosamente ignoraria futuros
  `.settings`/`.snapshots`.

## 5. Ativos de PI **próprios** da Sufficit (o que tem valor defensável)

O código Kotlin de primeira-parte é onde está a PI real. Estes são componentes
originais, não-triviais, dignos de proteção/destaque (todos sob `com.sufficit.ai.gateway`):

1. **Stack de wake word sem rede neural** (`audio/wake/`) — MFCC + DTW de subsequência
   com distância cosseno entre quadros (empiricamente 3,6× melhor separação que
   Euclidiana), três defesas ortogonais anti-falso-positivo (span energético contíguo,
   confirmação por 2 janelas, refratário) e **limiar auto-calibrado pela variância dos
   templates do próprio usuário**. Roda na CPU, dentro da thread de captura, zero
   download de modelo. É a peça mais autocontida e original.
2. **Verificação de locutor com zona-cinza adaptativa por duração** — threshold reduzido
   para segmentos curtos + banda cinza onde o trecho é encaminhado *com o score* para
   fusão server-side, em vez de rejeitado. Números tunados em campo.
3. **Fusão áudio-visual de liveness** — agregação de lip-activity (FaceMesh) por segmento
   correlacionada ao score de locutor: "voz do dono + lábios mexendo = presente; voz sem
   lábios = TV/gravação". Barato, opcional, nunca penaliza ausência.
4. **Quebrador de deadlock música/AGC** — dois scores de estabilidade (um com penalidade
   de fala para detecção, outro sem, só para redução de ganho) + absorção lenta do noise
   floor. Resolve um modo de falha real de sala barulhenta.
5. **Commit de fala de duplo-âncora** — silêncio clássico + âncora de "última
   transcrição com texto", mais detecção linguística de fala "inacabada" (caudas de
   conectores PT-BR que sobrevivem à pontuação do Whisper), esticando janelas 2,6–4×.
6. **Pre-roll ring com exclusão do gate de locutor** — recupera início de frase perdido
   pela latência do VAD, mantendo o embedding de locutor computado só sobre fala.
7. **AGC com ataque instantâneo/decaimento assimétrico + limitador tanh soft-knee**
   co-desenhado para não envenenar os detectores espectrais a jusante.
8. **Gramática de segmentação integrada a gestos** — indicador=segura-aberto,
   punho=finaliza+commit, mão-aberta=barge-in cujo contexto é enviado ao agente no
   próximo turno.

> Recomendação: se houver intenção de proteção formal (patente/segredo), os itens 1, 3 e
> 4 são os mais novos e defensáveis. No mínimo, documentá-los como invenções e manter
> registro de data de concepção (o histórico git reescrito apagou parte disso — os docs
> de atividade em `docs/` ajudam a reconstruir cronologia).

## 6. Resumo

- **Compatibilidade**: nenhuma incompatibilidade de licença. Risco puramente de
  atribuição, curável em horas.
- **Maior fraqueza de PI**: binários nativos prebuilt sem proveniência, versão ou
  checksum — é também o maior risco de cadeia de suprimentos.
- **Maior força de PI**: um punhado de algoritmos de processamento de sinal e fusão
  multimodal genuinamente originais, todos de primeira-parte.
