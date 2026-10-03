package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.analyzer.Analyzer
import io.github.atrzad.ayomusica.analyzer.Candidate
import io.github.atrzad.ayomusica.analyzer.Deezer
import io.github.atrzad.ayomusica.analyzer.ITunes
import io.github.atrzad.ayomusica.analyzer.MetadataSource
import io.github.atrzad.ayomusica.analyzer.MusicBrainz
import io.github.atrzad.ayomusica.analyzer.Source
import io.github.atrzad.ayomusica.analyzer.Verdict
import io.github.atrzad.ayomusica.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

class SourcesTest {
    private fun song(title: String, artist: String, seconds: Int = 224, file: String = "$title.mp3") =
        Song(1, title, artist, "", 1, durationMs = seconds * 1000L, displayName = file)

    @Test
    fun readsITunes() {
        val body = """{"resultCount":1,"results":[{"wrapperType":"track","kind":"song","trackId":42,
            "artistName":"Seu Pereira e Coletivo 401","collectionName":"Obsoleto","trackName":"Obsoleto",
            "artworkUrl100":"https://is1.mzstatic.com/a/100x100bb.jpg","releaseDate":"2025-03-07T12:00:00Z",
            "primaryGenreName":"MPB","trackTimeMillis":223800},{"kind":"music-video","trackName":"Clipe"}]}"""
        val found = ITunes.parse(body)
        assertEquals(1, found.size)
        val c = found[0]
        assertEquals(Source.ITunes, c.source)
        assertEquals("Obsoleto", c.album)
        assertEquals(2025, c.year)
        assertEquals("MPB", c.genre)
        assertEquals(223, c.seconds)
        assertEquals("https://is1.mzstatic.com/a/1200x1200bb.jpg", c.covers.first())
    }

    @Test
    fun readsMusicBrainzAndPicksTheAlbum() {
        val body = """{"recordings":[{"id":"r1","title":"Obsoleto","length":224000,
            "artist-credit":[{"name":"Seu Pereira","joinphrase":" e "},{"name":"Coletivo 401"}],
            "tags":[{"name":"mpb","count":3},{"name":"rock","count":1}],
            "releases":[
              {"id":"comp","title":"Hits 2025","date":"2025-01-01","status":"Official",
               "release-group":{"id":"g0","primary-type":"Album","secondary-types":["Compilation"]}},
              {"id":"single","title":"Obsoleto","date":"2024-11-01","status":"Official",
               "release-group":{"id":"g1","primary-type":"Single"}},
              {"id":"album","title":"Obsoleto","date":"2025-03-07","status":"Official",
               "release-group":{"id":"g2","primary-type":"Album"},
               "artist-credit":[{"name":"Seu Pereira e Coletivo 401"}]}]}]}"""
        val c = MusicBrainz.parse(body).single()
        assertEquals("Seu Pereira e Coletivo 401", c.artist)
        assertEquals("Seu Pereira e Coletivo 401", c.albumArtist)
        assertEquals(2025, c.year)
        assertEquals("Mpb", c.genre)
        assertEquals(listOf("https://coverartarchive.org/release/album/front-1200",
            "https://coverartarchive.org/release-group/g2/front-1200"), c.covers)
        assertEquals("\"AC\\/DC \\- Live\"", MusicBrainz.quote("AC/DC - Live"))
    }

    @Test
    fun scoresCandidatesFromAnySource() {
        val wanted = song("Obsoleto", "Seu Pereira e Coletivo 401")
        val right = Candidate(Source.MusicBrainz, "1", "Obsoleto", "Seu Pereira e Coletivo 401", "Obsoleto", seconds = 224)
        val live = right.copy(id = "2", title = "Obsoleto (Ao Vivo)")
        val other = right.copy(id = "3", artist = "Outra Banda", seconds = 180)
        assertTrue(Analyzer.score(right, wanted) >= Analyzer.AUTO)
        assertTrue(Analyzer.score(live, wanted) < Analyzer.AUTO)  // a live version is never applied on its own
        assertEquals(0, Analyzer.score(other, wanted))
    }

    private class Fake(override val source: Source, val results: List<Candidate>, val fails: Boolean = false) : MetadataSource {
        var asked = 0
        override fun search(title: String, artist: String): List<Candidate> {
            asked++
            if (fails) throw IOException("fora do ar")
            return results
        }
    }

    @Test
    fun otherSourcesHelpWhenDeezerIsNotSure() {
        val wanted = song("Obsoleto", "Seu Pereira")
        val deezer = Fake(Source.Deezer, emptyList(), fails = true)
        val itunes = Fake(Source.ITunes, listOf(Candidate(Source.ITunes, "9", "Obsoleto", "Seu Pereira e Coletivo 401",
            "Obsoleto", year = 2025, seconds = 224, covers = listOf("c"))))
        val brainz = Fake(Source.MusicBrainz, emptyList())
        val (verdict, proposal) = Analyzer(listOf(deezer, itunes, brainz)).analyze(wanted)
        assertEquals(Verdict.Auto, verdict)
        assertEquals(Source.ITunes, proposal!!.source)
        assertEquals(2025, proposal.override.year)
        assertEquals("itunes", proposal.override.source)
        assertEquals(0, brainz.asked)  // sure enough already: MusicBrainz is not bothered
    }

    @Test(expected = IOException::class)
    fun allSourcesDownIsAnError() {
        Analyzer(Source.entries.map { Fake(it, emptyList(), fails = true) }).analyze(song("Obsoleto", "Seu Pereira"))
    }

    @Test
    fun nothingFound() {
        val (verdict, proposal) = Analyzer(Source.entries.map { Fake(it, emptyList()) }).analyze(song("Obsoleto", "Seu Pereira"))
        assertEquals(Verdict.NotFound, verdict)
        assertNull(proposal)
    }

    /** Against the real services. Run with AYO_LIVE_SOURCES=1. */
    @Test
    fun liveSources() {
        assumeTrue(System.getenv("AYO_LIVE_SOURCES") != null)
        for (source in listOf(Deezer(), ITunes(), MusicBrainz())) {
            val found = source.search("Construção", "Chico Buarque")
            val best = found.firstOrNull()?.let(source::complete)
            println("${source.source.label}: ${found.size} resultados; 1º = $best")
            assertTrue("${source.source.label} não achou", found.any { "constru" in it.title.lowercase() })
        }
    }
}
