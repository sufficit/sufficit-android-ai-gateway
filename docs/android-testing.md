# Instalação e teste em Android

## Melhor forma de me dar acesso para instalar e testar

O caminho mais confiável é **ADB por USB**, não SSH root no Android.

## Método recomendado

### 1. Ativar modo desenvolvedor

No Android:

- abrir `Configurações`
- tocar várias vezes em `Número da versão`
- ativar `Opções do desenvolvedor`

### 2. Ativar depuração USB

- entrar em `Opções do desenvolvedor`
- ativar `Depuração USB`

### 3. Conectar por USB no seu PC

Depois disso, o fluxo ideal de trabalho é:

- build do APK aqui no PC
- instalação com `adb install`
- coleta de logs com `adb logcat`

## Por que ADB é melhor que SSH root

- padrão do ecossistema Android
- mais estável para instalar APK
- melhor para log e debug
- não exige root
- evita mexer em segurança do aparelho

## Root / SSH

Em Android comum, SSH root não é o caminho normal.

Só faz sentido se:

- o aparelho estiver rooteado
- existir servidor SSH instalado
- você quiser administração avançada do sistema

Mesmo assim, para desenvolver app Android, **ADB continua sendo melhor**.

## Apps úteis no Android

Se você quiser suporte operacional extra no aparelho:

- Termux
- Termux:API

Mas isso ajuda mais para debug local do aparelho. Não substitui bem o ADB para instalar e depurar o app.

## O que eu preciso de você para testar quando o app existir

1. aparelho Android com cabo USB funcionando
2. depuração USB ativada
3. confirmação da autorização RSA do PC no aparelho

Com isso, eu consigo:

- instalar APK
- atualizar versões
- abrir logs
- validar permissões e comportamento do serviço

## Comando típico

Exemplos quando o projeto estiver pronto:

```powershell
adb devices
adb install -r app-debug.apk
adb logcat
```

## Observação

Se você preferir sem cabo, existe `adb tcpip`, mas para o início o melhor é USB mesmo.

## Estado validado em 2026-03-17

Ambiente validado neste PC:

- `adb` instalado via `Google.PlatformTools`
- Android SDK local em `Z:\Android\sdk`
- device conectado: Samsung Galaxy A51 `SM_A515F`
- serial ADB usado nos testes: `RX8N60B5CZM`

Fluxo já validado:

```powershell
.\gradlew.bat assembleDebug
adb -s RX8N60B5CZM install -r app\build\outputs\apk\debug\app-debug.apk
adb -s RX8N60B5CZM shell am start -n com.sufficit.openclaw.gateway/.MainActivity
```

Observação operacional:

- o serviço de áudio não deve ser iniciado por ADB externo porque ele é `exported=false`
- o caminho correto é abrir o app e iniciar a escuta pela própria UI
- para acelerar testes locais, a permissão de microfone pode ser concedida por:

```powershell
adb -s RX8N60B5CZM shell pm grant com.sufficit.openclaw.gateway android.permission.RECORD_AUDIO
```
