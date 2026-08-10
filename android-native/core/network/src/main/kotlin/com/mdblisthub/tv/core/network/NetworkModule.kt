package com.mdblisthub.tv.core.network

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * Builds the API clients once for the process.
 *
 * There is no DI framework in this app: an object graph this shallow is
 * cheaper to read as plain constructors, and skipping annotation processing
 * for it keeps builds fast.
 */
class NetworkModule(context: Context) {

    val json: Json = HttpClients.json

    /** Shared by every metadata API, so they share one connection pool. */
    val metadataClient: OkHttpClient = HttpClients.metadata(context.applicationContext)

    /** Short timeouts and no cache: addon hosts are third-party and flaky. */
    val addonClient: OkHttpClient = HttpClients.addons(metadataClient)

    /** The same hosts, but for the one-off install, which may cold-start slowly. */
    val addonInstallClient: OkHttpClient = HttpClients.addonInstall(metadataClient)

    private val converter = json.asConverterFactory("application/json".toMediaType())

    val mdblist: MdblistApi = retrofit(ApiConfig.MDBLIST_BASE, metadataClient).create()
    val tmdb: TmdbApi = retrofit(ApiConfig.TMDB_BASE, metadataClient).create()
    val omdb: OmdbApi = retrofit(ApiConfig.OMDB_BASE, metadataClient).create()

    /**
     * Every call takes a full `@Url`, so the base is only there to satisfy
     * Retrofit's constructor.
     */
    val stremio: StremioApi = retrofit(ApiConfig.MDBLIST_BASE, addonClient).create()

    /** Same endpoints, patient client — see [addonInstallClient]. */
    val stremioInstall: StremioApi = retrofit(ApiConfig.MDBLIST_BASE, addonInstallClient).create()

    val stremioAccount: StremioAccountApi =
        retrofit(ApiConfig.STREMIO_ACCOUNT_BASE, metadataClient).create()

    /** Also `@Url`-driven; the base only has to be a valid URL. */
    val sync: SyncApi = retrofit(ApiConfig.FIREBASE_BASE, metadataClient).create()

    /** `@Url`-driven too — a cast member's bio can come from either language edition. */
    val wikipedia: WikipediaApi = retrofit(ApiConfig.MDBLIST_BASE, metadataClient).create()

    val imdb: ImdbApi = retrofit(ApiConfig.IMDB_GRAPHQL_BASE, metadataClient).create()

    /** OpenSubtitles.com's own API — see [ApiConfig.OPENSUBTITLES_BASE]. */
    val openSubtitles: OpenSubtitlesApi =
        retrofit(ApiConfig.OPENSUBTITLES_BASE, HttpClients.openSubtitles(metadataClient)).create()

    /**
     * Wyzie's own API — see [ApiConfig.WYZIE_BASE]. No dedicated client: its
     * key travels as a query param, not a header, so there is nothing for a
     * client-level interceptor to attach.
     */
    val wyzie: WyzieApi = retrofit(ApiConfig.WYZIE_BASE, metadataClient).create()

    val fanartTv: FanartTvApi = retrofit(ApiConfig.FANART_TV_BASE, metadataClient).create()

    private fun retrofit(base: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(converter)
            .build()
}
