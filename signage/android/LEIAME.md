# Guará Play · app Android (TV Box)

## O que o app faz
- **Abre sozinho ao ligar** e fica sempre na frente. Se cair, reabre em segundos; se sair da tela por mais de 20 s, o vigia traz de volta.
- **Funciona sem internet.** O app baixa TODAS as mídias da lista de reprodução (retomando downloads interrompidos) e só troca a lista que está tocando quando tudo estiver no aparelho. Sem rede, continua tocando a lista atual; quando a rede volta, recebe as novidades sozinho.
- **Modo administrador (quiosque):** com o app configurado como "proprietário do dispositivo", o Android trava o player na tela (sem Home, sem recentes, sem barra), bloqueia desinstalar e restaurar de fábrica, e mantém o Guará Play como tela inicial.

## 1. Gerar o APK (escolha UMA forma)

**A) No seu Linux, sem Android Studio** (Ubuntu/Debian; baixa ~1 GB na primeira vez):
```
cd android
SERVER_URL=https://painel.seudominio.com.br ADMIN_PIN=4821 ./build-apk.sh
```
O APK sai em `android/guara-play.apk`. O script cria `guara.keystore` (a chave de assinatura): **faça backup dela**; sem ela não dá para atualizar aparelhos já instalados. Com a TV Box conectada por ADB, `./build-apk.sh --install` já instala.

**B) Na nuvem, sem instalar nada** (GitHub, gratuito):
1. Crie um repositório privado no GitHub e envie o CONTEÚDO da pasta `android` (inclusive a pasta oculta `.github`).
2. Em Settings > Secrets and variables > Actions: crie o segredo `ADMIN_PIN` (seu PIN) e a variável `SERVER_URL`. Para atualizações funcionarem no futuro, crie também o segredo `KEYSTORE_B64` (saída de `base64 -w0 guara.keystore`).
3. Aba **Actions > Gerar APK > Run workflow**. Ao terminar, baixe o APK em "Artifacts".

**C) Android Studio:** abra a pasta `android` e use Build > Build APK(s).

## 2. Instalar na TV Box
Pendrive, app *Downloader* (apontando para um link do seu servidor) ou `adb install app-debug.apk`.
Abra o app uma vez e, se pedir, permita **"Exibir sobre outros apps"**.

## 3. Ativar o modo administrador (uma vez por aparelho)
Precisa de um computador com o **ADB** (platform-tools do Android).
1. Na TV Box: Configurações > Sobre > toque 7× em "Número da versão" > Opções do desenvolvedor > ative **Depuração USB** (ou ADB pela rede).
2. **A TV Box não pode ter conta Google/usuário extra cadastrado.** Se tiver, remova em Configurações > Contas (ou restaure de fábrica e pule o login).
3. No computador:
   ```
   adb connect IP-DA-TVBOX:5555        (se for pela rede)
   adb shell dpm set-device-owner br.com.guaraplay.player/.AdminReceiver
   ```
   Deve responder "Success". Se aparecer "already some accounts on the device", volte ao passo 2.
4. Reinicie a TV Box. O app abre travado, sem sair.
5. Depois, **desligue a Depuração USB** nas opções do desenvolvedor, para ninguém conseguir desfazer por ADB.

## Modo manutenção (você, técnico)
Aperte **Menu** no controle, ou **Voltar 5 vezes seguidas**, e digite o PIN. Opções:
- **Informações:** servidor, pareamento, última sincronização, arquivos e espaço, modo administrador.
- **Endereço do servidor.**
- **Configurações do Android (Wi-Fi…):** sai do travamento até o app ser reaberto.
- **Desativar modo administrador:** devolve o aparelho ao normal (para desinstalar, mover ou restaurar).

## O que NÃO dá para impedir
Tirar a TV Box da tomada, trocar a entrada HDMI/cabo, usar o botão físico de reset (se o aparelho tiver) ou acessar por ADB enquanto a depuração estiver ligada. Nenhum app consegue bloquear isso.

## Atualizar o app depois
`adb install -r app-debug.apk` (mantém tudo). O app reabre sozinho.
