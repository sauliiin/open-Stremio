package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.SyncPayloadDto
import com.mdblisthub.tv.core.network.dto.FirebaseProfileDto
import com.mdblisthub.tv.core.network.dto.FirebaseListPreferencesDto
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Firebase Realtime Database over its REST interface, rather than the Firebase
 * SDK: two verbs on one path is all this needs, and Retrofit already speaks
 * them — so cross-device sync costs no extra dependency and no Google Services
 * plugin.
 */
interface SyncApi {

    /**
     * `no-store` on every read, matching every mdblist GET in this app: this
     * is cross-device state read right after another device wrote it, and
     * `metadataClient`'s 96 MB disk cache must never answer with what this
     * device saw on its own last read instead of what is actually there now.
     */
    @GET
    suspend fun read(
        @Url url: String,
        @Query("auth") idToken: String,
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): JsonElement

    @PUT
    suspend fun write(
        @Url url: String,
        @Query("auth") idToken: String,
        @Body payload: SyncPayloadDto,
    ): Response<ResponseBody>

    @GET
    suspend fun readProfile(
        @Url url: String,
        @Query("auth") idToken: String,
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): JsonElement

    @PUT
    suspend fun writeProfile(
        @Url url: String,
        @Query("auth") idToken: String,
        @Body profile: FirebaseProfileDto,
    ): Response<ResponseBody>

    @DELETE
    suspend fun deleteProfile(
        @Url url: String,
        @Query("auth") idToken: String,
    ): Response<ResponseBody>

    @GET
    suspend fun readListPreferences(
        @Url url: String,
        @Query("auth") idToken: String,
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): JsonElement

    @PUT
    suspend fun writeListPreferences(
        @Url url: String,
        @Query("auth") idToken: String,
        @Body preferences: FirebaseListPreferencesDto,
    ): Response<ResponseBody>
}
