package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.data.Playlists
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistsTest {
    @Test
    fun readsPlaylistsSavedBeforeDescriptionsAndPictures() = runBlocking {
        val dir = Files.createTempDirectory("ayo").toFile()
        val file = File(dir, "playlists.json").apply { writeText("""[{"id":1,"name":"Viagem","songIds":[962,10977]}]""") }
        val store = Playlists(file)
        val old = store.all.value.single()
        assertEquals("Viagem", old.name)
        assertEquals(listOf(962L, 10977L), old.songIds)
        assertEquals("", old.description)
        assertNull(old.coverFile)

        store.edit(1, " Estrada ", " Para a estrada ", "/tmp/capa.jpg")
        store.add(1, listOf(10977, 5))
        val reread = Playlists(file).all.value.single()
        assertEquals("Estrada", reread.name)
        assertEquals("Para a estrada", reread.description)
        assertEquals("/tmp/capa.jpg", reread.coverFile)
        assertEquals(listOf(962L, 10977L, 5L), reread.songIds)  // already there: skipped
        dir.deleteRecursively()
        Unit
    }
}
