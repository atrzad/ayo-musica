package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.data.Grouping
import io.github.atrzad.ayomusica.data.Playlists
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.durationText
import io.github.atrzad.ayomusica.data.fold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryTest {
    private fun song(id: Long, title: String, artist: String, album: String, track: Int = 0, disc: Int = 0,
                     albumArtist: String = "") =
        Song(id, title, artist, album, albumId = 1, albumArtist = albumArtist, durationMs = 180_000, track = track,
            disc = disc)

    @Test
    fun searchIgnoresAccentsAndCase() {
        val songs = listOf(song(1, "São Paulo", "Criolo", "Nó na Orelha"), song(2, "Outra", "Emicida", "AmarElo"))
        assertEquals(listOf(1L), Grouping.search(songs, "sao PAULO").map { it.id })
        assertEquals(listOf(2L), Grouping.search(songs, "emicida amarelo").map { it.id })
        assertEquals("sao paulo", fold("  São Paulo "))
    }

    @Test
    fun albumsKeepDiscAndTrackOrderAndCompilations() {
        val songs = listOf(
            song(1, "B", "Tyler, The Creator", "Igor", track = 2),
            song(2, "A", "Tyler, The Creator", "Igor", track = 1),
            song(3, "C", "Tyler, The Creator", "Igor", track = 1, disc = 2),
            song(4, "X", "Artista 1", "Coletânea", albumArtist = "Vários"),
            song(5, "Y", "Artista 2", "Coletânea", albumArtist = "Vários"),
        )
        val albums = Grouping.albums(songs)
        assertEquals(listOf("Coletânea", "Igor"), albums.map { it.title })
        assertEquals(listOf(2L, 1L, 3L), albums[1].songs.map { it.id })
        assertEquals(2, albums[0].songs.size)
        assertEquals("Vários", albums[0].artist)
        assertEquals(listOf("Artista 1", "Artista 2", "Tyler, The Creator"), Grouping.artists(songs).map { it.name })
    }

    @Test
    fun durations() {
        assertEquals("3:05", durationText(185_000))
        assertEquals("1:01:01", durationText(3_661_000))
    }

    @Test
    fun playlistsPersistAndSkipDuplicates() = runBlocking {
        val folder = Files.createTempDirectory("ayo").toFile()
        val file = File(folder, "playlists.json")
        val playlists = Playlists(file)
        val created = playlists.create("Estrada", listOf(1, 2, 2))
        playlists.add(created.id, listOf(2, 3))
        playlists.move(created.id, 2, 0)
        playlists.removeAt(created.id, 1)
        assertEquals(listOf(3L, 2L), Playlists(file).all.value.single().songIds)
        folder.deleteRecursively()
        Unit
    }
}

class StatsAndSpectrumTest {
    @org.junit.Test
    fun playsCountAfterHalfOrFourMinutes() {
        org.junit.Assert.assertTrue(io.github.atrzad.ayomusica.data.Stats.counts(95_000, 180_000))
        org.junit.Assert.assertFalse(io.github.atrzad.ayomusica.data.Stats.counts(60_000, 180_000))
        org.junit.Assert.assertTrue(io.github.atrzad.ayomusica.data.Stats.counts(240_000, 1_800_000))
    }

    @org.junit.Test
    fun automaticLists() {
        val songs = (1L..4L).map { Song(it, "M$it", "A", "B", 1, durationMs = 200_000, dateAdded = it * 10) }
        val stats = mapOf(1L to io.github.atrzad.ayomusica.data.SongStats(plays = 2, lastPlayed = 5, favorite = true),
            2L to io.github.atrzad.ayomusica.data.SongStats(plays = 5, lastPlayed = 3),
            3L to io.github.atrzad.ayomusica.data.SongStats(skips = 1))
        val auto = io.github.atrzad.ayomusica.data.AutoList.entries.associateWith { list -> list.songs(songs, stats).map { it.id } }
        org.junit.Assert.assertEquals(listOf(1L), auto[io.github.atrzad.ayomusica.data.AutoList.Favorites])
        org.junit.Assert.assertEquals(listOf(2L, 1L), auto[io.github.atrzad.ayomusica.data.AutoList.MostPlayed])
        org.junit.Assert.assertEquals(listOf(1L, 2L), auto[io.github.atrzad.ayomusica.data.AutoList.Recent])
        org.junit.Assert.assertEquals(listOf(4L, 3L, 2L, 1L), auto[io.github.atrzad.ayomusica.data.AutoList.Added])
    }

    @org.junit.Test
    fun spectrumPutsATonesEnergyInItsBand() {
        val size = 1024
        val fft = ByteArray(size)
        val bin = (1000.0 / (44_100.0 / size)).toInt()  // a 1 kHz tone
        fft[2 * bin] = 120
        val bands = io.github.atrzad.ayomusica.playback.Spectrum.bands(fft, 44_100, 20)
        val loudest = bands.indices.maxBy { bands[it] }
        org.junit.Assert.assertTrue("banda $loudest", loudest in 7..11)
        org.junit.Assert.assertEquals(0f, bands[0])
        val smooth = io.github.atrzad.ayomusica.playback.Spectrum.smooth(floatArrayOf(1f), floatArrayOf(0f))
        org.junit.Assert.assertTrue(smooth[0] in 0.5f..0.9f)
    }
}
