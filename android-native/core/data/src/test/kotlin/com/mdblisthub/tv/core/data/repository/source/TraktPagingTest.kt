package com.mdblisthub.tv.core.data.repository.source

import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The `/sync/watched` loop. Since July 2026 Trakt pages that endpoint, and a
 * single read silently returns the first 100 titles — every other watched
 * title then looks unwatched with nothing on screen to say why.
 */
class TraktPagingTest {

    private fun page(items: List<Int>, pageCount: Int?) = Response.success(
        items,
        pageCount?.let { headersOf("X-Pagination-Page-Count", it.toString()) } ?: headersOf(),
    )

    @Test
    fun `reads every page the header announces, even after a short one`() = runBlocking {
        val asked = mutableListOf<Int>()
        val pages = mapOf(1 to listOf(1, 2, 3), 2 to listOf(4), 3 to listOf(5, 6, 7))

        val all = readAllTraktPages(limit = 3, maxPages = 20) { page ->
            asked += page
            page(pages.getValue(page), pageCount = 3)
        }

        assertEquals(listOf(1, 2, 3), asked)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), all)
    }

    @Test
    fun `without the header a short page is the end`() = runBlocking {
        val all = readAllTraktPages(limit = 2, maxPages = 20) { page ->
            page(if (page == 1) listOf(1, 2) else listOf(3), pageCount = null)
        }
        assertEquals(listOf(1, 2, 3), all)
    }

    @Test
    fun `never reads past maxPages`() = runBlocking {
        var calls = 0
        readAllTraktPages(limit = 1, maxPages = 4) { _ ->
            calls++
            page(listOf(1), pageCount = 1_000)
        }
        assertEquals(4, calls)
    }

    @Test
    fun `a failed page fails the whole read instead of returning part of it`() {
        assertThrows(HttpException::class.java) {
            runBlocking {
                readAllTraktPages<Int>(limit = 1, maxPages = 20) { page ->
                    if (page == 1) page(listOf(1), pageCount = 2)
                    else Response.error(429, "".toResponseBody())
                }
            }
        }
    }
}
