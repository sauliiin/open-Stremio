package com.mdblisthub.tv.core.model

/**
 * Which palette the interface is painted in.
 *
 * Lives in `core:model` rather than beside the colours in `core:ui` because
 * both ends of the preference need it: `core:ui` owns the palette it selects,
 * and `core:data` owns the store it is persisted in, and neither of those two
 * modules can see the other.
 *
 * The order is the order the "tema" button cycles through.
 */
enum class HubThemeVariant { NORMAL, CYBERPUNK, NETFLIXY }
