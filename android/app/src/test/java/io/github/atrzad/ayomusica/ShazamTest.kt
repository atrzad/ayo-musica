package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.analyzer.Shazam
import io.github.atrzad.ayomusica.analyzer.ShazamSignature
import io.github.atrzad.ayomusica.analyzer.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShazamTest {
    private fun samples(file: File): ShortArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(bytes.remaining()).also { bytes.get(it) }
    }

    /** Same signature as SongRec's reference code for the same audio. Run with AYO_SHAZAM_RAW and AYO_SHAZAM_REF. */
    @Test
    fun matchesSongRec() {
        val raw = System.getenv("AYO_SHAZAM_RAW")
        assumeTrue(raw != null)
        val reference = File(System.getenv("AYO_SHAZAM_REF")!!).readLines()
        val signature = ShazamSignature(samples(File(raw!!)))
        assertEquals(reference[0], signature.bands.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value.size}" })
        assertEquals(reference[1], signature.uri())
    }

    @Test
    fun silenceIsNotSent() {
        var asked = false
        assertNull(Shazam { _, _ -> asked = true; "{}" }.recognize(ShortArray(16000 * 12)))
        assertEquals(false, asked)
    }

    @Test
    fun readsShazamAnswer() {
        val body = """{"matches":[{"id":"1"}],"track":{"key":"42","title":"Lithonia","subtitle":"Childish Gambino",
            "images":{"coverart":"https://is1.mzstatic.com/x/400x400cc.jpg"},"genres":{"primary":"Alternative"},
            "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Atavista"},{"title":"Released","text":"2024"}]}]}}"""
        val found = Shazam.parse(body)!!
        assertEquals(Source.Shazam, found.source)
        assertEquals("Childish Gambino", found.artist)
        assertEquals("Atavista", found.album)
        assertEquals(2024, found.year)
        assertEquals("https://is1.mzstatic.com/x/1000x1000cc.jpg", found.covers.first())
        assertNull(Shazam.parse("""{"matches":[],"tagid":"x"}"""))
    }

    /** Against Shazam itself. Run with AYO_SHAZAM_RAW and AYO_LIVE_SOURCES=1. */
    @Test
    fun liveRecognition() {
        val raw = System.getenv("AYO_SHAZAM_RAW")
        assumeTrue(raw != null && System.getenv("AYO_LIVE_SOURCES") != null)
        val found = Shazam().recognize(samples(File(raw!!)))
        println("Shazam: $found")
        assertEquals(true, found?.title?.contains("Lithonia", ignoreCase = true))
    }
}

class AcoustIdTest {
    @Test
    fun readsRecordingsAndPicksTheAlbum() {
        val body = """{"status":"ok","results":[{"id":"a","score":0.97,"recordings":[{"id":"rec1","title":"Olhares","duration":109,
            "artists":[{"name":"SonoTWS"}],"releasegroups":[
              {"id":"comp","title":"Rap BR Hits","type":"Album","secondarytypes":["Compilation"]},
              {"id":"street","title":"Street Talk","type":"Album"}]}]},
            {"id":"b","score":0.3,"recordings":[{"id":"rec2","title":"Outra"}]}]}"""
        val found = io.github.atrzad.ayomusica.analyzer.AcoustId.parse(body)
        assertEquals(listOf("rec1"), found.map { it.id })  // low scores are left out
        assertEquals("Street Talk", found[0].album)
        assertEquals(97, found[0].confidence)
        assertEquals("https://coverartarchive.org/release-group/street/front-1200", found[0].covers.first())
    }

    @Test(expected = java.io.IOException::class)
    fun badKeyIsExplained() {
        io.github.atrzad.ayomusica.analyzer.AcoustId.parse("""{"status":"error","error":{"code":4,"message":"invalid API key"}}""")
    }

    @Test
    fun soundRecognitionScores() {
        val song = io.github.atrzad.ayomusica.data.Song(1, "Olhares", "SonoTWS", "", 1, durationMs = 109_000, displayName = "Olhares.mp3")
        val agrees = io.github.atrzad.ayomusica.analyzer.Candidate(Source.Shazam, "1", "Olhares", "SonoTWS", "Street Talk", confidence = 88)
        val other = agrees.copy(title = "Outra Coisa", artist = "Fulano")
        // The sound and the tags agree: applied on its own. They disagree: only for review.
        assertEquals(95, io.github.atrzad.ayomusica.analyzer.Analyzer.audioScore(agrees, song))
        assertEquals(88, io.github.atrzad.ayomusica.analyzer.Analyzer.audioScore(other, song))
    }
}
