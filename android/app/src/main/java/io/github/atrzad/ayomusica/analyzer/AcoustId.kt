package io.github.atrzad.ayomusica.analyzer

import android.content.Context
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.voice.AudioDecoder
import kotlinx.serialization.Serializable
import java.io.IOException

/** Chromaprint (native, LGPL-2.1): the fingerprint AcoustID looks up. */
object Chromaprint {
    init {
        System.loadLibrary("ayoprint")
    }

    /** Compressed fingerprint of mono 16-bit [samples], or null on failure. */
    @JvmStatic external fun fingerprint(samples: ShortArray, rate: Int): String?
}

@Serializable data class AcoustArtist(val name: String = "", val joinphrase: String = "")
@Serializable data class AcoustReleaseGroup(
    val id: String = "", val title: String = "", val type: String? = null, val secondarytypes: List<String> = emptyList(),
)
@Serializable data class AcoustRecording(
    val id: String = "", val title: String = "", val duration: Double? = null,
    val artists: List<AcoustArtist> = emptyList(), val releasegroups: List<AcoustReleaseGroup> = emptyList(),
)
@Serializable data class AcoustResult(val id: String = "", val score: Double = 0.0, val recordings: List<AcoustRecording> = emptyList())
@Serializable data class AcoustError(val code: Int = 0, val message: String = "")
@Serializable data class AcoustAnswer(
    val status: String = "", val results: List<AcoustResult> = emptyList(), val error: AcoustError? = null,
)

/**
 * AcoustID: the open fingerprint database behind MusicBrainz Picard. Needs an application key (free, made by the
 * person at acoustid.org/new-application). Answers MusicBrainz recordings, completed with year and genre there.
 */
class AcoustId(private val key: String, private val post: (String, String) -> String? = ::send) {
    fun lookup(fingerprint: String, seconds: Int): List<Candidate> {
        val form = "format=json&meta=recordings+releasegroups+compress&client=${Http.encode(key)}" +
            "&duration=$seconds&fingerprint=${Http.encode(fingerprint)}"
        return parse(post("https://api.acoustid.org/v2/lookup", form) ?: return emptyList())
    }

    companion object {
        fun parse(body: String): List<Candidate> {
            val answer = runCatching { sourceJson.decodeFromString<AcoustAnswer>(body) }.getOrNull() ?: return emptyList()
            answer.error?.let { throw IOException(if (it.code == 4) "A chave do AcoustID não vale. Confira em Configurações." else "AcoustID: ${it.message}") }
            return answer.results.filter { it.score >= 0.5 }.sortedByDescending { it.score }.flatMap { result ->
                result.recordings.filter { it.title.isNotBlank() }.map { recording ->
                    // The album: an official album first, then an EP or single; compilations and live last.
                    val group = recording.releasegroups.minByOrNull { group ->
                        val other = group.secondarytypes.any { it.equals("Compilation", true) || it.equals("Live", true) }
                        (if (other) 10 else 0) + when (group.type?.lowercase()) { "album" -> 0; "ep" -> 1; "single" -> 2; else -> 3 }
                    }
                    Candidate(
                        Source.AcoustID, recording.id, recording.title,
                        recording.artists.joinToString("") { it.name + it.joinphrase }, group?.title.orEmpty(),
                        seconds = recording.duration?.toInt() ?: 0,
                        thumb = group?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-250" },
                        covers = listOfNotNull(group?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-1200" }),
                        confidence = (result.score * 100).toInt(),
                    )
                }
            }.distinctBy { it.id }
        }

        fun send(url: String, form: String): String? {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "AyoMusica-Android (https://github.com/atrzad/ayo-musica)")
            try {
                connection.outputStream.use { it.write(form.toByteArray()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                if (code == 400 && body != null) return body  // AcoustID explains the problem in the body
                if (code != 200) throw IOException("AcoustID respondeu $code")
                return body
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * Recognizing a song by its sound: Shazam (12 seconds from the middle) and AcoustID (the first two minutes, when the
 * person gave a key). Used when the tags are not enough, and on "Corrigir informações" → Pelo som.
 */
class AudioId(
    private val context: Context,
    private val shazamOn: () -> Boolean,
    private val acoustKey: () -> String,
    private val musicBrainz: MusicBrainz = MusicBrainz(),
) {
    private val shazam = Shazam()
    private var lastShazam = 0L

    val enabled: Boolean get() = shazamOn() || acoustKey().isNotBlank()

    /** What the sound says the song is; throws IOException only when every source asked failed. */
    fun identify(song: Song): List<Candidate> {
        val found = mutableListOf<Candidate>()
        var asked = 0
        var failure: IOException? = null
        if (shazamOn()) {
            asked++
            try {
                val from = maxOf(0L, song.durationMs / 2 - 6_000)
                val audio = AudioDecoder.shorts(AudioDecoder.decode(context, song.uri, fromMs = from, lengthMs = 12_000))
                pace()
                shazam.recognize(audio)?.let { found += it.copy(confidence = 88) }
            } catch (error: IOException) {
                failure = error
            } catch (_: IllegalArgumentException) {
            }
        }
        val key = acoustKey().trim()
        if (key.isNotBlank()) {
            asked++
            try {
                val audio = AudioDecoder.shorts(AudioDecoder.decode(context, song.uri, lengthMs = 120_000))
                val fingerprint = Chromaprint.fingerprint(audio, 16_000)
                if (fingerprint != null) found += AcoustId(key).lookup(fingerprint, (song.durationMs / 1000).toInt()).take(5)
            } catch (error: IOException) {
                failure = error
            } catch (_: IllegalArgumentException) {
            }
        }
        if (found.isEmpty() && asked > 0 && failure != null) throw failure
        return found
    }

    /** Year, genre and album artist: AcoustID answers MusicBrainz recordings, so MusicBrainz completes them. */
    fun complete(candidate: Candidate): Candidate {
        if (candidate.source != Source.AcoustID) return candidate
        val full = runCatching { musicBrainz.recording(candidate.id) }.getOrNull() ?: return candidate
        return candidate.copy(
            album = candidate.album.ifBlank { full.album }, albumArtist = full.albumArtist, year = full.year,
            genre = full.genre, covers = (candidate.covers + full.covers).distinct(),
        )
    }

    /** Shazam has no public API: at most one question every few seconds. */
    @Synchronized private fun pace() {
        val wait = 3_000 - (System.currentTimeMillis() - lastShazam)
        if (wait > 0) Thread.sleep(wait)
        lastShazam = System.currentTimeMillis()
    }
}
