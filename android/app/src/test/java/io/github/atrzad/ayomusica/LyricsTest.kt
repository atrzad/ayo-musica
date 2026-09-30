package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.lyrics.Id3Lyrics
import io.github.atrzad.ayomusica.lyrics.LrcLib
import io.github.atrzad.ayomusica.lyrics.LrcLibResult
import io.github.atrzad.ayomusica.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LyricsTest {
    @Test
    fun lrcWithMetadataOffsetRepeatsAndWordStamps() {
        val lyrics = Lyrics.parse("[ar:Seu Pereira]\n[offset:+250]\n[00:26.72] Hoje eu acordei assim\n" +
            "[00:29.81][01:10.5]Me <00:30.10>sentindo obsoleto\n\n[01:05]Feito um CD\n")
        assertTrue(lyrics.synced)
        assertEquals(250L, lyrics.offsetMs)
        assertEquals(Lyrics.Line(26720, "Hoje eu acordei assim"), lyrics.lines[0])
        assertEquals(Lyrics.Line(29810, "Me sentindo obsoleto"), lyrics.lines[1])
        assertEquals(listOf(26720L, 29810L, 65000L, 70500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun plainTextStaysPlain() {
        val lyrics = Lyrics.parse("\nTodos esses que aí estão\nEles passarão, eu passarinho\n\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("Todos esses que aí estão", "Eles passarão, eu passarinho"), lyrics.lines.map { it.text })
    }

    @Test
    fun currentLineFollowsPositionAndOffset() {
        val lyrics = Lyrics.parse("[00:10.00]um\n[00:20.00]dois\n[00:30.00]três")
        assertEquals(-1, lyrics.currentIndex(5_000))
        assertEquals(1, lyrics.currentIndex(20_500))
        assertEquals(1, lyrics.copy(offsetMs = 1000).currentIndex(19_200))  // positive offset: earlier
        assertEquals(-1, Lyrics.parse("sem tempo").currentIndex(50_000))
    }

    @Test
    fun lrclibPrefersSyncedAndChecksDuration() {
        val results = listOf(
            LrcLibResult(id = 1, duration = 117.0, plainLyrics = "só texto"),
            LrcLibResult(id = 2, duration = 118.0, syncedLyrics = "[00:01.00]com tempo", plainLyrics = "com tempo"),
            LrcLibResult(id = 3, duration = 300.0, syncedLyrics = "[00:01.00]outra versão"),
        )
        assertEquals("[00:01.00]com tempo", LrcLib.pick(results, 117)?.synced)
        assertNull(LrcLib.pick(results.take(1), 200))
    }

    @Test
    fun lrclibFallsBackToSearch() {
        val calls = mutableListOf<String>()
        val lib = LrcLib { url ->
            calls += url
            if ("/get?" in url) null else """[{"id":7,"duration":224.0,"plainLyrics":"letra","syncedLyrics":null}]"""
        }
        val found = lib.find("Seu Pereira", "Obsoleto", durationMs = 224_000)
        assertEquals("letra", found?.plain)
        assertTrue(calls[0].contains("duration=224"))
        assertTrue(calls[1].contains("/search?"))
    }

    @Test
    fun embeddedUsltAndSyltFromId3() {
        val uslt = frame("USLT", byteArrayOf(3) + "por".toByteArray() + byteArrayOf(0) +
            "[00:02.00]primeira\n[00:04.00]segunda".toByteArray())
        val fromUslt = Id3Lyrics.read(tag(uslt).inputStream())
        assertTrue(fromUslt!!.synced)
        assertEquals(listOf(2000L, 4000L), fromUslt.lines.map { it.timeMs })

        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(3) + "por".toByteArray() + byteArrayOf(2, 1, 0))
            write("oi".toByteArray() + byteArrayOf(0) + int(1000))
            write("tchau".toByteArray() + byteArrayOf(0) + int(3000))
        }.toByteArray()
        val fromSylt = Id3Lyrics.read(tag(frame("SYLT", body)).inputStream())
        assertEquals(listOf(Lyrics.Line(1000, "oi"), Lyrics.Line(3000, "tchau")), fromSylt!!.lines)
        assertNull(Id3Lyrics.read("RIFF....".toByteArray().inputStream()))
    }

    private fun int(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun frame(id: String, body: ByteArray) = id.toByteArray() + int(body.size) + byteArrayOf(0, 0) + body

    private fun tag(frames: ByteArray): ByteArray {
        val size = frames.size
        val syncsafe = byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
            (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe + frames
    }
}
