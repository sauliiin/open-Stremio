package com.mdblisthub.tv.core.data.repository.source

import com.mdblisthub.tv.core.network.dto.BucketEntryDto
import com.mdblisthub.tv.core.network.dto.BucketPaginationDto
import com.mdblisthub.tv.core.network.dto.BucketResponseDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bucket loop.
 *
 * These exist because the bug they guard against was invisible: reading one
 * page returns a perfectly valid response, and the only symptom was a film
 * the viewer had already watched turning up as a recommendation months later.
 * Nothing about a single page looks wrong, so the loop is the thing that has
 * to be pinned down.
 */
class BucketPagingTest {

    private fun movie(id: Int) = BucketEntryDto(id = id)

    private fun page(
        ids: List<Int>,
        nextCursor: String? = null,
    ) = BucketResponseDto(
        movies = ids.map(::movie),
        pagination = BucketPaginationDto(hasMore = nextCursor != null, nextCursor = nextCursor),
    )

    @Test
    fun `follows the cursor to the end and concatenates every page`() = runBlocking {
        val asked = mutableListOf<String?>()
        val pages = mapOf<String?, BucketResponseDto>(
            null to page(listOf(1, 2), nextCursor = "c1"),
            "c1" to page(listOf(3, 4), nextCursor = "c2"),
            "c2" to page(listOf(5)),
        )

        val whole = readAllBucketPages { cursor ->
            asked += cursor
            pages.getValue(cursor)
        }

        assertEquals(listOf(null, "c1", "c2"), asked)
        assertEquals(listOf(1, 2, 3, 4, 5), whole.movies.map { it.id })
    }

    @Test
    fun `stops on has_more even when a cursor is still offered`() = runBlocking {
        var calls = 0
        val whole = readAllBucketPages { _ ->
            calls++
            BucketResponseDto(
                movies = listOf(movie(1)),
                // The cursor is present but the server says this is the end.
                // `has_more` is the authority; a leftover cursor is not.
                pagination = BucketPaginationDto(hasMore = false, nextCursor = "leftover"),
            )
        }

        assertEquals(1, calls)
        assertEquals(listOf(1), whole.movies.map { it.id })
    }

    @Test
    fun `stops when the server keeps claiming more, rather than looping forever`() = runBlocking {
        var calls = 0
        val whole = readAllBucketPages { _ ->
            calls++
            page(listOf(calls), nextCursor = "always")
        }

        assertEquals(BUCKET_MAX_PAGES, calls)
        assertEquals(BUCKET_MAX_PAGES, whole.movies.size)
    }

    @Test
    fun `stops when the cursor is missing or blank`() = runBlocking {
        for (cursor in listOf(null, "", "   ")) {
            var calls = 0
            readAllBucketPages { _ ->
                calls++
                BucketResponseDto(
                    movies = listOf(movie(1)),
                    pagination = BucketPaginationDto(hasMore = true, nextCursor = cursor),
                )
            }
            assertEquals("cursor=<$cursor>", 1, calls)
        }
    }

    @Test
    fun `stops when the response carries no pagination at all`() = runBlocking {
        var calls = 0
        readAllBucketPages { _ ->
            calls++
            BucketResponseDto(movies = listOf(movie(1)))
        }
        assertEquals(1, calls)
    }

    @Test
    fun `the merged result is not itself a page`() = runBlocking {
        val whole = readAllBucketPages { cursor ->
            if (cursor == null) page(listOf(1), nextCursor = "c1") else page(listOf(2))
        }
        // A concatenation has nothing after it, and saying otherwise would
        // invite a caller to page the already-complete result.
        assertNull(whole.pagination)
    }
}
