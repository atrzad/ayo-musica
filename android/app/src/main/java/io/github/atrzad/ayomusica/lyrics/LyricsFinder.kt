package io.github.atrzad.ayomusica.lyrics

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.fold
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Finds a song's lyrics on LRCLIB trying several readings of messy tags, and scores what comes back by
 * title, artist and duration. Synced lyrics win over plain ones of the same song.
 */
class LyricsFinder(private val lrcLib: LrcLib = LrcLib()) {
    data class Match(val result: LrcLibResult, val score: Int)

    fun find(song: Song): LrcLibResult? {
        val seconds = (song.durationMs / 1000.0).roundToLong()
        val readings = Clean.readings(song).take(4)
        val album = Clean.album(song)
        val candidates = mutableListOf<LrcLibResult>()
        fun good() = candidates.mapNotNull { score(it, song, readings) }.maxByOrNull { it.score }
            ?.takeIf { it.score >= ACCEPT && it.result.synced }

        for (reading in readings.take(2)) {
            if (reading.artist.isNotBlank()) {
                lrcLib.get(reading.artist, reading.title, album, seconds)?.let { candidates += it }
                if (album.isNotBlank()) lrcLib.get(reading.artist, reading.title, "", seconds)?.let { candidates += it }
            }
            good()?.let { return it.result }
        }
        for (reading in readings) {
            candidates += if (reading.artist.isNotBlank()) {
                lrcLib.search(mapOf("track_name" to reading.title, "artist_name" to reading.artist))
            } else emptyList()
            good()?.let { return it.result }
            candidates += lrcLib.search("${reading.artist} ${reading.title}".trim())
            good()?.let { return it.result }
        }
        return candidates.mapNotNull { score(it, song, readings) }.filter { it.score >= ACCEPT }
            .maxByOrNull { it.score }?.result
    }

    /** Manual search: whatever the person typed, songs with lyrics first, closest duration first. */
    fun search(query: String, song: Song?): List<LrcLibResult> {
        val seconds = song?.durationMs?.div(1000) ?: 0
        return lrcLib.search(query).filter { it.hasLyrics }
            .sortedWith(compareByDescending<LrcLibResult> { it.synced }
                .thenBy { if (seconds > 0 && it.duration != null) abs(it.duration - seconds) else 0.0 })
    }

    companion object {
        const val ACCEPT = 60

        /** 0..100+: title words, artist, duration; null when it can't be the same recording. */
        fun score(result: LrcLibResult, song: Song, readings: List<Clean.Reading>): Match? {
            if (!result.hasLyrics) return null
            val seconds = song.durationMs / 1000.0
            val difference = if (seconds > 0 && result.duration != null) abs(result.duration - seconds) else null
            if (difference != null && difference > 8) return null
            val title = fold(Clean.coreTitle(result.trackName))
            val artist = fold(result.artistName.orEmpty())
            var best = 0
            for (reading in readings) {
                val wanted = fold(reading.title)
                var points = when {
                    title == wanted -> 50
                    title.isNotEmpty() && (title in wanted || wanted in title) -> 35
                    similarity(title, wanted) >= 0.7 -> 30
                    else -> 0
                }
                if (points == 0) continue
                val names = Clean.artists(reading.artist).map(::fold).filter { it.isNotEmpty() }
                // Without an artist the duration is the only proof that it is the same song.
                if (names.isEmpty() && (difference == null || difference > 2)) continue
                points += when {
                    names.isEmpty() -> 10
                    names.any { it.isNotEmpty() && (it in artist || artist in it) } -> 30
                    else -> -20
                }
                points += when {
                    difference == null -> 5
                    difference <= 2 -> 20
                    difference <= 5 -> 10
                    else -> 0
                }
                if (result.synced) points += 5
                best = maxOf(best, points)
            }
            return Match(result, best)
        }

        /** Share of words in common (0..1). */
        fun similarity(a: String, b: String): Double {
            val left = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val right = b.split(' ').filter { it.isNotEmpty() }.toSet()
            if (left.isEmpty() || right.isEmpty()) return 0.0
            return (left intersect right).size.toDouble() / maxOf(left.size, right.size)
        }
    }
}
