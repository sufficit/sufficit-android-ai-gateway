# 04 — Segurança e Privacidade

Auditoria de segurança do commit `c6161a8`. Severidades: **CRÍTICO / ALTO / MÉDIO /
BAIXO**. Este é um app **sempre-ligado, com microfone e câmera**, que fala com um
servidor remoto e expõe uma API de controle — o modelo de ameaças é sério.

---

## 1. Modelo de ameaças (quem pode atacar o quê)

| Vetor | Ativo em risco | Pré-condição |
|-------|----------------|--------------|
| Quem tem o APK | Todos os tokens de produção embarcados | baixar/extrair o APK |
| Qualquer host na LAN | Screenshot, foto, config (com segredos), fala, injeção de conversa | API habilitada (default bind `0.0.0.0`) + token |
| Página web maliciosa | POSTs state-changing (DNS-rebinding) | usuário visita a página, API na LAN |
| Servidor OpenClaw (ou quem o comprometer) | Reescrever qualquer config, ligar API, tirar foto, mudar tokens | conexão já estabelecida (por design) |
| Quem acessa backup do device | Tokens, áudio, chat, perfil de voz, gravações de wake word | `adb backup` ou Auto Backup na nuvem |
| App co-residente (Android antigo) | Fotos/screenshots em armazenamento externo | device pré-scoped-storage |

---

## 2. Achados

### CRÍTICO

**C1 — Credenciais de produção reais empacotadas no APK.**
`app/src/main/assets/config.json` existe na árvore de trabalho com tokens vivos:
`gatewayToken`, `deviceToken`, `whisperToken` (`RKHENtK…`) e um `sessionKey` que vaza um
ANDROID_ID de device, apontando para `openclaw.sufficit.com.br` /
`whisper.sufficit.com.br`. O arquivo é gitignored e **não** está versionado — mas
**assets são empacotados em todo APK** e são extraíveis por qualquer um com o APK
(`unzip`). Qualquer pessoa que baixe um release tem acesso total ao gateway e ao Whisper.
- **Ação**: rotacionar os três tokens **agora** (considerá-los queimados — pré-rewrite
  clones podem tê-los). Nunca semear credenciais por asset; usar pareamento no primeiro
  boot ou managed config. Ver [07](./07-ideias-e-roadmap.md).

**C2 — Caminho local whisper.cpp derruba o serviço (disponibilidade).**
Não é um ataque externo, mas é uma falha de disponibilidade crítica: o mismatch de
pacote no JNI (`04`/[03](./03-engenharia.md)) faz o ramo LOCAL+CPU+não-bundle lançar
`UnsatisfiedLinkError` → `handleFatalError` → `stopSelf()`. Um usuário que selecione a
combinação errada de modo derruba a escuta.

### ALTO

**A1 — API HTTP: bind em toda a LAN por padrão.**
`DEFAULT_API_BIND_ALL_INTERFACES = true` (`GatewaySettings.kt:738`). Ao habilitar a API,
ela sobe em `0.0.0.0` a menos que o usuário desligue LAN manualmente. A doc
(`http-control-api.md`) diz "prefira localhost", mas o default é o oposto.

**A2 — API HTTP em texto puro, sem TLS.**
NanoHTTPD é HTTP puro; `usesCleartextTraffic="true"` no manifest. Token bearer e todos os
payloads (screenshots, fotos, transcrições, **config com os tokens do OpenClaw via
`GET /api/config`**) trafegam na LAN em claro. Token aceito por query string `?token=`
(`GatewayApiServer.kt:239`) vaza em proxies/logs/histórico.

**A3 — `GET /api/config` vaza todos os segredos.**
Retorna a config inteira incluindo `gatewayToken`, `deviceToken`, `sessionKey`,
`whisperToken` **e o próprio `apiToken`** (`GatewayApiServer.kt:56` →
`toConfigJson()`). Combinado com A1/A2 e `Access-Control-Allow-Origin: *` (`:282`),
qualquer página na origem alcançável lê tudo assim que tem o token.

**A4 — Endpoints de screenshot e foto da câmera expostos.**
`GET|POST /api/screenshot` retorna PNG da janela; `POST /api/photo` dispara a câmera
frontal/traseira. Qualquer cliente LAN com o token **fotografa e captura tela
silenciosamente**. `/api/screenshot` via GET com `?token=` pode ser embutido como
`<img src>`. Mitigação parcial: screenshot exige Activity em foreground, photo exige
câmera ativa.

**A5 — `allowBackup=true` sem regras de backup.**
`AndroidManifest.xml:16`, sem `dataExtractionRules`/`fullBackupContent`. `adb backup`
(pré-Android 12) e Auto Backup na nuvem incluem `filesDir/config.json` (todos os
tokens), `chat_history.json`, `history/`, `speaker_voice/` (embeddings da voz do dono),
`wake_word/` (**gravações PCM cruas da voz do usuário**), `installation_id`.
- **Ação**: adicionar `android:dataExtractionRules` excluindo `files/`, ou
  `allowBackup=false`.

**A6 — Gravação incondicional de áudio ambiente em disco.**
`AudioDebugStore.appendRolling` é chamado **incondicionalmente** por chunk
(`RoomAudioForegroundService.kt:866`), gravando WAVs pós-ganho de **tudo** que o
microfone ouve — inclusive segmentos descartados. Retenção de 5 min mitiga o volume, mas
é gravação contínua de conversas de sala em claro, num app sempre-escutando. Existe a
flag `settings.development` que já governa os outros knobs de debug (`:2003`) mas **não**
gate isto.
- **Ação**: gatear `appendRolling` por `development`; documentar claramente no consentimento.

### MÉDIO

**M1 — CORS `*` + sem validação de Origin/Host → DNS-rebinding.**
`Access-Control-Allow-Origin: *` global. Sem checagem de Origin/Host, uma página que o
usuário visite pode religar (rebind) ao IP do device e disparar POSTs state-changing
(`/api/say`, `/api/conversation`, `/api/config`, `/api/photo`). O token é a única
barreira; sem defesa em profundidade.

**M2 — DoS pós-auth por alocação ilimitada.**
`readJsonBody` (`:255`) lê `Content-Length` e faz `ByteArray(length)` **sem cap** antes
de ler o stream. Cliente autenticado força OOM. Exige token → médio.

**M3 — Controle remoto total sem escopo de capacidade.**
Um único token concede tudo: ler segredos, reescrever config (inclusive virar
`apiBindAllInterfaces`, trocar `apiToken`, apontar OpenClaw/Whisper para host
atacante), falar, injetar conversa. Sem separação read-only/admin. Token comprometido =
tomada total de device + conta.

**M4 — Servidor remoto pode reescrever qualquer config.**
Respostas do agente OpenClaw (`settingsPatch`, `:4194`) e a ferramenta `"config"`
(`:4149`) passam pelo mesmo patch irrestrito. O servidor pode silenciosamente habilitar
`apiEnabled`, mudar `apiToken` ou repontar `openClawServerAddress`/tokens. É "por
design" (conexão de saída), mas é controle remoto-equivalente. Sem allowlist de chaves
patcháveis remotamente.

**M5 — Segredos em texto puro em repouso (sem Keystore).**
`openClawGatewayToken`, `openClawDeviceToken`, `openClawSessionKey`, `whisperAuthToken`,
`apiToken` serializados literalmente em `filesDir/config.json`. Nenhum uso de Android
Keystore/EncryptedSharedPreferences (dependência sequer presente). Também transitam em
`rememberSaveable` (Bundle de estado salvo, `GatewaySettingsState.kt:175`) e no export
de backup em claro (`cacheDir/exports/config.json`).

**M6 — FileProvider expõe a raiz de filesDir.**
`res/xml/file_paths.xml:13-15` tem `files-path path="."` chamado
`agent_media_internal` → qualquer URI concedido pode endereçar **qualquer** arquivo em
filesDir, inclusive `config.json` com os tokens.

### BAIXO

**B1 — `constantTimeEquals` vaza o comprimento do token.**
`:286-291` retorna cedo se `a.length != b.length`; o loop XOR é constant-time só para
comprimentos iguais. Impacto baixo para token fixo de 48 hex, mas não é totalmente
timing-safe como a doc afirma.

**B2 — Logs de identidade.**
Migração de sessionKey e caminho de modelo logam valores (`GatewaySettings.kt:215`).

**B3 — Cleartext global.**
`usesCleartextTraffic="true"` app-wide em vez de um `networkSecurityConfig` escopado só
para o endpoint LAN.

**B4 — `usesCleartextTraffic` também permite `ws://` degradado no OpenClaw.**
O default é `wss://`, mas se o usuário digitar `ws://` é honrado (`GatewaySettings.kt:605`).

---

## 3. O que está BEM feito em segurança

- API **desligada por padrão** e **recusa iniciar sem token** (`:309`).
- Token gerado com `SecureRandom` 24 bytes → 48 hex.
- Comparação de token em tempo (quase) constante.
- Blank string em patch não consegue apagar tokens/URLs (exceto campos explicitamente
  permitidos) — evita "cegar" a config por acidente.
- Canal OpenClaw é **`wss://` por padrão** (TLS), só degrada se o usuário forçar.
- Sem vetor de path traversal: `label` de screenshot/foto é só metadado, nunca caminho;
  patch de config só mapeia chaves conhecidas (allowlist). Superfície de injeção baixa.
- Histórico git reescrito para purgar credenciais (37→13 commits); árvore atual limpa de
  segredos versionados.

---

## 4. Plano de remediação priorizado

| Prioridade | Ação | Esforço |
|-----------|------|---------|
| P0 | Rotacionar os 3 tokens de produção; remover `assets/config.json` do build (semear por pareamento) | baixo |
| P0 | Corrigir mismatch JNI (C2/disponibilidade) | baixo |
| P1 | `apiBindAllInterfaces` default `false`; adicionar checagem de `Origin`/`Host` allowlist (anti-rebinding) | baixo |
| P1 | Remover segredos de `GET /api/config` (ou exigir flag explícita e mascarar) | baixo |
| P1 | `dataExtractionRules` excluindo `files/`; ou `allowBackup=false` | baixo |
| P1 | Gatear gravação de áudio rolling por `development` | trivial |
| P2 | TLS na API embarcada (cert autoassinado + pinning no cliente), ou só localhost | médio |
| P2 | Escopo de token (read-only vs admin); allowlist de chaves patcháveis remotamente | médio |
| P2 | Cap de `Content-Length`; migrar segredos para EncryptedSharedPreferences/Keystore | médio |
| P3 | Estreitar `file_paths.xml`; `networkSecurityConfig` escopado; parar de logar identidade | baixo |
