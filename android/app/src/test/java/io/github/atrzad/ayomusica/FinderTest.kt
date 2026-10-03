package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.lyrics.Clean
import io.github.atrzad.ayomusica.lyrics.LrcLib
import io.github.atrzad.ayomusica.lyrics.LyricsFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderTest {
    private fun song(title: String, artist: String, file: String = "$title.mp3", seconds: Int = 224, album: String = "") =
        Song(1, title, artist, album, 1, durationMs = seconds * 1000L, displayName = file)

    @Test
    fun youtubeNamesBecomeClean() {
        assertEquals("Obsoleto", Clean.coreTitle("Obsoleto (Official Video)(MP3_160K)"))
        assertEquals(listOf("Tyler, The Creator"), Clean.artists("Tyler |  The Creator - Topic"))
        assertEquals(listOf("Ruas Mc", "MELI", "Bradoc"), Clean.artists("Ruas Mc, MELI & Bradoc"))
        assertEquals("Doom" to "Rapp Snitch Knishes", Clean.fromFileName("03. Doom - Rapp Snitch Knishes (Official Audio).mp3")
            .let { it.first to Clean.coreTitle(it.second) })
        assertEquals(emptyList<String>(), Clean.artists("<unknown>"))
    }

    @Test
    fun readingsCoverTitleWithArtistAndPipes() {
        val readings = Clean.readings(song("OTÁRIO _ Seu Pereira", "<unknown>", "OTÁRIO _ Seu Pereira(MP3_160K).mp3"))
        assertTrue(readings.toString(), Clean.Reading("Seu Pereira", "OTÁRIO") in readings)
        val prefixed = Clean.readings(song("Deftones - Rx Queen", "Deftones"))
        assertEquals(Clean.Reading("Deftones", "Rx Queen"), prefixed.first())
    }

    @Test
    fun findsSyncedLyricsThroughSearchWhenExactLookupFails() {
        val calls = mutableListOf<String>()
        val finder = LyricsFinder(LrcLib { url ->
            calls += url
            when {
                "/get?" in url -> null
                "artist_name=" in url -> "[]"
                else -> """[{"id":9,"trackName":"Obsoleto","artistName":"Seu Pereira e Coletivo 401","duration":224.0,
                           "plainLyrics":"letra","syncedLyrics":"[00:26.72]Hoje eu acordei assim"},
                          {"id":8,"trackName":"Obsoleto","artistName":"Outro","duration":120.0,"syncedLyrics":"[00:01.00]x"}]"""
            }
        })
        val found = finder.find(song("Obsoleto (Official Video)", "Seu Pereira e Coletivo 401", "Obsoleto(MP3_160K).mp3"))
        assertEquals(9L, found?.id)
        assertTrue(calls.first().contains("track_name=Obsoleto&") || calls.first().contains("track_name=Obsoleto"))
    }

    @Test
    fun rejectsOtherRecordings() {
        val finder = LyricsFinder(LrcLib { url ->
            if ("/get?" in url) null
            else """[{"id":3,"trackName":"Obsoleto","artistName":"Seu Pereira","duration":300.0,"syncedLyrics":"[00:01.00]ao vivo"},
                     {"id":4,"trackName":"Outra música","artistName":"Seu Pereira","duration":224.0,"syncedLyrics":"[00:01.00]x"}]"""
        })
        assertNull(finder.find(song("Obsoleto", "Seu Pereira")))
    }

    @Test
    fun manualSearchPutsSyncedAndCloseDurationsFirst() {
        val finder = LyricsFinder(LrcLib {
            """[{"id":1,"trackName":"A","duration":300.0,"plainLyrics":"x"},
                {"id":2,"trackName":"A","duration":226.0,"syncedLyrics":"[00:01.00]x"},
                {"id":3,"trackName":"A","duration":224.0,"syncedLyrics":"[00:01.00]x"},
                {"id":4,"trackName":"A","duration":224.0}]"""
        })
        assertEquals(listOf(3L, 2L, 1L), finder.search("a", song("A", "B")).map { it.id })
    }
}

class AlignTest {
    @org.junit.Test
    fun plainLyricsGetTimesFromHeardWords() {
        val plain = io.github.atrzad.ayomusica.lyrics.Lyrics.parse("Hoje eu acordei assim\nMe sentindo obsoleto\n\nFeito um CD na estante")
        val tokens = listOf("26700\t27000\t Hoje", "27000\t27200\t eu", "27200\t27600\t acor", "27600\t28000\tdei",
            "28000\t29000\t assim", "29100\t29400\t uh", "29800\t30000\t me", "30000\t30500\t sentido",
            "30500\t31500\t obsoleto", "33000\t33300\t feito", "33300\t33400\t um", "33400\t33900\t CD").joinToString("\n")
        val words = io.github.atrzad.ayomusica.lyrics.Align.words(tokens)
        org.junit.Assert.assertEquals("acordei", words[2].text)
        val (synced, share) = io.github.atrzad.ayomusica.lyrics.Align.align(plain, words)!!
        org.junit.Assert.assertEquals(listOf(26700L, 29800L, 33000L), synced.lines.map { it.timeMs })
        org.junit.Assert.assertTrue(share > 0.7)
    }

    @org.junit.Test
    fun languageGuess() {
        org.junit.Assert.assertEquals("pt", io.github.atrzad.ayomusica.lyrics.Align.language(
            "eu não sei o que você quer de mim meu amor, tudo que tem pra gente é isso"))
        org.junit.Assert.assertEquals("auto", io.github.atrzad.ayomusica.lyrics.Align.language("oi"))
    }
}
