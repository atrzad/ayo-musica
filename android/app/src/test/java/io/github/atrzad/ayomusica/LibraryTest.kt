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
