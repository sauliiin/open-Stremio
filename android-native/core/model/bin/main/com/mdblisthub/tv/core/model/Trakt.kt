package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/** The linked Trakt account, as the settings screen names it. */
@Serializable
data class TraktAccount(
    val username: String,
    val slug: String? = null,
) {
    val handle: String get() = "@$username"
}

/**
 * The pair the device flow hands back at the start of a link.
 *
 * [userCode] is what the person types, and [verificationUrl] is where they
 * type it — the whole link is built from these two, read aloud or copied by
 * hand.
 */
data class TraktDeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    /** Seconds between polls. Going faster than Trakt asked for earns a 429. */
    val intervalSeconds: Int,
    /** Seconds the pair stays valid — the countdown the screen shows. */
    val expiresInSeconds: Int,
)

/**
 * How far along linking an account is.
 *
 * [Failed] carries a reason rather than a message: the strings belong to the
 * screen, which is the only place that knows the interface language — the
 * same split the login screen already makes between `error` and `errorRes`.
 */
sealed interface TraktLinkState {
    /** Asking Trakt for a code; nothing to show the user yet. */
    data object Requesting : TraktLinkState

    /** The code is on screen and this is waiting for the user to approve it. */
    data class AwaitingUser(
        val code: TraktDeviceCode,
        /** Seconds left before the code stops working. */
        val secondsRemaining: Int,
    ) : TraktLinkState

    data class Linked(val account: TraktAccount) : TraktLinkState

    data class Failed(val reason: TraktLinkFailure) : TraktLinkState
}

enum class TraktLinkFailure {
    /** No client id/secret in the build — see `ApiConfig.TRAKT_CLIENT_ID`. */
    MISSING_CREDENTIALS,

    /** The ten-minute window closed before anyone approved the code. */
    EXPIRED,

    /** The user pressed "Deny" on trakt.tv. */
    DENIED,

    /** Network, or anything Trakt answered that the flow does not model. */
    UNAVAILABLE,
}
