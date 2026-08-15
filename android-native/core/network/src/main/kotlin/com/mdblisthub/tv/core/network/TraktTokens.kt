package com.mdblisthub.tv.core.network

/**
 * The Trakt credential, as the HTTP layer needs to see it.
 *
 * An interface rather than a direct dependency because the token itself lives
 * in `:core:data` — it is persisted, refreshed and cleared alongside the rest
 * of the session state, and `:core:network` sits below that module. This is
 * the narrow seam between the two: read the current token, and when the
 * server rejects it, produce a fresh one.
 *
 * Both methods are blocking on purpose. Their only callers are an OkHttp
 * interceptor and authenticator, neither of which can suspend, and neither of
 * which ever runs on the main thread.
 */
interface TraktTokens {

    /** The access token to send, or blank when no account is linked. */
    fun accessToken(): String

    /**
     * Called once after a `401`. Exchanges the refresh token, persists the new
     * pair and returns the new access token — or null when the link is gone
     * for good and the user has to authorize again.
     *
     * [expired] is the token that was just rejected, so a concurrent caller
     * that already refreshed can recognise the retry and hand back what it
     * stored instead of burning a second single-use refresh token.
     */
    fun refreshed(expired: String): String?

    /** The state before anyone links an account — and after they unlink one. */
    companion object {
        val Unlinked: TraktTokens = object : TraktTokens {
            override fun accessToken(): String = ""
            override fun refreshed(expired: String): String? = null
        }
    }
}
