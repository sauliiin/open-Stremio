package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.SubtitleCue
import com.mdblisthub.tv.core.model.SubtitleTrack
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Turns a downloaded subtitle file into cues this app can draw itself.
 *
 * Media3 has perfectly good parsers for all of these formats. They are not
 * used because they parse *into the player* — the cues become samples in the
 * video's own timeline, which is what made changing the synchronization offset
 * cost a full re-prepare. Parsing here instead is what lets the offset be a
 * number the UI adds at lookup time.
 *
 * Everything about this is deliberately forgiving. Addon subtitles are
 * scraped, re-encoded and re-uploaded by strangers: a file that declares one
 * format and contains another, or that ends mid-cue, is normal. A malformed
 * block is skipped rather than failing the file, because 900 good lines with
 * one bad one is a working subtitle.
 */
object SubtitleFileParser {

    fun parse(bytes: ByteArray, declaredEncoding: String? = null): SubtitleTrack {
        val text = decode(unwrap(bytes), declaredEncoding)
        val cues = when {
            text.startsWith(WEBVTT_MAGIC) -> parseBlocks(text)
            text.contains(ASS_EVENTS, ignoreCase = true) ||
                text.contains(ASS_DIALOGUE, ignoreCase = true) -> parseAss(text)
            else -> parseBlocks(text)
        }
        return SubtitleTrack(cues)
    }

    // ------------------------------------------------------------ container

    /**
     * Unpacks the archive an addon may have handed back instead of a subtitle.
     *
     * OpenSubtitles serves the same "download" URL as plain text, as a zip or
     * as gzip depending on the mirror, and nothing in the response says which
     * — the Kodi addons that do this reliably all sniff the magic bytes, so
     * this does too. Without it a zipped file reaches the text parser as
     * binary, yields no cues, and looks exactly like "the subtitle is broken".
     *
     * Anything that fails to unpack is returned untouched: a false positive on
     * two bytes should degrade to "try parsing it as text", never to an error.
     */
    private fun unwrap(bytes: ByteArray): ByteArray = when {
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
            runCatching { unzip(bytes) }.getOrNull() ?: bytes

        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte() ->
            runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrNull() ?: bytes

        else -> bytes
    }

    /** The first subtitle-looking entry, or the first entry at all. */
    private fun unzip(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var fallback: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val content = zip.readBytes()
                if (SUBTITLE_SUFFIXES.any { entry.name.endsWith(it, ignoreCase = true) }) return content
                if (fallback == null) fallback = content
            }
            return fallback
        }
    }

    // ------------------------------------------------------------- encoding

    /**
     * Order of trust: a byte-order mark, then UTF-8, then whatever the addon
     * *said* it was, then Latin-1.
     *
     * The declared charset used to come second and that was wrong. What the
     * addon declares is routinely a lie — OpenSubtitles serves this exact
     * film's Brazilian subtitle tagged `CP1252` while the bytes are plainly
     * UTF-8 — and the lie is not harmless, because windows-1252 can decode
     * *any* byte sequence: trusting it never fails, it just silently turns
     * every "ç" into "Ã§". A strict UTF-8 decode, by contrast, cannot succeed
     * by accident on a Latin-1 file, so succeeding *is* the proof. Only once
     * that has failed is the declared charset worth consulting.
     */
    private fun decode(bytes: ByteArray, declaredEncoding: String?): String {
        bomCharset(bytes)?.let { (charset, offset) ->
            return String(bytes, offset, bytes.size - offset, charset)
        }

        strictDecode(bytes, Charsets.UTF_8)?.let { return it }

        declaredEncoding
            ?.let(::charsetOrNull)
            ?.let { charset -> strictDecode(bytes, charset)?.let { return it } }

        return String(bytes, FALLBACK_CHARSET)
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE to 2

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE to 2

        else -> null
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name.trim())
    } catch (_: IllegalCharsetNameException) {
        null
    } catch (_: UnsupportedCharsetException) {
        null
    }

    /** Null when the bytes are not valid in this charset, rather than U+FFFD. */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    // -------------------------------------------------------------- SRT/VTT

    /**
     * Both formats are the same shape: blocks separated by blank lines, each
     * holding a `-->` line and the text under it. The differences that matter
     * (comma vs dot for the fraction, an optional index line, VTT's trailing
     * cue settings) are all absorbed by being lenient about them.
     */
    private fun parseBlocks(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()

        for (block in text.split(BLANK_LINE)) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            val timingIndex = lines.indexOfFirst { it.contains(ARROW) }
            if (timingIndex < 0) continue

            val (start, end) = parseTimingLine(lines[timingIndex]) ?: continue
            val body = lines.drop(timingIndex + 1).joinToString("\n")
            val clean = cleanMarkup(body)
            if (clean.isNotEmpty()) cues += SubtitleCue(start, end, clean)
        }
        return cues
    }

    private fun parseTimingLine(line: String): Pair<Long, Long>? {
        val halves = line.split(ARROW)
        if (halves.size < 2) return null

        val start = parseTimestamp(halves[0]) ?: return null
        // VTT appends cue settings ("align:middle line:90%") after the end
        // time, on the same line.
        val end = parseTimestamp(halves[1].trim().substringBefore(' ')) ?: return null
        return if (end > start) start to end else null
    }

    /**
     * One parser for all three formats: `HH:MM:SS,mmm` (SRT),
     * `[HH:]MM:SS.mmm` (VTT) and `H:MM:SS.cc` (ASS, centiseconds). Padding the
     * fraction to three digits is what makes the last of those work — ASS's
     * ".40" is 400ms, not 40.
     */
    private fun parseTimestamp(raw: String): Long? {
        val match = TIMESTAMP.find(raw.trim()) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured

        val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        val h = hours.ifEmpty { "0" }.toLongOrNull() ?: return null
        val m = minutes.toLongOrNull() ?: return null
        val s = seconds.toLongOrNull() ?: return null

        return ((h * 3600 + m * 60 + s) * 1000) + millis
    }

    // ------------------------------------------------------------------ ASS

    /**
     * ASS keeps its cues in `Dialogue:` rows whose column order is declared by
     * a `Format:` line, so the indices are read rather than assumed — some
     * files reorder them. Text is always last and may itself contain commas,
     * which is why the split is bounded by the column count.
     */
    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var startColumn = ASS_DEFAULT_START
        var endColumn = ASS_DEFAULT_END
        var textColumn = ASS_DEFAULT_TEXT
        var columns = ASS_DEFAULT_TEXT + 1

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Format:", ignoreCase = true) -> {
                    val fields = trimmed.removePrefix("Format:").removePrefix("format:")
                        .split(',')
                        .map { it.trim().lowercase() }
                    val start = fields.indexOf("start")
                    val end = fields.indexOf("end")
                    val body = fields.indexOf("text")
                    if (start >= 0 && end >= 0 && body >= 0) {
                        startColumn = start
                        endColumn = end
                        textColumn = body
                        columns = fields.size
                    }
                }

                trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = trimmed.substringAfter(':').split(',', limit = columns)
                    if (fields.size <= maxOf(startColumn, endColumn, textColumn)) continue

                    val start = parseTimestamp(fields[startColumn]) ?: continue
                    val end = parseTimestamp(fields[endColumn]) ?: continue
                    if (end <= start) continue

                    val clean = cleanMarkup(fields[textColumn])
                    if (clean.isNotEmpty()) cues += SubtitleCue(start, end, clean)
                }
            }
        }
        return cues
    }

    // ---------------------------------------------------------------- text

    /**
     * Strips everything that would otherwise be drawn literally.
     *
     * Nothing of value is lost by dropping the styling: this app forces its
     * own caption look (yellow, fixed size, drop shadow) on every subtitle
     * regardless of what the file asks for, exactly as the Media3 renderer it
     * replaced was configured to do.
     */
    private fun cleanMarkup(raw: String): String = raw
        .replace(ASS_OVERRIDE, "")
        .replace(ASS_NEWLINE, "\n")
        .replace(ASS_HARD_SPACE, " ")
        .replace(HTML_TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()

    private const val WEBVTT_MAGIC = "WEBVTT"
    private const val ASS_EVENTS = "[events]"
    private const val ASS_DIALOGUE = "dialogue:"
    private const val ARROW = "-->"
    private const val ASS_DEFAULT_START = 1
    private const val ASS_DEFAULT_END = 2
    private const val ASS_DEFAULT_TEXT = 9
    private val SUBTITLE_SUFFIXES = listOf(".srt", ".vtt", ".ass", ".ssa", ".sub")

    private val FALLBACK_CHARSET: Charset = Charset.forName("windows-1252")
    private val BLANK_LINE = Regex("""\r?\n[ \t]*\r?\n""")
    private val TIMESTAMP = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")
    /**
     * The closing brace is escaped, and that is not cosmetic.
     *
     * Android's `java.util.regex` is backed by ICU, which rejects a bare `}`
     * as a dangling quantifier where the desktop JVM quietly accepts it. Since
     * these patterns are `val`s on an object, the failure surfaced as an
     * `ExceptionInInitializerError` on the very first parse — taking down
     * *every* subtitle on *every* device, while a JVM unit test of the same
     * parser passed. The bug is invisible off-device by construction.
     */
    private val ASS_OVERRIDE = Regex("""\{[^}]*\}""")
    private val ASS_NEWLINE = Regex("""\\[Nn]""")
    private val ASS_HARD_SPACE = Regex("""\\h""")
    private val HTML_TAG = Regex("""<[^>]*>""")
}
