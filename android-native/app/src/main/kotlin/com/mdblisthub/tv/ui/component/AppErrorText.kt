package com.mdblisthub.tv.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.model.AddonImportSkipReason
import com.mdblisthub.tv.core.model.AppError

/**
 * The one place an [AppError] becomes words.
 *
 * Every screen that surfaces a repository failure — Addons, the Detail
 * library toggle, Settings' Trakt link — resolves through here rather than
 * reading `Throwable.message`, which is how a Portuguese sentence used to
 * reach an English interface (and vice versa) regardless of the locale the
 * rest of the screen was drawn in. `stringResource` only works inside
 * composition, which is exactly why [AppError] itself carries no text at
 * all — this function is the single seam where that text gets attached.
 */
@Composable
fun AppError.text(): String = when (this) {
    AppError.Unexpected -> stringResource(R.string.error_unexpected)

    AppError.NameRequired -> stringResource(R.string.error_name_required)

    AppError.AddonInstallTimedOut -> stringResource(R.string.error_addon_install_timed_out)
    AppError.AddonManifestUnreadable -> stringResource(R.string.error_addon_manifest_unreadable)
    AppError.AddonManifestInvalid -> stringResource(R.string.error_addon_manifest_invalid)

    AppError.MdblistNotLinked -> stringResource(R.string.error_mdblist_not_linked)
    AppError.MdblistNoLists -> stringResource(R.string.error_mdblist_no_lists)
    AppError.MdblistNoValidManifests -> stringResource(R.string.error_mdblist_no_valid_manifests)
    AppError.MdblistKeyRequired -> stringResource(R.string.error_mdblist_key_required)
    is AppError.MdblistWriteRejected -> stringResource(R.string.error_mdblist_write_rejected, code)
    AppError.MdblistSessionExpired -> stringResource(R.string.error_mdblist_session_expired)

    AppError.GoogleSignInRequired -> stringResource(R.string.error_google_sign_in_required)
    AppError.GoogleAccountChangedDuringRestore ->
        stringResource(R.string.error_google_account_changed_restore)
    AppError.GoogleAccountChangedDuringSync ->
        stringResource(R.string.error_google_account_changed_sync)

    AppError.FirebaseNoAccount -> stringResource(R.string.error_firebase_no_account)
    AppError.FirebaseNoToken -> stringResource(R.string.error_firebase_no_token)
    is AppError.FirebaseLinkRejected -> stringResource(R.string.error_firebase_link_rejected, code)
    is AppError.FirebaseUnlinkRejected -> stringResource(R.string.error_firebase_unlink_rejected, code)
    is AppError.FirebaseAddonSyncRejected ->
        stringResource(R.string.error_firebase_addon_sync_rejected, code)
    is AppError.FirebasePreferencesSyncRejected ->
        stringResource(R.string.error_firebase_preferences_sync_rejected, code)
    AppError.FirebaseUnreachable -> stringResource(R.string.error_firebase_unreachable)

    AppError.AddonSyncFailed -> stringResource(R.string.error_addon_sync_failed)
    AppError.ListOrderSyncFailed -> stringResource(R.string.error_list_order_sync_failed)
    AppError.PreferencesSyncFailed -> stringResource(R.string.error_preferences_sync_failed)
    AppError.PreferencesChangedRepeatedly ->
        stringResource(R.string.error_preferences_changed_repeatedly)
    AppError.NoCloudAddonList -> stringResource(R.string.error_no_cloud_addon_list)

    AppError.StremioCredentialsRequired -> stringResource(R.string.error_stremio_credentials_required)
    AppError.StremioNoSession -> stringResource(R.string.error_stremio_no_session)
    AppError.StremioNotLinked -> stringResource(R.string.error_stremio_not_linked)
    AppError.StremioSessionExpired -> stringResource(R.string.error_stremio_session_expired)
    AppError.StremioUnexpectedResponse -> stringResource(R.string.error_stremio_unexpected_response)
    AppError.StremioWrongPassword -> stringResource(R.string.error_stremio_wrong_password)
    AppError.StremioUserNotFound -> stringResource(R.string.error_stremio_user_not_found)
    is AppError.StremioRequestRejected ->
        raw?.takeIf { it.isNotBlank() } ?: stringResource(R.string.error_stremio_request_rejected)

    AppError.TraktNotLinked -> stringResource(R.string.error_trakt_not_linked)
    AppError.TraktTitleNotRecognized -> stringResource(R.string.error_trakt_title_not_recognized)
    // Reuses the Trakt-link overlay's own strings rather than duplicating
    // them: both cases mean exactly what those two already say.
    AppError.TraktNotConfigured -> stringResource(R.string.trakt_link_error_credentials)
    AppError.TraktUnavailable -> stringResource(R.string.trakt_link_error_unavailable)

    AppError.InvalidImdbId -> stringResource(R.string.error_invalid_imdb_id)
    AppError.TmdbTitleNotFound -> stringResource(R.string.error_tmdb_title_not_found)
}

/** Why one Stremio collection entry was skipped during import — see [AppError.text]. */
@Composable
fun AddonImportSkipReason.text(): String = when (this) {
    AddonImportSkipReason.NO_URL -> stringResource(R.string.addon_import_skip_no_url)
    AddonImportSkipReason.NO_MANIFEST_ID -> stringResource(R.string.addon_import_skip_no_manifest_id)
    AddonImportSkipReason.UNPARSABLE_URL -> stringResource(R.string.addon_import_skip_unparsable_url)
    AddonImportSkipReason.INVALID_MANIFEST -> stringResource(R.string.addon_import_skip_invalid_manifest)
}
