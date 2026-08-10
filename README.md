# mdblist hub — APKs

Tudo que empacota o mdblist hub em Android. O site em si (Angular) vive no
repo irmão [`../mdblist-hub`](../mdblist-hub) e não sabe que esta pasta existe.

```
android/          Wrapper Capacitor: WebView em cima do build do site.
android-native/   App nativo em Kotlin para Android TV (libVLC, sem WebView).
capacitor.config.ts   Aponta webDir para ../mdblist-hub/dist/mdblist-hub/browser.
```

Os dois projetos Android são independentes um do outro — veja
[`android-native/README.md`](android-native/README.md) para o porquê de existirem
os dois.

## Wrapper Capacitor (`android/`)

Precisa do build do site pronto antes de sincronizar:

```bash
npm install                 # instala @capacitor/cli e @capacitor/android
npm run android:apk         # build do site (../mdblist-hub) + sync + gradlew assemble (mobile e tv)
npm run android:apk:mobile  # só o flavor mobile
npm run android:apk:tv      # só o flavor tv
```

`android:sync` roda `npm run build` dentro de `../mdblist-hub` antes de `cap
sync`, então o repo do site precisa estar clonado ao lado deste
(`GitHub/mdblist-hub` e `GitHub/mdblist-hub-apk` como pastas irmãs).

## App nativo (`android-native/`)

Não depende do site nem do Capacitor — Kotlin puro, libVLC embutido.

```bash
cd android-native
./gradlew assembleDebug     # APKs por ABI em app/build/outputs/apk/debug
./gradlew assembleRelease
```

Precisa de JDK 17+ e do Android SDK. Detalhes de arquitetura, workers e cache
em [`android-native/README.md`](android-native/README.md).
