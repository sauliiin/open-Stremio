package com.mdblisthub.tv.core.data.repository.source

import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.dto.BucketResponseDto

/**
 * A library bucket read to the end, not just its first page.
 *
 * mdblist paginates all three buckets, and nothing here used to ask for the
 * rest — so an account with any real history got back its most recent
 * entries and nothing else. That is not a quiet inaccuracy: the watched set
 * is what "já assisti" is decided against everywhere in the app, so a film
 * watched a while ago came back recommended in the home hero, and an older
 * episode lost its tick. The web build fixed exactly this and this mirrors
 * its answer, parameter for parameter.
 *
 * **[BUCKET_PAGE] is why this is cheap.** The endpoint's own default page is
 * a hundred entries, and asking for it that way would turn one call into ten.
 * Asking for a thousand collapses all but the largest histories into a single
 * request — fewer calls than before this function existed for most accounts,
 * not more, which matters because these run on every launch and an mdblist
 * key has a daily budget.
 *
 * A hundred entries is also not a hundred titles: the page is shared between
 * films, series, seasons and episodes, so a viewer part-way through a few
 * series spends most of a default page on episodes and gets far fewer films
 * than the number suggests. That is what made the truncation bite sooner than
 * anyone would guess from the limit alone.
 *
 * The result carries no `pagination` of its own: it is a concatenation, not a
 * page, and there is nothing after it.
 */
internal suspend fun MdblistApi.wholeBucket(url: String, apiKey: String): BucketResponseDto =
    readAllBucketPages { cursor -> bucket(url, apiKey, BUCKET_PAGE, cursor) }

/**
 * The paging loop itself, over any source of pages.
 *
 * Split from the call above so it can be tested without standing up a whole
 * `MdblistApi`: what is worth pinning down here is the loop — that it follows
 * the cursor, that it stops on `has_more` rather than on a page coming back
 * short, and that it cannot spin forever — and none of that is about
 * Retrofit. [fetchPage] is called with null for the first page.
 */
internal suspend fun readAllBucketPages(
    fetchPage: suspend (cursor: String?) -> BucketResponseDto,
): BucketResponseDto {
    var page = fetchPage(null)

    val movies = page.movies.toMutableList()
    val shows = page.shows.toMutableList()
    val episodes = page.episodes.toMutableList()

    var fetched = 1
    while (fetched < BUCKET_MAX_PAGES) {
        val pagination = page.pagination ?: break
        if (!pagination.hasMore) break
        val cursor = pagination.nextCursor?.takeIf { it.isNotBlank() } ?: break

        page = fetchPage(cursor)
        movies += page.movies
        shows += page.shows
        episodes += page.episodes
        fetched++
    }

    return BucketResponseDto(movies = movies, shows = shows, episodes = episodes)
}

/** Entries per request. See the note above on why this is not the default. */
internal const val BUCKET_PAGE = 1_000

/**
 * A stop, not a budget.
 *
 * Five thousand entries is past any real library, so reaching this means the
 * cursor is not advancing rather than that the account is enormous — and an
 * unbounded `while` against a remote that keeps saying `has_more` is a loop
 * that never ends on a television nobody is watching. Same ceiling as the web
 * build's.
 */
internal const val BUCKET_MAX_PAGES = 5
