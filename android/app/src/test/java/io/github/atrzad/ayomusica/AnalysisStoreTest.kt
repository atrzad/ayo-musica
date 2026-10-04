package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.analyzer.AnalysisStore
import io.github.atrzad.ayomusica.analyzer.Proposal
import io.github.atrzad.ayomusica.analyzer.SavedResult
import io.github.atrzad.ayomusica.analyzer.Source
import io.github.atrzad.ayomusica.analyzer.Verdict
import io.github.atrzad.ayomusica.data.SongOverride
import io.github.atrzad.ayomusica.data.Stats
import io.github.atrzad.ayomusica.data.AutoList
import io.github.atrzad.ayomusica.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnalysisStoreTest {
    @Test
    fun remembersResultsAndIgnoredSongsBetweenRuns() {
        val dir = Files.createTempDirectory("ayo").toFile()
        val file = File(dir, "analysis.json")
        val store = AnalysisStore(file)
        val proposal = Proposal(SongOverride(title = "Obsoleto", artist = "Seu Pereira"), listOf("c"), 72, "x", Source.ITunes)
        store.put(1, SavedResult(Verdict.Review, proposal))
        store.put(2, SavedResult(Verdict.NotFound))
        store.put(3, SavedResult(Verdict.NotFound))
        store.ignore(3)
        store.flush()
        Thread.sleep(2500)  // the delayed write

        val again = AnalysisStore(file).data.value
        assertEquals(listOf(1L, 2L, 3L), again.results.keys.toList())  // kept in order: the next run goes on after them
        assertEquals(Source.ITunes, again.results[1]!!.proposal!!.source)
        assertEquals(setOf(3L), again.ignored)

        val reread = AnalysisStore(file)
        reread.forget(Verdict.NotFound)
        assertEquals(setOf(1L), reread.data.value.results.keys)
        reread.unignore(3)
        assertTrue(reread.data.value.ignored.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun shuffleBlacklist() {
        val dir = Files.createTempDirectory("ayo").toFile()
        val stats = Stats(File(dir, "stats.json"))
        stats.setFavorite(5, true)
        stats.setNoShuffle(listOf(5L, 6L), true)
        assertTrue(stats.isNoShuffle(5))
        assertTrue(stats.isFavorite(5))  // the other marks stay
        stats.setNoShuffle(listOf(6L), false)
        assertFalse(stats.isNoShuffle(6))
        val songs = (4L..6L).map { Song(it, "t$it", "a", "", 1, durationMs = 1000) }
        assertEquals(listOf(5L), AutoList.NoShuffle.songs(songs, stats.all.value).map { it.id })
        dir.deleteRecursively()
    }
}
