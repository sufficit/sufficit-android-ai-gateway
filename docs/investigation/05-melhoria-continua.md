# 05 — Melhoria Contínua

CI/CD, testes, observabilidade, reprodutibilidade de build e processo. O que existe, o
que falta, e como maturar.

---

## 1. CI/CD (estado atual)

Um único workflow: `.github/workflows/release.yml` ("Release APK").

- **Gatilhos**: push de tag `v*` + `workflow_dispatch`.
- **Passos**: checkout → JDK 17 (temurin) → setup-android → cache Gradle →
  `./gradlew :app:assembleDebug --no-daemon` → renomeia para
  `sufficit-ai-gateway-<TAG>.apk` → upload-artifact → `action-gh-release`
  (`generate_release_notes: true`).
- **Permissões**: `contents: write` (corretamente escopado).

### Problemas
1. **Publica APK debug-assinado.** Não existe `signingConfig` de release; o release
   buildType só tem `isMinifyEnabled=false`. Cada runner gera seu próprio
   `~/.android/debug.keystore` efêmero → **releases sucessivos não são
   upgrade-compatíveis** (`adb install -r` falha por assinatura divergente), o APK é
   `debuggable=true`, sem minificação/shrink.
2. **Sem CI em push/PR.** Nenhuma checagem de build no `main`, sem lint, sem teste. O
   README promete "`assembleDebug` precisa passar" para PRs, mas nada aplica.
3. **`versionCode`/`versionName` estáticos.** `versionCode 1`/`0.1.0` nunca subiram
   apesar de 12 commits de feature após o release inicial — conflita com a regra do
   próprio usuário de sempre subir versão antes de empacotar.
4. **`.gitignore` antecipa keystore** (`*.keystore`/`*.jks`/`keystore.properties`) mas
   nada está ligado.

### Recomendações de CI/CD
| Prioridade | Ação |
|-----------|------|
| P0 | Criar `signingConfig` de release com keystore em GitHub Secrets; publicar APK release assinado e estável (upgrade-compatível) |
| P0 | Workflow de PR/push: `assembleDebug` + `lint` + `test` obrigatórios antes de merge |
| P1 | Derivar `versionCode` do run number / contagem de commits; `versionName` da tag |
| P1 | Habilitar `isMinifyEnabled=true` + regras ProGuard/R8 no release (reduz APK e ofusca) |
| P2 | `dependencyGuard`/`gradle-versions` para alertar deps desatualizadas; SBOM |
| P2 | Assinar releases + gerar checksums dos APKs e dos `.so` |

## 2. Testes (estado atual: ZERO)

`find app/src -path "*test*"` → **nada**. Sem `app/src/test/`, sem `app/src/androidTest/`,
sem dependências de teste (nenhum JUnit/Espresso/compose-ui-test além do artefato debug
`ui-test-manifest`). `testInstrumentationRunner` está declarado mas aponta para uma
dependência que nem está no classpath. O QA de fato é: `assembleDebug` → `adb install -r`
num único Galaxy A51 → validação manual por logcat, mais um self-test in-app com
`res/raw/local_selftest.wav`.

Isto é o maior gap de melhoria contínua: dois dos bugs mais sérios (as funções
neutralizadas em [03](./03-engenharia.md#2)) seriam pegos pelo teste mais básico.

### Alvos de teste de maior valor (lógica pura/quase-pura, testável off-device)
1. `GatewaySettingsPatch` — validação/clamping (603 linhas de lógica pura). Pega as
   divergências de clamp já existentes.
2. `GatewaySettingsJson` — round-trip export/import.
3. `TranscriptWindowing` — dedup/merge/avanço de janela.
4. `TranscriptTextPipeline` — correções, e **regressão das funções neutralizadas**.
5. `VoiceChannelSkill` — matching de wake term coloquial ("xuxu"/"chu chu"),
   `routedText`/`shouldDispatch` (que hoje são ignorados).
6. `SpeakerContinuityTracker` — scoring e transições de estado.
7. `MfccExtractor` / `WakeWordDetector` — extração e DTW (com fixtures de áudio).
8. `GatewayApiServer` — auth + roteamento (NanoHTTPD é trivialmente testável off-device;
   pega token-length-leak, endpoints sem auth).
9. `ChatHistoryStore` — round-trip e trim de 200.

### Testes instrumentados (device/emulador)
- Ciclo de vida do Foreground Service (start/stop, recuperação de exceção).
- Fluxo de permissões.
- Classificador de gestos com frames sintéticos de landmarks.

## 3. Observabilidade

### O que existe (bom)
- `AudioDebugStore` — black-box de áudio com prune automático e one-liner adb de exfil.
- 4 loggers de histórico (transcrição, continuidade, espectro, chat).
- Badges de telemetria de dev na UI (mic gain, noise floor, probabilidade de locutor).

### O que falta
- **Rotação de logs** — 3 dos loggers crescem sem limite (ver
  [03](./03-engenharia.md#9) e [04](./04-seguranca.md#a6)). ~100 MB/dia de espectro.
  Adicionar rotação por tamanho/idade a todos.
- **Crash reporting** — nenhum (sem Crashlytics/Sentry). Numa escuta sempre-ligada,
  crashes silenciosos = escuta morta sem sinal. A "meia-morte" do loop de captura (R9)
  fica invisível.
- **Métricas de saúde** — sem heartbeat de "estou vivo e ouvindo" observável remotamente
  (o `/api/health` existe mas exige pull na LAN). Considerar métrica push para o OpenClaw.
- **Telemetria de erro estruturada** — erros de config/modelo corrompido são engolidos
  em silêncio (ver [03](./03-engenharia.md#10)).

## 4. Reprodutibilidade de build (fraqueza séria)

~130 MB de binários nativos (`libwhisper.so`, `libggml*.so`, `libggml-vulkan.so` 38 MB,
`libc++_shared.so`, e o AAR sherpa-onnx de 26 MB comprimido) são **prebuilts
versionados** sem:
- versão/commit upstream registrado,
- receita de build in-repo (o `tools/build-whisper-vulkan-android.ps1` referenciado pelo
  README **não existe**; a doc tem só caminhos `Z:\` de máquina Windows),
- checksums.

`strings libwhisper.so` revela o caminho de build `Z:/Desenvolvimento/temp/whisper.cpp`
e NDK clang 18 — um build local de máquina, não um checkout pinado. Um contribuidor não
consegue rebuildar; a cadeia de suprimentos é inverificável. Ver
[06-propriedade-intelectual](./06-propriedade-intelectual.md).

### Recomendações
| Prioridade | Ação |
|-----------|------|
| P0 | Recriar `tools/build-whisper-vulkan-android.*` reproduzível (Docker/CI), com commit pinado do whisper.cpp e do sherpa-onnx |
| P0 | Registrar versão + checksum de cada `.so`/AAR num manifest (`THIRD-PARTY/versions.lock`) |
| P1 | Mover binários grandes para Git LFS ou baixá-los em build a partir de releases pinados |
| P1 | Corrigir a doc `on-device-whisper-vulkan.md` (caminhos genéricos, passo JNI que ficou faltando) |

## 5. Processo e documentação

- **Docs de atividade**: excelente disciplina (11 docs timestamped + PLANs). Manter.
- **Docs perenes defasadas**: `roadmap.md` e `README.md` estão ~3-4 meses atrás do
  código; `android-testing.md` cita o pacote antigo `com.sufficit.openclaw.gateway`;
  README referencia `tools/*.ps1` inexistente e um `AGENTS.md` que não existe.
  - **Ação**: sincronizar roadmap/README com o estado real (usar
    [02-funcionalidades](./02-funcionalidades-existentes.md) como fonte).
- **Regra de 400 linhas** documentada em `engineering-guidelines.md` mas violada pela
  god class (5.038) e pelo recognizer (1.058).
- **Regra de milestone/checkpoint e sempre-subir-versão** (memórias do usuário) não
  refletidas no CI.

## 6. Roteiro de maturação (ordem sugerida)

1. **Estancar sangramento**: rotacionar segredos, corrigir JNI, gatear áudio rolling,
   rotação de logs. (P0 segurança/operacional)
2. **Rede de segurança**: assinatura de release estável + CI de PR + primeira suíte de
   testes unitários dos objetos puros (pega os no-ops).
3. **Reprodutibilidade**: receita de build nativa + manifest de versões/checksums +
   NOTICE de terceiros (ver [06](./06-propriedade-intelectual.md)).
4. **Refatoração guiada por teste**: quebrar a god class atrás dos testes (ver
   [07](./07-ideias-e-roadmap.md)).
5. **Observabilidade**: crash reporting + heartbeat de saúde remoto.
