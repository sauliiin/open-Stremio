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
enum class HubThemeVariant { NORMAL, CYBERPUNK, NETFLIXY, PRIMEFLY, CYBERFLIX, OPTIMUS_PRIME }

/**
 * Who answers for the account's own library: watchlist, collection, watched,
 * up next and the paused sessions behind "continue watching".
 *
 * One choice covering all five rather than one per row, because the five are
 * a single coherent picture of what a person has and is watching — filling
 * them from two accounts at once would put a title on the watchlist of one
 * and the history of the other with nothing tying them together.
 *
 * Everything *else* still comes from mdblist regardless: the user's own
 * lists, aggregated ratings, recommendations and search. Trakt is an
 * alternative source for the personal library, not a replacement account.
 */
enum class LibraryProvider { MDBLIST, TRAKT }
