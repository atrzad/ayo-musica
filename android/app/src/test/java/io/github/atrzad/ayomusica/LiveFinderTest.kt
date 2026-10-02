package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.lyrics.LyricsFinder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Against the real LRCLIB, with messy tags from a real library. Run with AYO_LIVE=<songs.json>. */
class LiveFinderTest {
    @Serializable
    data class Sample(val title: String, val artist: String, val album: String, val file: String, val seconds: Int)

    @Test
    fun realLibrary() {
        val path = System.getenv("AYO_LIVE")
        assumeTrue(path != null)
        val samples = Json.decodeFromString<List<Sample>>(File(path!!).readText())
        val finder = LyricsFinder()
        var synced = 0
        var plain = 0
        samples.forEachIndexed { index, sample ->
            val title = sample.title.ifBlank { sample.file.substringBeforeLast('.') }  // what MediaStore shows
            val song = Song(index.toLong(), title, sample.artist, sample.album, 1, durationMs = sample.seconds * 1000L,
                displayName = sample.file)
            val found = runCatching { finder.find(song) }.getOrNull()
            when {
                found?.synced == true -> synced++
                found != null -> plain++
            }
            println("%-55s → %s".format(sample.file.take(55), found?.let {
                "${if (it.synced) "SINCRONIZADA" else "texto"}: ${it.artistName} – ${it.trackName}" } ?: "nada"))
        }
        println("RESUMO: ${samples.size} músicas, $synced sincronizadas, $plain só texto, ${samples.size - synced - plain} sem letra")
    }
}
