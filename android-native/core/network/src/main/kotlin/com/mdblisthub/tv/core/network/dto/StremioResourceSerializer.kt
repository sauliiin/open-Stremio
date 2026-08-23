package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A manifest's `resources` array mixes two shapes: the bare string `"stream"`
 * and the object form `{"name":"stream","types":[...],"idPrefixes":[...]}`.
 * Only the name is used downstream, so both collapse to it here rather than
 * forcing every caller to branch.
 */
object StremioResourceSerializer : KSerializer<StremioResourceDto> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StremioResource", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): StremioResourceDto {
        val input = decoder as? JsonDecoder ?: return StremioResourceDto(decoder.decodeString())
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> StremioResourceDto(element.content)
            is JsonObject -> StremioResourceDto(element["name"]?.jsonPrimitive?.content.orEmpty())
            else -> StremioResourceDto("")
        }
    }

    override fun serialize(encoder: Encoder, value: StremioResourceDto) {
        encoder.encodeString(value.name)
    }
}

/** The JSON body both library write endpoints take. */
@kotlinx.serialization.Serializable
data class LibraryWriteDto(
    val movies: List<LibraryKeyDto> = emptyList(),
    val shows: List<LibraryKeyDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class LibraryKeyDto(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val seasons: List<LibraryWriteSeasonDto>? = null,
)

@kotlinx.serialization.Serializable
data class LibraryWriteSeasonDto(
    val number: Int,
    val episodes: List<LibraryWriteEpisodeDto>? = null,
)

@kotlinx.serialization.Serializable
data class LibraryWriteEpisodeDto(val number: Int)
