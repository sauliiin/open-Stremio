package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/**
 * An installed Stremio addon.
 *
 * Nothing ships with the app: every addon URL is pasted by whoever is using
 * it, the same model Stremio itself uses.
 */
@Serializable
data class Addon(
    /** Transport URL with `/manifest.json` stripped — everything hangs off it. */
    val base: String,
    val id: String,
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val version: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val configurable: Boolean = false,
    val addedAt: Long = 0L,
) {
    /**
     * Whether it is worth asking this addon for `resource` about this id.
     *
     * An addon that declares `idPrefixes` only answers for ids that start with
     * one of them, so skipping the rest is the difference between one request
     * and a dozen timeouts on every stream screen.
     */
    fun serves(resource: String, type: MediaType, id: String): Boolean {
        if (resources.none { it.equals(resource, true) }) return false
        if (types.isNotEmpty() && types.none { it.equals(type.stremio, true) }) return false
        if (idPrefixes.isEmpty()) return true
        return idPrefixes.any { id.startsWith(it) }
    }

    val configureUrl: String? get() = if (configurable) "$base/configure" else null

    companion object {
        /**
         * Accepts whatever gets pasted — `stremio://` links, a bare host, or
         * the full manifest URL — and answers the base everything else uses.
         */
        fun normaliseUrl(raw: String): String {
            var url = raw.trim()
            requireOrFail(url.isNotEmpty()) { AppError.AddonManifestUnreadable }

            if (url.startsWith("stremio://", true)) url = "https://" + url.substring(10)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "https://$url"
            }
            url = url.substringBefore('#').trimEnd('/')
            if (url.endsWith("/manifest.json", true)) {
                url = url.dropLast("/manifest.json".length)
            }
            requireOrFail(url.length > "https://".length) { AppError.AddonManifestUnreadable }
            return url
        }
    }
}

/** One home-screen row declared by an installed Stremio addon manifest. */
data class AddonCatalog(
    val addonBase: String,
    val addonId: String,
    val id: String,
    val type: String,
    val name: String,
    val originalName: String = name,
    /** Null until the row is manually moved; the home then appends it after MDBList rows. */
    val position: Int? = null,
    val hidden: Boolean = false,
) {
    /** Key used by older builds, which collided across configured addon instances. */
    val legacyKey: String get() = "$addonId:$type:$id"

    /**
     * The configured base is part of the identity, but only as a stable hash:
     * addon URLs commonly contain private tokens and must not be copied into
     * the Firebase preference document. The legacy prefix keeps migration
     * straightforward while distinct instances no longer share one row.
     */
    val key: String
        get() = "$legacyKey@${addonBase.trimEnd('/').lowercase().hashCode().toUInt().toString(36)}"
}

@Serializable
data class StremioAccount(
    val authKey: String,
    val email: String,
)

data class StremioImportFailure(
    val name: String,
    val url: String,
    val reason: AddonImportSkipReason,
)

/** Why one entry of a Stremio collection import was skipped. */
enum class AddonImportSkipReason {
    /** The account record has no `transportUrl` for this addon at all. */
    NO_URL,

    /** The manifest JSON has no `id` field, so it cannot be identified as an addon. */
    NO_MANIFEST_ID,

    /** [Addon.normaliseUrl] could not make sense of the stored URL. */
    UNPARSABLE_URL,

    /** The manifest parsed as JSON but not into a usable addon entity. */
    INVALID_MANIFEST,
}

data class StremioImportReport(
    val received: Int,
    val imported: List<String>,
    val skipped: List<StremioImportFailure>,
)

data class MdblistAddonExportReport(
    val exported: List<String>,
    val failed: List<String>,
)
