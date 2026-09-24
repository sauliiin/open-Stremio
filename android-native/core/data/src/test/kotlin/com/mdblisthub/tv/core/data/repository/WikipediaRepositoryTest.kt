package com.mdblisthub.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class WikipediaRepositoryTest {

    @Test
    fun `english interface only uses english wikipedia`() {
        assertEquals(listOf("en"), wikipediaEditionsFor("en"))
        assertEquals(listOf("en"), wikipediaEditionsFor("en-US"))
    }

    @Test
    fun `portuguese interface falls back to english wikipedia`() {
        assertEquals(listOf("pt", "en"), wikipediaEditionsFor("pt"))
        assertEquals(listOf("pt", "en"), wikipediaEditionsFor("pt-BR"))
    }

    @Test
    fun `french interface falls back to english wikipedia`() {
        assertEquals(listOf("fr", "en"), wikipediaEditionsFor("fr"))
        assertEquals(listOf("fr", "en"), wikipediaEditionsFor("fr-FR"))
    }

    /**
     * Spanish had no case of its own and fell through to the English edition,
     * so a Spanish interface read an English biography. Same omission, and the
     * same shape, as the age sentence it sits above.
     */
    @Test
    fun `spanish reads the spanish edition first`() {
        assertEquals(listOf("es", "en"), wikipediaEditionsFor("es"))
        assertEquals(listOf("es", "en"), wikipediaEditionsFor("es-ES"))
        assertEquals(listOf("es", "en"), wikipediaEditionsFor("es-419"))
    }

    @Test
    fun `age sentence uses present tense for someone alive, in portuguese`() {
        // The exact age depends on today's date, so this only checks the
        // shape of the sentence — [ageAsOf] (private, exercised indirectly
        // here) covers the year-rollover arithmetic itself.
        val sentence = ageSentence("Carlos", "2000-01-01", null, "pt-BR")
        assertEquals(true, sentence != null && sentence.startsWith("Carlos tem "))
        assertEquals(true, sentence!!.endsWith(" anos."))
    }

    @Test
    fun `age sentence uses present tense for someone alive, in english`() {
        val sentence = ageSentence("Carlos", "2000-01-01", null, "en-US")
        assertEquals(true, sentence != null && sentence.startsWith("Carlos is "))
        assertEquals(true, sentence!!.endsWith(" years old."))
    }

    @Test
    fun `age sentence uses present tense for someone alive, in french`() {
        val sentence = ageSentence("Carlos", "2000-01-01", null, "fr-FR")
        assertEquals(true, sentence != null && sentence.startsWith("Carlos a "))
        assertEquals(true, sentence!!.endsWith(" ans."))
    }

    @Test
    fun `age sentence uses past tense for someone with a death date`() {
        assertEquals("Carlos morreu aos 20 anos.", ageSentence("Carlos", "2000-06-15", "2020-06-15", "pt-BR"))
        assertEquals("Carlos morreu aos 19 anos.", ageSentence("Carlos", "2000-06-15", "2020-06-14", "pt-BR"))
        assertEquals("Carlos died at 20.", ageSentence("Carlos", "2000-06-15", "2020-06-15", "en-US"))
        assertEquals("Carlos est décédé à l’âge de 20 ans.", ageSentence("Carlos", "2000-06-15", "2020-06-15", "fr-FR"))
    }

    @Test
    fun `age sentence is null without a birthday`() {
        assertEquals(null, ageSentence("Carlos", null, null, "pt-BR"))
        assertEquals(null, ageSentence("Carlos", "", "2020-06-15", "en-US"))
    }

    // ------------------------------------------------ the premiere clause

    /**
     * The sentence this feature exists for, in every language the interface
     * ships. Born 1977-03-10, released 2009-08-21: 32 on the release date, and
     * 31 shown — the deliberate filming offset, which is what these numbers
     * are really pinning. Still alive, so the first clause is present tense
     * and depends on today's date; hence the split assertion.
     */
    @Test
    fun `age at premiere is appended in every language`() {
        val cases = mapOf(
            "pt-BR" to Pair("Carlos tem ", " anos e possuía 31 anos quando o filme estreou."),
            "es-ES" to Pair("Carlos tiene ", " años y tenía 31 años cuando se estrenó la película."),
            "fr-FR" to Pair("Carlos a ", " ans et avait 31 ans à la sortie du film."),
            "en-US" to Pair("Carlos is ", " years old and was 31 when the film premiered."),
        )
        for ((tag, expected) in cases) {
            val sentence = ageSentence("Carlos", "1977-03-10", null, tag, releaseDate = "2009-08-21")
            assertEquals("$tag opening", true, sentence != null && sentence.startsWith(expected.first))
            assertEquals("$tag premiere clause", true, sentence!!.endsWith(expected.second))
        }
    }

    /** Saying *film* about a television series is wrong in all four. */
    @Test
    fun `a series premiere says series, not film`() {
        val cases = mapOf(
            "pt-BR" to " anos e possuía 31 anos quando a série estreou.",
            "es-ES" to " años y tenía 31 años cuando se estrenó la serie.",
            "fr-FR" to " ans et avait 31 ans à la sortie de la série.",
            "en-US" to " years old and was 31 when the series premiered.",
        )
        for ((tag, tail) in cases) {
            val sentence = ageSentence(
                "Carlos", "1977-03-10", null, tag,
                releaseDate = "2009-08-21", isSeries = true,
            )
            assertEquals(tag, true, sentence!!.endsWith(tail))
        }
    }

    /** Someone who has died still has an age at the premiere, and it still reads. */
    @Test
    fun `a death date and a premiere both appear`() {
        assertEquals(
            "Carlos morreu aos 43 anos e possuía 31 anos quando o filme estreou.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "pt-BR", releaseDate = "2009-08-21"),
        )
        assertEquals(
            "Carlos died at 43 and was 31 when the film premiered.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "en-US", releaseDate = "2009-08-21"),
        )
    }

    /**
     * The clause is dropped when it would say the same number twice. With the
     * filming offset in place the usual way to land there is a posthumous
     * release: died at 43 in 2020, released 2021, which computes 44 and shows
     * 43. Saying "morreu aos 43 anos e possuía 43 anos quando o filme estreou"
     * is worse than saying nothing.
     */
    @Test
    fun `an age that has not changed since the premiere is left unsaid`() {
        assertEquals(
            "Carlos morreu aos 43 anos.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "pt-BR", releaseDate = "2021-08-21"),
        )
    }

    /** The birthday still decides the number, on either side of it. */
    @Test
    fun `the premiere age respects the birthday within the year`() {
        assertEquals(
            true,
            ageSentence("Carlos", "1977-03-10", null, "pt-BR", releaseDate = "2009-03-09")!!
                .endsWith("possuía 30 anos quando o filme estreou."),
        )
        assertEquals(
            true,
            ageSentence("Carlos", "1977-03-10", null, "pt-BR", releaseDate = "2009-03-10")!!
                .endsWith("possuía 31 anos quando o filme estreou."),
        )
    }

    /**
     * A credit on a title released before the person was born is a TMDB data
     * error, not something to render as a negative age.
     */
    @Test
    fun `a premiere before the birth is left unsaid`() {
        assertEquals(
            true,
            ageSentence("Carlos", "1977-03-10", null, "en-US", releaseDate = "1970-01-01")!!
                .endsWith(" years old."),
        )
    }

    /**
     * The offset's own edge: a title released inside the person's first year
     * computes 0 and would show -1. There is no age to state there, so the
     * clause goes rather than the number.
     */
    @Test
    fun `an offset that would go negative is left unsaid`() {
        assertEquals(
            "Carlos morreu aos 43 anos.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "pt-BR", releaseDate = "1977-06-01"),
        )
    }

    /** No title in hand, or a blank date, leaves the original sentence untouched. */
    @Test
    fun `no release date leaves the sentence as it was`() {
        assertEquals(
            "Carlos morreu aos 43 anos.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "pt-BR"),
        )
        assertEquals(
            "Carlos morreu aos 43 anos.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "pt-BR", releaseDate = ""),
        )
    }

    /**
     * Spanish had no case of its own at all — it fell through to English, so
     * a Spanish interface read "Carlos is 43 years old." This is the
     * regression guard for that.
     */
    @Test
    fun `spanish is not english`() {
        assertEquals(
            "Carlos murió a los 43 años.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "es-ES"),
        )
        assertEquals(
            "Carlos murió a los 43 años.",
            ageSentence("Carlos", "1977-03-10", "2020-06-15", "es"),
        )
    }
}
