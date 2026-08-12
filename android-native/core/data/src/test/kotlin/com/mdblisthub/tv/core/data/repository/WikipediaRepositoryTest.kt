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
}
