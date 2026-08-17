package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.StreamKind
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.network.dto.OpenSubtitlesItemDto
import com.mdblisthub.tv.core.network.dto.StremioStreamDto
import com.mdblisthub.tv.core.network.dto.StremioSubtitleDto
import com.mdblisthub.tv.core.network.dto.WyzieItemDto
import java.util.Locale
import kotlin.math.roundToInt

private val QUALITY_ORDER = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p")
private val QUALITY_4K = Regex("""\b(2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE)
private val QUALITY_ANY = Regex("""\b(1440|1080|720|480|360)p\b""", RegexOption.IGNORE_CASE)
private val SIZE_TEXT = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB|GiB|MiB)""", RegexOption.IGNORE_CASE)

/**
 * Normalises an addon's stream entry.
 *
 * Unlike the browser build there is no container blocklist here. ExoPlayer opens
 * MKV, AVI, TS and HLS, so anything with a URL is simply playable — the whole
 * reason this app is native rather than a page.
 */
fun StremioStreamDto.toPlayable(addon: Addon, index: Int): PlayableStream {
    val nameLines = lines(name)
    val titleLines = lines(title ?: description)

    // Torrentio and its imitators put the release name on the first line of
    // `title` and the seeders/size/source line under it, with `name` holding
    // the addon tag and quality. Everything that is not the headline is detail.
    val headline = titleLines.firstOrNull()
        ?: behaviorHints?.filename
        ?: nameLines.joinToString(" · ").takeIf { it.isNotBlank() }
        ?: "Stream"
    val detail = (titleLines.drop(1) + nameLines).joinToString(" · ").takeIf { it.isNotBlank() }
    val label = "${name.orEmpty()} ${title.orEmpty()} ${behaviorHints?.filename.orEmpty()}"

    val base = PlayableStream(
        key = "${addon.base}#$index",
        addon = addon.name,
        title = headline,
        detail = detail,
        quality = qualityOf(label),
        size = sizeOf(label, behaviorHints?.videoSize),
        // Carried separately from the display string above, which prefers
        // whatever size the addon wrote into its label. That text is the right
        // thing to *show* — it is what the user sees on the source list — but
        // the wrong thing to *measure* against, so the protocol's own per-file
        // number is kept intact here. See `PlayableStream.sizeBytes`.
        sizeBytesHint = behaviorHints?.videoSize?.takeIf { it > 0 },
        filename = behaviorHints?.filename,
        headers = behaviorHints?.proxyHeaders?.request.orEmpty(),
    )

    return when {
        !url.isNullOrBlank() -> base.copy(url = url, externalUrl = url, kind = StreamKind.DIRECT)

        !infoHash.isNullOrBlank() -> base.copy(
            externalUrl = magnet(infoHash!!, headline),
            kind = StreamKind.TORRENT,
        )

        else -> base.copy(
            externalUrl = ytId?.let { "https://www.youtube.com/watch?v=$it" } ?: externalUrl,
            kind = StreamKind.EXTERNAL,
        )
    }
}

/**
 * The order the cascade walks: sharpest first, then biggest file.
 *
 * Nothing here is ever shown as a list, so this ordering is the entire
 * expression of "which source do we watch" — it decides what plays, and the
 * ones below it only matter when the ones above fail.
 */
fun List<PlayableStream>.rankForPlayback(): List<PlayableStream> {
    val seen = mutableSetOf<String>()
    return this
        .filter { it.playable }
        .filter { seen.add(it.url!!) }
        .sortedWith(
            compareBy<PlayableStream> { stream ->
                stream.quality?.let(QUALITY_ORDER::indexOf)?.takeIf { it >= 0 } ?: QUALITY_ORDER.size
            }.thenByDescending { gigabytes(it.size) }
        )
}

private fun qualityOf(label: String): String? = when {
    QUALITY_4K.containsMatchIn(label) -> "2160p"
    else -> QUALITY_ANY.find(label)?.groupValues?.get(1)?.let { "${it}p" }
}

private fun sizeOf(label: String, bytes: Long?): String? {
    SIZE_TEXT.find(label)?.let { match ->
        val value = match.groupValues[1].replace(',', '.')
        val unit = match.groupValues[2].replace("i", "", ignoreCase = true).uppercase()
        return "$value $unit"
    }
    if (bytes == null || bytes <= 0) return null

    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1) String.format(Locale.US, "%.2f GB", gb)
    else "${(bytes / 1024.0 / 1024.0).roundToInt()} MB"
}

private fun gigabytes(size: String?): Double {
    if (size == null) return 0.0
    val value = size.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
    return if (size.uppercase().contains("GB")) value else value / 1024
}

private fun magnet(infoHash: String, name: String): String {
    val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
    ).joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }

    return "magnet:?xt=urn:btih:$infoHash&dn=" +
        java.net.URLEncoder.encode(name, "UTF-8") + trackers
}

private fun lines(value: String?): List<String> =
    value.orEmpty().split('\n').map(String::trim).filter(String::isNotEmpty)

// ------------------------------------------------------------- subtitles

fun StremioSubtitleDto.toOption(addon: Addon, index: Int) = SubtitleOption(
    key = "${addon.base}#${id ?: index}",
    addon = addon.name,
    label = Languages.label(lang),
    lang = lang.orEmpty(),
    url = url,
    encoding = encoding,
    releaseHint = listOfNotNull(title, release, subFileName).firstOrNull { it.isNotBlank() },
)

/**
 * A search result names a file, not a downloadable URL — minting the real
 * one costs against OpenSubtitles.com's daily quota (see
 * [com.mdblisthub.tv.core.network.OpenSubtitlesApi.download]), so the
 * result's [url] is this marker plus the file id instead, and stays that
 * way until [com.mdblisthub.tv.core.data.repository.StreamsRepository]
 * actually resolves it for the one subtitle someone asked for.
 */
const val OPENSUBTITLES_FILE_SCHEME = "opensubtitles-file:"

/** Skips a result with no attached file — the API's search can return one. */
fun OpenSubtitlesItemDto.toOption(index: Int): SubtitleOption? {
    val file = attributes.files.firstOrNull() ?: return null
    val lang = attributes.language?.takeIf { it.isNotBlank() } ?: return null

    return SubtitleOption(
        key = "opensubtitles.com#${file.fileId}",
        addon = "OpenSubtitles.com",
        label = Languages.label(lang),
        lang = lang,
        url = "$OPENSUBTITLES_FILE_SCHEME${file.fileId}",
        releaseHint = attributes.release ?: file.fileName,
        popularity = attributes.downloadCount,
    )
}

/**
 * `normalizedLang` comes from the caller, not [WyzieItemDto.language]: Wyzie
 * is queried one language at a time — see [ApiConfig.WYZIE_BASE] — so which
 * bucket a result belongs to is already known before this runs, and trusting
 * that is simpler than re-deriving it from a field the API itself is
 * inconsistent about (`"pb"` for Brazilian Portuguese, which nothing else in
 * this app recognises as a Portuguese code).
 */
fun WyzieItemDto.toOption(index: Int, normalizedLang: String): SubtitleOption? {
    val downloadUrl = url?.takeIf { it.isNotBlank() } ?: return null

    return SubtitleOption(
        key = "wyzie#${id ?: index}",
        addon = "Wyzie",
        label = Languages.label(normalizedLang),
        lang = normalizedLang,
        url = downloadUrl,
        encoding = encoding,
        releaseHint = release ?: fileName,
        popularity = downloadCount,
    )
}

/**
 * OpenSubtitles mirrors hand the same file back under several ids, so the URL
 * is the only identity worth trusting.
 */
fun List<SubtitleOption>.rankSubtitles(): List<SubtitleOption> {
    val seen = mutableSetOf<String>()
    return this
        .filter { it.url.isNotBlank() && seen.add(it.url) }
        .sortedWith(compareBy({ Languages.rank(it.lang) }, { it.label }))
}

/** ISO 639-2/B codes the addons use, rendered readably in Portuguese. */
object Languages {

    private val NAMES = mapOf(
        "por" to "Português", "pob" to "Português (BR)", "pt" to "Português",
        "pt-br" to "Português (BR)",
        "eng" to "Inglês", "en" to "Inglês",
        "spa" to "Espanhol", "es" to "Espanhol",
        "fre" to "Francês", "fra" to "Francês", "fr" to "Francês",
        "ger" to "Alemão", "deu" to "Alemão", "de" to "Alemão",
        "ita" to "Italiano", "it" to "Italiano",
        "jpn" to "Japonês", "ja" to "Japonês",
        "kor" to "Coreano", "ko" to "Coreano",
        "chi" to "Chinês", "zho" to "Chinês", "zh" to "Chinês",
        "rus" to "Russo", "ru" to "Russo",
        "ara" to "Árabe", "ar" to "Árabe",
        "dut" to "Holandês", "nld" to "Holandês",
        "swe" to "Sueco", "nor" to "Norueguês", "dan" to "Dinamarquês", "fin" to "Finlandês",
        "pol" to "Polonês", "tur" to "Turco", "hin" to "Hindi", "heb" to "Hebraico",
        "ell" to "Grego",
    )

    fun label(code: String?): String {
        val key = code.orEmpty().lowercase().trim()
        return NAMES[key] ?: key.uppercase().ifBlank { "Desconhecido" }
    }

    /** Portuguese first, then English, then everything else. */
    fun rank(code: String?): Int {
        val key = code.orEmpty().lowercase()
        return when {
            key.startsWith("po") || key.startsWith("pt") -> 0
            key.startsWith("en") -> 1
            else -> 2
        }
    }
}
