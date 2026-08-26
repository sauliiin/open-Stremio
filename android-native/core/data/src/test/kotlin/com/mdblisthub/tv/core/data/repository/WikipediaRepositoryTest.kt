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
}
