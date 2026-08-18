package com.mdblisthub.tv.core.model

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Every domain failure a repository can hand back, as a type instead of a
 * sentence.
 *
 * This exists because a repository has no `Context` and cannot know the
 * interface's chosen language — it used to write the message in Portuguese
 * directly into the exception, which is what a Portuguese speaker saw no
 * matter which language the app was set to, and what an English-reading user
 * saw literally always. Resolving the words happens exactly once, in the
 * Compose layer that can call `stringResource` — see `AppError.text()` in the
 * `:app` module. This type only says *what* went wrong.
 *
 * A flat sealed hierarchy rather than one per repository: several screens
 * (Addons, Detail's library toggle, Settings) all render whatever error a
 * repository handed them through the same handful of `InlineMessage`/`Text`
 * call sites, so one exhaustive `when` covering every case is what keeps a
 * new failure from being addable in the model without the compiler forcing
 * its rendering to be addressed too.
 */
sealed interface AppError {

    /**
     * The UI-layer catch-all for a failure that never went through
     * [requireOrFail]/[fail]/[orFail] at all — a raw network timeout, a JSON
     * shape the DTO did not expect, and the like.
     *
     * Every precondition and every known failure branch in every repository
     * now throws a specific case above instead of a bare exception, so this
     * is reached only for the genuinely unanticipated ones — which used to
     * surface as whatever `Throwable.message` happened to contain (English
     * OkHttp text on a Portuguese interface, or nothing at all). Screens use
     * this as `(e as? AppException)?.error ?: AppError.Unexpected`.
     */
    data object Unexpected : AppError

    // ---------------------------------------------------------- validation

    /** A rename (list, feed, or catalog) was submitted blank. */
    data object NameRequired : AppError

    // -------------------------------------------------------------- addons

    /**
     * A fresh install's first request can spend the better part of a minute
     * validating the debrid key it carries; this is that timeout, not a dead
     * host — the addon almost always answers instantly on the next attempt.
     */
    data object AddonInstallTimedOut : AppError

    /** The manifest URL did not answer with anything parseable as one. */
    data object AddonManifestUnreadable : AppError

    /** Answered, but the JSON is not a Stremio addon manifest (no id/name). */
    data object AddonManifestInvalid : AppError

    // ------------------------------------------------------------ mdblist

    data object MdblistNotLinked : AppError
    data object MdblistNoLists : AppError
    data object MdblistNoValidManifests : AppError
    data object MdblistKeyRequired : AppError

    /** A write to `api.mdblist.com` answered outside the 2xx range. */
    data class MdblistWriteRejected(val code: Int) : AppError

    /** The account toggling a library bucket has no mdblist session at all. */
    data object MdblistSessionExpired : AppError

    // --------------------------------------------------------- google/auth

    data object GoogleSignInRequired : AppError
    data object GoogleAccountChangedDuringRestore : AppError
    data object GoogleAccountChangedDuringSync : AppError

    // ----------------------------------------------------------- firebase

    data object FirebaseNoAccount : AppError
    data object FirebaseNoToken : AppError
    data class FirebaseLinkRejected(val code: Int) : AppError
    data class FirebaseUnlinkRejected(val code: Int) : AppError
    data class FirebaseAddonSyncRejected(val code: Int) : AppError
    data class FirebasePreferencesSyncRejected(val code: Int) : AppError
    data object FirebaseUnreachable : AppError

    /** Generic backstop after every retry in the automatic addon push failed. */
    data object AddonSyncFailed : AppError

    /** Generic backstop after every retry of the list-preferences push failed. */
    data object ListOrderSyncFailed : AppError

    /** Same backstop as [ListOrderSyncFailed], for the initial pull/restore path. */
    data object PreferencesSyncFailed : AppError

    /** A snapshot kept changing underfoot faster than three write passes could settle it. */
    data object PreferencesChangedRepeatedly : AppError

    /**
     * `pull` found no addon list stored yet — nothing to bring down, and
     * nothing was touched locally either.
     */
    data object NoCloudAddonList : AppError

    // -------------------------------------------------------------- stremio

    data object StremioCredentialsRequired : AppError
    data object StremioNoSession : AppError
    data object StremioNotLinked : AppError
    data object StremioSessionExpired : AppError
    data object StremioUnexpectedResponse : AppError
    data object StremioWrongPassword : AppError
    data object StremioUserNotFound : AppError

    /**
     * Whatever `api.strem.io` said, when it is none of the recognised cases
     * above. [raw] is the API's own text — already whatever language Stremio
     * itself answers in, most often English — shown as-is because there is no
     * better translation to offer for a message this app has never seen.
     */
    data class StremioRequestRejected(val raw: String?) : AppError

    // ---------------------------------------------------------------- trakt

    data object TraktNotLinked : AppError
    data object TraktTitleNotRecognized : AppError

    /** No client id/secret in this build — see `ApiConfig.TRAKT_CLIENT_ID`. */
    data object TraktNotConfigured : AppError

    /** The device-code exchange failed for any other reason. */
    data object TraktUnavailable : AppError

    // ---------------------------------------------------------------- media

    data object InvalidImdbId : AppError
    data object TmdbTitleNotFound : AppError
}

/**
 * Carries an [AppError] through the ordinary `throw`/`runCatching`/`Result`
 * idioms every repository already uses, without those call sites needing to
 * change shape.
 *
 * [message] is English and exists only for logcat and crash reports — nothing
 * reads it for display. Localized text always comes from resolving [error].
 */
class AppException(val error: AppError) : Exception(error.toString())

/** Throws [error] wrapped as an [AppException] — the typed sibling of `error()`. */
fun fail(error: AppError): Nothing = throw AppException(error)

/**
 * The typed sibling of `require()`/`check()`: throws [error] when [condition]
 * is false.
 *
 * Carries the same contract `require()` does — `returns() implies condition`
 * — so the compiler smart-casts whatever the boolean tested (`x != null`,
 * `x is Y`) on every line after the call, exactly like the stdlib function it
 * replaces. Without this, replacing a `require`/`check` call site with this
 * one would compile but silently lose every smart-cast downstream of it.
 */
@OptIn(ExperimentalContracts::class)
inline fun requireOrFail(condition: Boolean, error: () -> AppError) {
    contract { returns() implies condition }
    if (!condition) fail(error())
}

/**
 * The typed sibling of `requireNotNull()`.
 *
 * Not built on `requireNotNull` itself: its `lazyMessage` parameter is typed
 * `() -> Any` and is only ever turned into `IllegalArgumentException(message
 * .toString())` — passing an [AppError] there would silently stringify it
 * instead of throwing something a caller could match on.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T : Any> T?.orFail(error: () -> AppError): T {
    contract { returns() implies (this@orFail != null) }
    return this ?: fail(error())
}
