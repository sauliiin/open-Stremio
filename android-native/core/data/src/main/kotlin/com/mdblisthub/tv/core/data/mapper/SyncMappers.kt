package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.dto.StremioManifestDto
import com.mdblisthub.tv.core.network.dto.SyncedAddonDto
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 in and out, without `java.time` — that package needs API 26, and
 * this app's floor is 24. `SimpleDateFormat` has done the job since API 1.
 */
private object Iso8601 {
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun format(epochMs: Long): String = formatter().format(java.util.Date(epochMs))

    fun parseOrNull(text: String): Long? = runCatching { formatter().parse(text)?.time }.getOrNull()
}

/**
 * Turning a manifest that arrived as a raw [JsonObject] into a row.
 *
 * Firebase hands manifests over this way — this app's own past writes —
 * rather than through Retrofit's typed deserialiser. Decoding lazily here,
 * instead of widening every DTO to carry a `JsonObject` field, keeps the typed
 * path — installing by URL — as the one Retrofit validates directly.
 */
fun JsonObject.toAddonEntity(base: String, addedAt: Long): AddonEntity? {
    val manifest = runCatching {
        HttpClients.json.decodeFromJsonElement(StremioManifestDto.serializer(), this)
    }.getOrNull() ?: return null

    if (manifest.id.isBlank() || manifest.name.isBlank()) return null

    return AddonEntity(
        base = base,
        manifestJson = toString(),
        addonId = manifest.id,
        name = manifest.name,
        description = manifest.description,
        logoUrl = manifest.logo,
        version = manifest.version,
        types = manifest.types,
        resources = manifest.resources.map { it.name }.filter { it.isNotBlank() },
        idPrefixes = manifest.idPrefixes,
        configurable = manifest.behaviorHints?.configurable == true,
        addedAt = addedAt,
    )
}

/** A Firebase record — already this app's own shape, `{base, manifest}`. */
fun SyncedAddonDto.toEntityOrNull(now: Long): AddonEntity? {
    val manifest = manifest ?: return null
    val addedAtMs = addedAt?.let(Iso8601::parseOrNull)
    return manifest.toAddonEntity(base, addedAtMs ?: now)
}

fun AddonEntity.toSyncedDto() = SyncedAddonDto(
    base = base,
    manifest = runCatching {
        HttpClients.json.parseToJsonElement(manifestJson) as? JsonObject
    }.getOrNull(),
    addedAt = Iso8601.format(addedAt),
)
