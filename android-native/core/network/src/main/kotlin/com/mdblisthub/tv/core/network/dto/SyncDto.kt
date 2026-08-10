package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------- Firebase sync

/**
 * The Firebase record shape, shared with the web app.
 *
 * This is **not** free to change: the same records are written and read by
 * the Angular build, so the two clients only stay in sync for as long as both
 * speak this exact structure — `{base, manifest, addedAt}`, matching
 * `InstalledAddon` in `core/stremio/models.ts`. The manifest travels as a raw
 * object for the same reason: whatever one client does not model, the other
 * must still get back untouched.
 */
@Serializable
data class SyncedAddonDto(
    val base: String = "",
    val manifest: JsonObject? = null,
    /** ISO-8601, which is what the web app writes. */
    val addedAt: String? = null,
)

@Serializable
data class SyncPayloadDto(
    val updatedAt: String? = null,
    val addons: List<SyncedAddonDto> = emptyList(),
)

/** Private per-Google-account settings stored under `/users/{uid}/profile`. */
@Serializable
data class FirebaseProfileDto(
    val mdblistApiKey: String = "",
)

/** One Open Stream customization for an MDBList row, shared between Google devices. */
@Serializable
data class FirebaseListPreferenceDto(
    val id: Long,
    /** Null means "follow the current name from MDBList". */
    val name: String? = null,
    val position: Int = 0,
    val hidden: Boolean = false,
    val deleted: Boolean = false,
)

/** One Open Stream customization for a catalog declared by a Stremio addon. */
@Serializable
data class FirebaseCatalogPreferenceDto(
    /** Stable manifest identity: addon id + media type + catalog id. */
    val key: String,
    /** Null means "follow the current name from the addon manifest". */
    val name: String? = null,
    /** Null means the catalog has never been manually reordered. */
    val position: Int? = null,
    val hidden: Boolean = false,
    val deleted: Boolean = false,
)

/** Private per-account list customizations stored below `/users/{uid}/listPreferences`. */
@Serializable
data class FirebaseListPreferencesDto(
    val updatedAt: String? = null,
    /** Prevents preferences from one linked MDBList account crossing into another. */
    val mdblistUserId: Long = 0,
    val lists: List<FirebaseListPreferenceDto> = emptyList(),
    val catalogs: List<FirebaseCatalogPreferenceDto> = emptyList(),
)
