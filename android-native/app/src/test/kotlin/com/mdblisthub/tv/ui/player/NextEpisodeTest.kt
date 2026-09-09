package com.mdblisthub.tv.ui.player

import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.player.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeTest {
    @Test
    fun `selects the closest following episode even when metadata is unordered`() {
        val episodes = listOf(episode(8), episode(3), episode(5))

        assertEquals(5, nextEpisodeAfter(episodes, currentEpisode = 3)?.episodeNumber)
    }

    @Test
    fun `does not invent an episode after the season ends`() {
        val episodes = listOf(episode(1), episode(2), episode(3))

        assertNull(nextEpisodeAfter(episodes, currentEpisode = 3))
    }

    @Test
    fun `offers auto next exactly at ninety three percent`() {
        assertFalse(offer(progress = 0.9299f))
        assertTrue(offer(progress = 0.93f))
    }

    @Test
    fun `does not offer without a next episode or more than once`() {
        assertFalse(offer(hasNextEpisode = false))
        assertFalse(offer(alreadyTriggered = true))
        assertFalse(offer(phase = PlaybackPhase.PAUSED))
    }

    private fun offer(
        progress: Float = 0.93f,
        hasNextEpisode: Boolean = true,
        alreadyTriggered: Boolean = false,
        phase: PlaybackPhase = PlaybackPhase.PLAYING,
    ) = shouldOfferNextEpisode(
        enabled = true,
        hasNextEpisode = hasNextEpisode,
        phase = phase,
        progress = progress,
        alreadyTriggered = alreadyTriggered,
    )

    private fun episode(number: Int) = Episode(
        id = number,
        seasonNumber = 2,
        episodeNumber = number,
        name = "Episode $number",
    )
}
