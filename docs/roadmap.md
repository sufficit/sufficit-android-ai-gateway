# Roadmap

## Fase 0 - Arquitetura

- [x] definir objetivo do app
- [x] definir topologia Android -> Raspberry -> Whisper -> OpenClaw
- [x] definir escolha por app nativo com `ForegroundService`
- [x] definir protocolo inicial por WebSocket

## Fase 1 - Esqueleto Android

- [x] projeto Android em Kotlin
- [x] tela principal mínima
- [x] permissão de microfone
- [x] serviço foreground
- [x] indicador de status

## Fase 2 - Captura

- [x] `AudioRecord`
- [x] captura PCM 16 kHz mono
- [ ] buffer circular curto
- [x] VAD simples
- [x] telemetria básica de nível RMS/pico na notificação

## Fase 3 - Transporte

- [ ] cliente WebSocket
- [ ] handshake do dispositivo
- [ ] envio de segmentos
- [ ] ack e retry simples
- [x] configuração persistida de endpoint local

## Fase 4 - Gateway local

- [ ] listener simples na Raspberry
- [ ] persistência temporária de segmentos
- [ ] integração com Whisper
- [ ] entrega de texto ao OpenClaw

## Fase 5 - Operação

- [ ] otimização de bateria
- [ ] autostart opcional
- [ ] logs locais
- [x] documentação de instalação no Android
