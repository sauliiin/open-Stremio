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

    @GET
    suspend fun read(@Url url: String, @Query("auth") idToken: String): JsonElement

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
    ): JsonElement

    @PUT
    suspend fun writeListPreferences(
        @Url url: String,
        @Query("auth") idToken: String,
        @Body preferences: FirebaseListPreferencesDto,
    ): Response<ResponseBody>
}
