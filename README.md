# open Stremio — Android TV

App nativo em Kotlin para Android TV: player **Media3/ExoPlayer**, addons no
protocolo Stremio, metadados em Room e interface Compose para D-pad.

**O projeto Gradle não fica na raiz deste repositório — fica em
[`android-native/`](android-native/).**

```bash
cd android-native
./gradlew assembleDebug     # APKs por ABI em app/build/outputs/apk/debug
./gradlew assembleRelease   # com R8
./gradlew installDebug      # instala no dispositivo/emulador conectado
```

Precisa de JDK 17+ e do Android SDK. O `local.properties` (não versionado) deve
apontar para o seu SDK.

Arquitetura, decisões do player e o modelo de addons estão em
[`android-native/README.md`](android-native/README.md).

Existe um app irmão para celular, com o mesmo núcleo e a interface refeita para
toque: [open-stremio-mobile](https://github.com/sauliiin/open-stremio-mobile).
Os módulos `core/` e `player/` **já não são idênticos** entre os dois — o app de
celular tem download offline e o de TV não — então uma mudança compartilhada
precisa ser aplicada nos dois, não copiada de um para o outro.

## O que mais existe neste repositório

```
android-native/   O app de TV. É isto que este repositório é.
android/          Wrapper Capacitor de um projeto diferente — ver abaixo.
```

`android/`, `capacitor.config.ts`, `package.json` e `database.rules.json` são
resíduos de quando este repositório empacotava o site Angular
[`mdblist-hub`](https://github.com/sauliiin/mdblist-hub) como WebView. Nada
disso participa do build do app nativo, e o wrapper só funciona com aquele repo
clonado como pasta irmã:

```bash
npm install
npm run android:apk         # build do site + cap sync + gradlew assemble
```

`database.rules.json` continua sendo usado: são as regras do Realtime Database
que o app nativo consome (cada usuário só lê e escreve sob o próprio `uid`).

## Licença

GPL-3.0 — ver [LICENSE](LICENSE).

O APK distribui `android-native/player/libs/media3-decoder-ffmpeg-1.11.0.aar`,
uma build local do módulo `decoder_ffmpeg` do Media3. O FFmpeg que ele embute é
LGPL/GPL e o Google não o publica no Maven por causa disso; as obrigações dessa
licença acompanham qualquer redistribuição do APK.
