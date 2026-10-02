package io.github.atrzad.ayomusica.analyzer

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.SongOverride
import io.github.atrzad.ayomusica.data.fold
import io.github.atrzad.ayomusica.lyrics.Clean
import kotlin.math.abs

/** A proposal for one song and how sure we are (0..100). */
data class Proposal(val override: SongOverride, val coverUrl: String?, val score: Int, val label: String)

enum class Verdict { Auto, Review, NotFound }

/**
 * Identifies songs by their tags and file name on Deezer, like the desktop app: same title, same artist and
 * duration within a few seconds is enough to apply on its own; doubtful matches go to review.
 */
class Analyzer(private val deezer: Deezer = Deezer()) {

    fun analyze(song: Song): Pair<Verdict, Proposal?> {
        val readings = Clean.readings(song).take(3)
        var best: Proposal? = null
        val seen = mutableSetOf<Long>()
        for (reading in readings) {
            val query = if (reading.artist.isNotBlank()) "artist:\"${reading.artist}\" track:\"${reading.title}\""
                else reading.title
            var results = deezer.search(query)
            if (results.isEmpty() && reading.artist.isNotBlank()) results = deezer.search("${reading.artist} ${reading.title}")
            for (track in results) {
                if (!seen.add(track.id)) continue
                val score = score(track, song, readings)
                if (score > (best?.score ?: -1)) best = proposal(track, score)
            }
            if ((best?.score ?: 0) >= AUTO) break
        }
        val found = best ?: return Verdict.NotFound to null
        return when {
            found.score >= AUTO -> Verdict.Auto to found
            found.score >= REVIEW -> Verdict.Review to found
            else -> Verdict.NotFound to null
        }
    }

    private fun proposal(track: DeezerTrack, score: Int): Proposal {
        val album = if (score >= REVIEW) deezer.album(track.album.id) else null
        val override = SongOverride(
            title = track.title, artist = track.artist.name, album = track.album.title,
            albumArtist = album?.artist?.name.orEmpty(),
            year = album?.release_date?.take(4)?.toIntOrNull() ?: 0,
            genre = album?.genres?.data?.firstOrNull()?.name.orEmpty(),
        )
        return Proposal(override, track.album.cover_xl ?: track.album.cover_big, score,
            "${track.artist.name} — ${track.title}")
    }

    companion object {
        const val AUTO = 90
        const val REVIEW = 60
        private val VERSIONS = listOf("live", "ao vivo", "remix", "instrumental", "acoustic", "acústico", "sped up",
            "slowed", "karaoke", "cover", "demo")

        /** Is this song worth analyzing? Missing artist or album, or a title with download junk. */
        fun needsWork(song: Song): Boolean {
            val artists = Clean.artists(song.artist)
            return artists.isEmpty() || song.album.isBlank() || Clean.album(song).isBlank() ||
                Clean.coreTitle(song.title) != song.title.trim() || " - " in song.title || song.coverFile == null &&
                song.year == 0
        }

        fun score(track: DeezerTrack, song: Song, readings: List<Clean.Reading>): Int {
            val seconds = song.durationMs / 1000
            val difference = if (seconds > 0 && track.duration > 0) abs(track.duration - seconds) else null
            if (difference != null && difference > 15) return 0
            val title = fold(Clean.coreTitle(track.title))
            val raw = fold(song.title + " " + song.displayName)
            var best = 0
            for (reading in readings) {
                val wanted = fold(reading.title)
                var points = when {
                    title == wanted -> 55
                    title.isNotEmpty() && (wanted.startsWith(title) || title.startsWith(wanted)) -> 40
                    else -> 0
                }
                if (points == 0) continue
                val names = Clean.artists(reading.artist).map(::fold)
                val artist = fold(track.artist.name)
                points += when {
                    names.isEmpty() -> if (difference != null && difference <= 2) 15 else -40
                    names.any { it.isNotEmpty() && (it in artist || artist in it) } -> 30
                    else -> -25
                }
                points += when {
                    difference == null -> 0
                    difference <= 3 -> 15
                    difference <= 6 -> 5
                    else -> -15
                }
                // A live or remixed version is a different recording unless the file says so too.
                val version = VERSIONS.firstOrNull { it in fold(track.title) && it !in raw }
                if (version != null) points -= 35
                best = maxOf(best, points)
            }
            return best.coerceIn(0, 100)
        }
    }
}
