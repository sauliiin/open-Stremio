# Open Stream — Android TV

App nativo em Kotlin para Android TV: player **Media3/ExoPlayer**, interface no
vocabulário do **Kodi/Estuary**, **workers dedicados** de metadados e **cache
persistente** em Room.

Não é o app web empacotado. Não há WebView em lugar nenhum — o projeto Capacitor
em [`../android`](../android) e o site em
[`../../mdblist-hub/src`](../../mdblist-hub/src) continuam existindo e
independentes deste.

```bash
cd android-native
./gradlew assembleDebug     # APKs por ABI em app/build/outputs/apk/debug
./gradlew assembleRelease   # com R8; ~5 MB por ABI
```

Precisa de JDK 17+ e do Android SDK. O `local.properties` aponta para
`/opt/android-sdk`.

### Firebase e login Google

O APK usa o cliente Android `mdblist_hub.apk.S84` do projeto
`safevault-fcbdc`. Antes de testar o login pela primeira vez:

1. ative **Authentication > Google** no console Firebase e cadastre os SHA-1
   das chaves que assinam debug/release (nesta máquina, o SHA-1 de debug é
   `9C:A4:26:54:0C:38:24:1A:78:89:4B:83:4C:65:28:1B:E0:13:0F:20`);
2. baixe novamente o `google-services.json` (o arquivo atualizado precisa ter
   um cliente OAuth web/default) e coloque em `app/google-services.json`;
3. crie o Realtime Database e ajuste `FIREBASE_BASE` em
   `core/network/.../ApiConfig.kt` caso a instância não use a URL padrão;
4. publique as regras privadas da raiz do repositório com
   `firebase deploy --only database`.

O login do app é Google. A chave MDBList é vinculada depois da primeira entrada
porque a API da MDBList continua exigindo uma credencial pessoal; ela é salva
em `/users/{uid}/profile` e só o próprio UID pode lê-la. A sincronização de
addons usa `/users/{uid}/addons` sob a mesma regra.

---

## Por que nativo

Três coisas que o app web não conseguia fazer, e que motivaram o projeto:

1. **Reprodução de TV.** O Media3 lida nativamente com HLS, DASH e os
   containers/codecs suportados pelo aparelho, com buffer adaptado a links de
   addons — sem depender do comportamento irregular de um `<video>` de WebView.
2. **Cold start.** O site pedia ~25 requisições antes de pintar a home. Aqui a
   home vem do Room no primeiro frame, e a rede nunca está no caminho crítico.
3. **CORS.** As escritas de watchlist precisam de um proxy no navegador porque o
   mdblist responde 405 ao preflight OPTIONS. Um cliente nativo não tem essa
   regra — o proxy simplesmente não existe aqui.

---

## Módulos

```
:core:model      Tipos de domínio. Kotlin puro, sem Android.
:core:network    Retrofit/OkHttp: mdblist, TMDB, OMDb, Stremio. Cache de disco HTTP.
:core:database   Room. É a fonte da verdade de tudo que a tela lê.
:core:data       Repositórios cache-first, sessão em DataStore, workers.
:core:ui         Design system de 10 pés (Compose + androidx.tv).
:player          Media3/ExoPlayer, buffer, failover, áudio e legendas.
:app             Telas, navegação, grafo de objetos.
```

Sem framework de DI. O grafo é raso o bastante para caber em construtores —
veja [`DataGraph`](core/data/src/main/kotlin/com/mdblisthub/tv/core/data/DataGraph.kt).
Isso também poupa um processador de anotações no build.

---

## O player nunca pergunta qual fonte usar

Esta é a decisão de produto que mais amarra código, então vale explicitar.

Ao dar play, o app pergunta a todos os addons instalados em paralelo e entrega
as fontes verificadas ao
[`PlaybackController`](player/src/main/kotlin/com/mdblisthub/tv/player/PlaybackController.kt)
assim que cada uma chega. Ele tenta a fila automaticamente; uma fonte morta,
token expirado ou erro de demux avança para a próxima sem interromper o usuário.

Sucesso exige o Media3 chegar a `STATE_READY`, não apenas aceitar uma URL. Se
todas as opções falharem, a mesma fila aparece para escolha manual — antes
disso, a seleção continua automática.

Tudo isso acontece atrás de um véu com o fanart do título. Nove mirrors podem ser
testados e descartados sem que nada apareça além de "Preparando a reprodução…".
Se a cascata fosse visível seria só uma versão mais lenta de um seletor.

O app web tem o mesmo comportamento, em
[`features/player`](../../mdblist-hub/src/app/features/player/player.ts).

---

## Cache e workers

O Room é a fonte da verdade. `observe*` sempre emite do banco, imediatamente;
`refresh*` escreve por cima em background. Nenhuma tela espera a rede.

A tabela de metadados é dividida em duas de propósito:

- `media` — o card, que o sync de listas já traz de graça. Barato de escrever
  para mil títulos de uma vez.
- `media_detail` — a ficha: elenco, notas, artwork, temporadas. Caro, vem de três
  APIs, e só vale a pena para títulos que alguém abre.

Assim o refresh noturno reescreve todos os cards sem tocar nas fichas que um
worker levou minutos para montar, e cada uma envelhece no seu próprio ritmo
([`CachePolicy`](core/data/src/main/kotlin/com/mdblisthub/tv/core/data/CachePolicy.kt)).

| Worker | Quando | O que faz |
| --- | --- | --- |
| `ListSyncWorker` | 6/6 h + ao abrir | Listas e seus itens → Room |
| `MetadataWorker` | 4/4 h, bateria ok | Hidrata 40 fichas por passada |
| `ArtworkWorker` | após cada sync | Pré-aquece os 8 primeiros pôsteres de cada fileira |
| `ResumeSyncWorker` | 3/3 h | "Continuar assistindo" |
| `CachePruneWorker` | 24/24 h | Descarta fichas órfãs |

Prefetch por foco **não** é WorkManager. É a
[`MetadataPrefetcher`](core/data/src/main/kotlin/com/mdblisthub/tv/core/data/MetadataPrefetcher.kt),
com escopo de processo: só serve nos próximos segundos, e se o usuário seguir
adiante deve ser descartado. É o que faz abrir uma ficha parecer instantâneo.

---

## Tamanho

| | arm64-v8a | armeabi-v7a | universal |
| --- | --- | --- | --- |
| release 0.2.0 | 5,1 MB | 5,1 MB | 5,2 MB |

O player usa os codecs do Android e não carrega um motor multimídia nativo de
dezenas de megabytes. Os splits por ABI permanecem para as pequenas bibliotecas
transitivas; o APK universal é o caminho mais simples para sideload em aparelho
desconhecido.

---

## Versões travadas

`compileSdk` está em 36 porque é a plataforma instalada na máquina de build. Os
artefatos AndroidX lançados após o salto para SDK 37 (`core` 1.18+, `lifecycle`
2.11+) se recusam a compilar contra 36 e exigem AGP 9.1, então esses dois estão
segurados uma versão atrás em
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). Subir plataforma, AGP e
esses dois é uma mudança única e coordenada.

Kotlin 2.4 exige R8 9.1.29 ou superior. O `settings.gradle.kts` fixa esse R8
compatível enquanto o projeto permanece no AGP 8.13.

## Licença

GPL-3.0 — ver [LICENSE](../LICENSE).

O APK distribui `player/libs/media3-decoder-ffmpeg-1.11.0.aar`, uma build local
do módulo `decoder_ffmpeg` do Media3. O FFmpeg que ele embute é LGPL/GPL e o
Google não o publica no Maven por causa disso; as obrigações dessa licença
acompanham qualquer redistribuição do APK.
