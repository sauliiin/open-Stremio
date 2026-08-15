package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.TraktDeviceCodeDto
import com.mdblisthub.tv.core.network.dto.TraktDeviceCodeRequestDto
import com.mdblisthub.tv.core.network.dto.TraktDeviceTokenRequestDto
import com.mdblisthub.tv.core.network.dto.TraktRefreshRequestDto
import com.mdblisthub.tv.core.network.dto.TraktRevokeRequestDto
import com.mdblisthub.tv.core.network.dto.TraktTokenDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Trakt's OAuth host, which is a different one from its API host — see
 * [ApiConfig.TRAKT_AUTH_BASE].
 *
 * Only the device flow is implemented. It is the one flow that works
 * unchanged on a television and a phone: no redirect URI to register, no
 * browser to hand control to and get it back from, and the same screen serves
 * a D-pad and a thumb. The user reads a code and approves on whatever device
 * they already have a browser open on.
 */
interface TraktAuthApi {

    @POST("oauth/device/code")
    suspend fun deviceCode(@Body body: TraktDeviceCodeRequestDto): TraktDeviceCodeDto

    /**
     * The raw [Response] rather than the decoded body, because the polling
     * loop is driven entirely by the status code: `400` means keep waiting,
     * `429` means wait longer, and `404`/`409`/`410`/`418` each end the
     * attempt for a different reason the user has to be told about. Letting
     * Retrofit throw on all of them alike would collapse those into one
     * indistinguishable failure.
     */
    @POST("oauth/device/token")
    suspend fun deviceToken(@Body body: TraktDeviceTokenRequestDto): Response<TraktTokenDto>

    @POST("oauth/token")
    suspend fun refresh(@Body body: TraktRefreshRequestDto): Response<TraktTokenDto>

    @POST("oauth/revoke")
    suspend fun revoke(@Body body: TraktRevokeRequestDto): Response<ResponseBody>
}
