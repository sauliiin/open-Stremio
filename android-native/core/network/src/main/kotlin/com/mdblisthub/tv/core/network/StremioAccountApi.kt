package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.StremioApiResponse
import com.mdblisthub.tv.core.network.dto.StremioCollectionRequest
import com.mdblisthub.tv.core.network.dto.StremioCollectionResult
import com.mdblisthub.tv.core.network.dto.StremioCollectionSetRequest
import com.mdblisthub.tv.core.network.dto.StremioLoginRequest
import com.mdblisthub.tv.core.network.dto.StremioLoginResult
import com.mdblisthub.tv.core.network.dto.StremioLogoutRequest
import com.mdblisthub.tv.core.network.dto.StremioSuccessResult
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

interface StremioAccountApi {
    @POST("api/login")
    suspend fun login(@Body request: StremioLoginRequest): StremioApiResponse<StremioLoginResult>

    @POST("api/addonCollectionGet")
    suspend fun collection(
        @Body request: StremioCollectionRequest,
    ): StremioApiResponse<StremioCollectionResult>

    @POST("api/logout")
    suspend fun logout(@Body request: StremioLogoutRequest): StremioApiResponse<JsonObject>

    @POST("api/addonCollectionSet")
    suspend fun setCollection(
        @Body request: StremioCollectionSetRequest,
    ): StremioApiResponse<StremioSuccessResult>
}
