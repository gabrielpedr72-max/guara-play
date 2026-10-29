#!/usr/bin/env bash
# Gera o APK do Guará Play no Linux, SEM Android Studio.
#
#   SERVER_URL=https://painel.seudominio.com.br ADMIN_PIN=4821 ./build-apk.sh
#   (opcional: ./build-apk.sh --install   para instalar por ADB no aparelho conectado)
#
# Na primeira vez baixa ~1 GB (SDK do Android + Gradle) e leva alguns minutos.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
SDK="${ANDROID_HOME:-$HOME/guara-android-sdk}"
CACHE="$HOME/.cache/guara-build"
mkdir -p "$SDK" "$CACHE"
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# 1) Java (17 a 21)
need_java=1
if command -v java >/dev/null 2>&1; then
  v=$(java -version 2>&1 | sed -n '1s/.*"\([0-9]*\)[."].*/\1/p')
  if [ "${v:-0}" -ge 17 ] && [ "${v:-0}" -le 21 ]; then need_java=0; fi
fi
if [ "$need_java" = 1 ]; then
  say "Instalando o Java 17 (vai pedir sua senha de administrador)"
  sudo apt-get update -y || echo "AVISO: o apt-get update reclamou de algum repositório; tentando instalar mesmo assim."
  sudo apt-get install -y openjdk-17-jdk
fi
command -v java >/dev/null 2>&1 || { echo "ERRO: o Java não foi instalado. Rode: sudo apt-get install -y openjdk-17-jdk"; exit 1; }
for t in unzip curl; do command -v $t >/dev/null 2>&1 || { sudo apt-get update -y; sudo apt-get install -y $t; }; done

# 2) Ferramentas do Android SDK
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  say "Baixando as ferramentas do Android SDK"
  curl -fL -o "$CACHE/cmdline.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf "$CACHE/ct"; unzip -q "$CACHE/cmdline.zip" -d "$CACHE/ct"
  mkdir -p "$SDK/cmdline-tools"; rm -rf "$SDK/cmdline-tools/latest"
  mv "$CACHE/ct/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
say "Instalando componentes do SDK (aceitando as licenças do Android)"
yes | "$SM" --licenses >/dev/null 2>&1 || true
"$SM" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 3) Gradle 8.7
G="$CACHE/gradle-8.7"
if [ ! -x "$G/bin/gradle" ]; then
  say "Baixando o Gradle 8.7"
  curl -fL -o "$CACHE/gradle.zip" https://services.gradle.org/distributions/gradle-8.7-bin.zip
  unzip -q -o "$CACHE/gradle.zip" -d "$CACHE"
fi

# 4) Chave de assinatura (guarde este arquivo! sem ela não dá para atualizar aparelhos já instalados)
if [ ! -f "$ROOT/guara.keystore" ]; then
  say "Criando a chave de assinatura guara.keystore (FAÇA BACKUP dela)"
  keytool -genkeypair -keystore "$ROOT/guara.keystore" -storepass guaraplay -keypass guaraplay \
    -alias guara -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Guara Play"
fi

# 5) Compilar
echo "sdk.dir=$SDK" > "$ROOT/local.properties"
[ -n "${ADMIN_PIN:-}" ] || echo "AVISO: ADMIN_PIN não definido; o PIN de manutenção será 1234. Troque!"
[ -n "${SERVER_URL:-}" ] || echo "AVISO: SERVER_URL não definido; o app vai perguntar o endereço na 1ª abertura."
say "Compilando o APK"
"$G/bin/gradle" --no-daemon assembleDebug -PserverUrl="${SERVER_URL:-}" -PadminPin="${ADMIN_PIN:-}"

cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/guara-play.apk"
say "PRONTO: $ROOT/guara-play.apk"

if [ "${1:-}" = "--install" ]; then
  say "Instalando no aparelho conectado"
  "$SDK/platform-tools/adb" install -r "$ROOT/guara-play.apk"
fi
