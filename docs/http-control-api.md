# HTTP Control API

Servidor HTTP embarcado que expõe **todas** as funções e configurações do
gateway — inclusive participar da conversa — por comandos HTTP. Roda dentro do
foreground service de áudio (disponível enquanto a escuta está ativa).

## Habilitar

Pela tela de configuração (Geral → "API HTTP de controle"):

- Ativar API (gera um token automaticamente na primeira vez)
- Expor na rede local (LAN) ou só `localhost`
- Porta (padrão `8765`)
- Copiar/gerar token

A API fica **desligada por padrão** e **recusa subir sem token**.

## Autenticação

Toda rota exige o token (exceto `GET /api/health`), por qualquer uma das vias:

- `Authorization: Bearer <token>`
- `X-Api-Token: <token>`
- `?token=<token>` na query

Token inválido/ausente → `401`. Comparação em tempo constante.

## Arquitetura

A API e a UI compartilham a mesma superfície de comandos
(`GatewayApiActions`, implementada por `RoomAudioForegroundService`): um
comando por toque na tela, gesto da câmera ou HTTP executa exatamente a mesma
ação. Estado é lido dos flows do `GatewayRuntime`.

## Endpoints

| Método | Rota | Corpo | Efeito |
|--------|------|-------|--------|
| GET | `/api/health` | — | Liveness (sem auth) |
| GET | `/api/status` | — | Estado: escuta, transcrição, resposta, fila, gesto ativo, score do locutor |
| GET | `/api/config` | — | Configuração completa (formato `config.json`) |
| PATCH/POST/PUT | `/api/config` | JSON seccionado, plano ou misto | Aplica e persiste; retorna chaves aplicadas/ignoradas + efeitos |
| GET | `/api/chat` | — | Histórico de conversa |
| GET | `/api/transcripts` | — | Transcrição atual/anterior/recentes |
| POST | `/api/chat/clear` | — | Limpa o histórico exibido |
| POST | `/api/listen/start` | — | Inicia/retoma a escuta |
| POST | `/api/listen/stop` | — | Para a escuta (standby se houver palavra de ativação) |
| POST | `/api/standby` | — | Força o modo de espera |
| POST | `/api/wake` | — | Sai do standby e acorda a tela |
| POST | `/api/say` | `{"text": "..."}` | Fala o texto pelo TTS |
| POST | `/api/conversation` | `{"text": "...", "speak": true}` | **Participa da conversa**: injeta turno do usuário → agente; resposta assíncrona (ver `/api/chat`) |
| POST | `/api/interrupt` | — | Interrompe a fala do assistente |
| POST | `/api/gesture` | `{"id": "index_up\|fist\|open_hand"}` | Dispara o gesto como se viesse da câmera |
| POST | `/api/finalize` | — | Finaliza o segmento de fala em captura e envia |
| POST | `/api/wakeonlan` | `{"mac":"AA:BB:CC:DD:EE:FF","broadcast":"192.168.1.255","port":9,"repeat":3}` | Envia magic packet Wake-on-LAN pela rede local do telefone |
| POST | `/api/wakeonlan/discover` | `{"probe":true}` | Descobre vizinhos IPv4 com MAC visível na LAN; candidatos a cadastro WOL |

Respostas são sempre JSON. Erros: status HTTP + `{"error": "...", "detail": "..."}`.

## Exemplos

```bash
BASE=http://<ip-do-aparelho>:8765
TOKEN=<seu-token>

# Estado
curl $BASE/api/status -H "Authorization: Bearer $TOKEN"

# Participar da conversa (com fala da resposta)
curl -X POST $BASE/api/conversation \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"text": "que horas são?", "speak": true}'

# Participar sem falar a resposta (só texto, ver /api/chat)
curl -X POST $BASE/api/conversation \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"text": "anote: comprar café", "speak": false}'

# Falar um texto arbitrário
curl -X POST $BASE/api/say \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"text": "chegou uma encomenda"}'

# Alterar configuração (seccionado, igual config.json)
curl -X PATCH $BASE/api/config \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"audio": {"gain": 2.0}, "voiceChannel": {"followUpSecs": 20}}'

# Alterar configuração (chaves planas)
curl -X PATCH $BASE/api/config \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"microphoneGain": 1.5, "vadThreshold": 0.02}'

# Controlar escuta e gestos
curl -X POST $BASE/api/listen/stop  -H "Authorization: Bearer $TOKEN"
curl -X POST $BASE/api/gesture -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"id": "fist"}'

# Ligar um computador da mesma rede por Wake-on-LAN. `broadcast` é opcional:
# sem ele, o app usa 255.255.255.255 e os broadcasts das interfaces IPv4 ativas.
curl -X POST $BASE/api/wakeonlan \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"mac": "AA:BB:CC:DD:EE:FF", "broadcast": "192.168.1.255", "port": 9, "repeat": 3}'

# Descobrir vizinhos ativos. A sonda UDP vazia só provoca resolução ARP na
# sub-rede; `probe:false` apenas devolve a tabela ARP já existente.
curl -X POST $BASE/api/wakeonlan/discover \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"probe": true}'
```

## Notas

- **Segurança**: bind em LAN expõe a API na rede local — o token é a única
  barreira. Use um token forte (o app gera 24 bytes aleatórios) e prefira
  `localhost` quando possível.
- **Mudar a config da API** (porta/token/enabled/bind) reinicia o servidor;
  o restart é adiado ~250 ms para a resposta do próprio comando sair antes do
  socket cair.
- **`speak: false`** suprime a fala apenas da próxima resposta do agente.
- A API só responde enquanto o serviço de áudio está vivo. `POST
  /api/listen/start` garante a captura ativa.
- **Wake-on-LAN** envia o pacote pela mesma rede do telefone. A confirmação da
  rota significa que o UDP foi enviado, não que o computador já iniciou: a
  placa de rede/BIOS do destino precisa estar configurada para WOL, e telefone
  e computador precisam alcançar o mesmo domínio de broadcast (ou usar um
  relay configurado na rede).
- **Descoberta WOL** não consegue determinar pela rede se a BIOS/NIC de outro
  aparelho aceita magic packet. Ela retorna vizinhos vistos por ARP e, nos
  Androids que bloqueiam ARP, respostas NetBIOS NODE STATUS (IP, MAC,
  interface, broadcast e `source`) como candidatos; `wakeOnLanStatus` fica
  `unknown` até um teste de wake ser confirmado.

## Ferramenta do agente: `wakeonlan`

Quando o OpenClaw recebe uma chamada explícita, o cliente anuncia essa tool no
catálogo `availableTools`. O agente pode executá-la pela conexão de saída:

```json
{
  "actions": [
    {
      "tool": "wakeonlan",
      "mac": "AA:BB:CC:DD:EE:FF",
      "broadcast": "192.168.1.255",
      "port": 9,
      "repeat": 3
    }
  ]
}
```

`mac` é obrigatório; `broadcast` é opcional; `port` aceita `1..65535` e
`repeat` aceita `1..5` (padrão: `9` e `3`). O resultado — ou uma falha de
validação/rede — fica registrado como bolha de sistema no chat.

## Ferramenta do agente: `discover_wol_devices`

O agente pode pedir uma descoberta local antes de cadastrar um computador:

```json
{
  "actions": [
    {
      "tool": "discover_wol_devices",
      "probe": true
    }
  ]
}
```

O app registra os candidatos encontrados em uma bolha de sistema. `probe` é
opcional e vale `true` por padrão; a descoberta é limitada a no máximo 254
hosts por interface para evitar uma varredura ampla da rede.
