package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class StremioApiResponse<T>(
    val result: T? = null,
    val error: StremioApiErrorDto? = null,
)

@Serializable
data class StremioApiErrorDto(
    val code: Int = 0,
    val message: String = "",
)

@Serializable
data class StremioLoginRequest(
    val type: String = "Login",
    val email: String,
    val password: String,
)

@Serializable
data class StremioLoginResult(
    val authKey: String = "",
    val user: StremioUserDto? = null,
)

@Serializable
data class StremioUserDto(val email: String? = null)

@Serializable
data class StremioCollectionRequest(
    val type: String = "AddonCollectionGet",
    val authKey: String,
    val update: Boolean = true,
)

@Serializable
data class StremioCollectionResult(
    val addons: List<StremioCollectionEntryDto> = emptyList(),
)

@Serializable
data class StremioCollectionEntryDto(
    val transportUrl: String? = null,
    val manifest: JsonObject? = null,
    val flags: JsonObject? = null,
    val transportName: String? = null,
)

@Serializable
data class StremioCollectionSetRequest(
    val type: String = "AddonCollectionSet",
    val authKey: String,
    val addons: List<StremioCollectionEntryDto>,
)

@Serializable
data class StremioSuccessResult(val success: Boolean = false)

@Serializable
data class StremioLogoutRequest(
    val type: String = "Logout",
    val authKey: String,
)
