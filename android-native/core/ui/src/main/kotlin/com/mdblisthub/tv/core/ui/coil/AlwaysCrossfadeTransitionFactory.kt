package com.mdblisthub.tv.core.ui.coil

import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.transition.CrossfadeDrawable
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget

/**
 * Coil's own [CrossfadeTransition.Factory] skips the animation whenever the
 * image was already sitting in the memory cache — which on a TV, browsing
 * rows that were visible a minute ago, is most of the time. That reads as
 * every other poster popping in with no transition at all, next to the rare
 * one that actually fades.
 *
 * This factory only skips the animation when the request resolves to the
 * exact same URL already on screen, so scrolling a row or walking focus
 * across the same still-loading card doesn't restart the fade — but a
 * genuinely new image, cached or not, always crossfades.
 */
class AlwaysCrossfadeTransitionFactory @JvmOverloads constructor(
    private val durationMillis: Int = CrossfadeDrawable.DEFAULT_DURATION,
    private val preferExactIntrinsicSize: Boolean = false,
) : Transition.Factory {

    @Volatile
    private var lastUrl: Any? = null

    init {
        require(durationMillis > 0) { "durationMillis must be > 0." }
    }

    override fun create(target: TransitionTarget, result: ImageResult): Transition {
        if (result !is SuccessResult) {
            return Transition.Factory.NONE.create(target, result)
        }
        val url = result.request.data
        val previousUrl = lastUrl
        lastUrl = url

        if (previousUrl != null && previousUrl == url) {
            return Transition.Factory.NONE.create(target, result)
        }

        return CrossfadeTransition(
            target = target,
            result = result,
            durationMillis = durationMillis,
            preferExactIntrinsicSize = preferExactIntrinsicSize,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is AlwaysCrossfadeTransitionFactory &&
            durationMillis == other.durationMillis &&
            preferExactIntrinsicSize == other.preferExactIntrinsicSize
    }

    override fun hashCode(): Int {
        var result = durationMillis
        result = 31 * result + preferExactIntrinsicSize.hashCode()
        return result
    }
}
