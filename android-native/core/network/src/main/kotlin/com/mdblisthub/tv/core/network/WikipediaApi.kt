package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.WikipediaSummaryDto
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Wikipedia's REST summary API, one per language edition — the repository
 * builds a full `pt.wikipedia.org`/`en.wikipedia.org` URL per call, so there
 * is no single base to speak of here.
 */
interface WikipediaApi {

    @GET
    suspend fun summary(@Url url: String): WikipediaSummaryDto
}
