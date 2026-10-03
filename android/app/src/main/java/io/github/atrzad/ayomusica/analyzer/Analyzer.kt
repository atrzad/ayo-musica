package io.github.atrzad.ayomusica.analyzer

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.SongOverride
import io.github.atrzad.ayomusica.data.fold
import io.github.atrzad.ayomusica.lyrics.Clean
import kotlin.math.abs

/** A proposal for one song and how sure we are (0..100). */
data class Proposal(val override: SongOverride, val covers: List<String>, val score: Int, val label: String, val source: Source)

enum class Verdict { Auto, Review, NotFound }

/**
 * Identifies songs by their tags and file name, like the desktop app: same title, same artist and duration within a
 * few seconds is enough to apply on its own; doubtful matches go to review. Deezer is asked first; Apple Music and
 * MusicBrainz only when it is not sure, since they allow fewer requests.
 */
class Analyzer(private val sources: List<MetadataSource> = listOf(Deezer(), ITunes(), MusicBrainz())) {

    fun analyze(song: Song): Pair<Verdict, Proposal?> {
        val readings = Clean.readings(song).take(3)
        var best: Pair<Candidate, Int>? = null
        val seen = mutableSetOf<String>()
        var failures = 0
        for ((index, source) in sources.withIndex()) {
            // The slower sources only get the likeliest readings.
            for (reading in if (index == 0) readings else readings.take(2)) {
                val results = try {
                    source.search(reading.title, reading.artist)
                } catch (error: java.io.IOException) {
                    failures++
                    break  // this source is out of reach (or rate limited): go on with the next one
                }
                for (candidate in results) {
                    if (!seen.add("${candidate.source}:${candidate.id}")) continue
                    val score = score(candidate, song, readings)
                    if (score > (best?.second ?: -1)) best = candidate to score
                }
                if ((best?.second ?: 0) >= AUTO) break
            }
            if ((best?.second ?: 0) >= AUTO) break
        }
        if (best == null && failures == sources.size) throw java.io.IOException("Sem conexão com as fontes.")
        val (candidate, score) = best ?: return Verdict.NotFound to null
        return when {
            score >= AUTO -> Verdict.Auto to proposal(candidate, score)
            score >= REVIEW -> Verdict.Review to proposal(candidate, score)
            else -> Verdict.NotFound to null
        }
    }

    private fun proposal(found: Candidate, score: Int): Proposal {
        val source = sources.first { it.source == found.source }
        val candidate = runCatching { source.complete(found) }.getOrDefault(found)
        return Proposal(override(candidate), candidate.covers, score, "${candidate.artist} — ${candidate.title}", candidate.source)
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

        /** What saving [candidate] writes over the song's tags (inside the app). */
        fun override(candidate: Candidate) = SongOverride(
            title = candidate.title, artist = candidate.artist, album = candidate.album,
            albumArtist = candidate.albumArtist, year = candidate.year, genre = candidate.genre,
            source = candidate.source.name.lowercase(),
        )

        fun score(track: Candidate, song: Song, readings: List<Clean.Reading> = Clean.readings(song)): Int {
            val seconds = song.durationMs / 1000
            val difference = if (seconds > 0 && track.seconds > 0) abs(track.seconds - seconds) else null
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
                val artist = fold(track.artist)
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
