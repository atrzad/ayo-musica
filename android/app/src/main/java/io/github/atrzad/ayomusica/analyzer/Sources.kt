package io.github.atrzad.ayomusica.analyzer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** The online catalogs songs are identified in. None of them needs an account or a key. */
@Serializable
enum class Source(val label: String, val byAudio: Boolean = false) {
    Deezer("Deezer"),
    ITunes("Apple Music"),
    MusicBrainz("MusicBrainz"),
    /** Recognized by the sound (fingerprint of a few seconds), not by the tags. */
    Shazam("Shazam", byAudio = true),
    AcoustID("AcoustID", byAudio = true),
    ;

    companion object {
        /** The catalogs searched by text. */
        val byText = entries.filterNot { it.byAudio }
    }
}

/** A song as one of the sources knows it. Year, genre and album artist may only come with [MetadataSource.complete]. */
data class Candidate(
    val source: Source,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val year: Int = 0,
    val genre: String = "",
    val seconds: Int = 0,
    /** A small cover for lists. */
    val thumb: String? = null,
    /** Big covers to save, best first (the next one is tried when one is missing). */
    val covers: List<String> = emptyList(),
    /** What the source needs to fill in the details later (Deezer album id...). */
    val ref: String = "",
    /** Recognized by the sound: how sure the source is (0..100). */
    val confidence: Int = 0,
)

interface MetadataSource {
    val source: Source
    fun search(title: String, artist: String): List<Candidate>
    /** Fills in what the search results leave out; costs another request on some sources. */
    fun complete(candidate: Candidate): Candidate = candidate
}

internal val sourceJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Plain HTTP for the sources, with each one's pace (requests closer than [gapMs] wait). */
class Http(private val gapMs: Long, private val get: (String) -> String? = ::fetch) {
    private var last = 0L

    @Synchronized fun text(url: String): String? {
        val wait = gapMs - (System.currentTimeMillis() - last)
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
        return get(url)
    }

    companion object {
        private const val AGENT = "AyoMusica-Android/0.6 (https://github.com/atrzad/ayo-musica)"

        private fun open(address: String, timeout: Int) = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = timeout
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept", "application/json")
        }

        /** The body for 200, null for 404; anything else is an error (no connection, rate limit...). */
        fun fetch(address: String): String? {
            val connection = open(address, 15_000)
            try {
                return when (val code = connection.responseCode) {
                    200 -> connection.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> throw IOException("${connection.url.host} respondeu $code")
                }
            } finally {
                connection.disconnect()
            }
        }

        /** An image or other file; null when it is not there. */
        fun bytes(address: String): ByteArray? = runCatching {
            val connection = open(address, 20_000)
            try {
                if (connection.responseCode == 200) connection.inputStream.use { it.readBytes() } else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        /** Saves the first of [addresses] that exists into [target]. */
        fun download(addresses: List<String>, target: File): Boolean {
            for (address in addresses) {
                val data = bytes(address) ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(data)
                return true
            }
            return false
        }

        fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
    }
}

// ── Apple Music (iTunes Search API) ─────────────────────────────────────────

@Serializable data class ITunesTrack(
    val trackId: Long = 0, val trackName: String = "", val artistName: String = "", val collectionName: String = "",
    val collectionArtistName: String? = null, val releaseDate: String = "", val primaryGenreName: String = "",
    val trackTimeMillis: Long = 0, val artworkUrl100: String? = null, val kind: String = "",
)
@Serializable data class ITunesResults(val results: List<ITunesTrack> = emptyList())

/** Apple's public catalog search: good covers (up to 1200 px), genre and release year in one request. */
class ITunes(private val http: Http = Http(3_000)) : MetadataSource {  // Apple allows about 20 requests a minute
    override val source = Source.ITunes
    private val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    override fun search(title: String, artist: String): List<Candidate> {
        val term = "$artist $title".trim()
        if (term.isEmpty()) return emptyList()
        val body = http.text("https://itunes.apple.com/search?media=music&entity=song&limit=15&country=$country&term=" +
            Http.encode(term)) ?: return emptyList()
        return parse(body)
    }

    companion object {
        fun parse(body: String): List<Candidate> =
            runCatching { sourceJson.decodeFromString<ITunesResults>(body).results }.getOrDefault(emptyList())
                .filter { it.trackName.isNotBlank() && (it.kind.isEmpty() || it.kind == "song") }
                .map { track ->
                    val art = track.artworkUrl100
                    Candidate(
                        Source.ITunes, track.trackId.toString(), track.trackName, track.artistName, track.collectionName,
                        albumArtist = track.collectionArtistName.orEmpty(),
                        year = track.releaseDate.take(4).toIntOrNull() ?: 0, genre = track.primaryGenreName,
                        seconds = (track.trackTimeMillis / 1000).toInt(),
                        thumb = art?.replace("100x100bb", "200x200bb"),
                        covers = listOfNotNull(art?.replace("100x100bb", "1200x1200bb"), art?.replace("100x100bb", "600x600bb")),
                    )
                }
    }
}

// ── MusicBrainz + Cover Art Archive ─────────────────────────────────────────

@Serializable data class MbName(val name: String = "")
@Serializable data class MbCredit(val name: String = "", val joinphrase: String = "", val artist: MbName = MbName())
@Serializable data class MbGroup(
    val id: String = "",
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
)
@Serializable data class MbRelease(
    val id: String = "", val title: String = "", val date: String = "", val status: String? = null,
    @SerialName("release-group") val group: MbGroup = MbGroup(),
    @SerialName("artist-credit") val credit: List<MbCredit> = emptyList(),
)
@Serializable data class MbTag(val name: String = "", val count: Int = 0)
@Serializable data class MbRecording(
    val id: String = "", val title: String = "", val length: Long? = null,
    @SerialName("artist-credit") val credit: List<MbCredit> = emptyList(),
    val releases: List<MbRelease> = emptyList(),
    val tags: List<MbTag> = emptyList(),
)
@Serializable data class MbSearch(val recordings: List<MbRecording> = emptyList())

/** MusicBrainz, the open music encyclopedia, with covers from the Cover Art Archive. One request a second. */
class MusicBrainz(private val http: Http = Http(1_100)) : MetadataSource {
    override val source = Source.MusicBrainz

    override fun search(title: String, artist: String): List<Candidate> {
        if (title.isBlank()) return emptyList()
        var query = "recording:${quote(title)}"
        if (artist.isNotBlank()) query += " AND artist:${quote(artist)}"
        val body = http.text("https://musicbrainz.org/ws/2/recording?fmt=json&limit=15&query=" + Http.encode(query))
            ?: return emptyList()
        return parse(body)
    }

    /** One recording by its MusicBrainz id, with its releases (for year, album artist and genre). */
    fun recording(id: String): Candidate? {
        val body = http.text("https://musicbrainz.org/ws/2/recording/$id?fmt=json&inc=artist-credits+releases+release-groups+tags")
            ?: return null
        return parse("""{"recordings":[$body]}""").firstOrNull()
    }

    companion object {
        private val LUCENE = Regex("""([+\-&|!(){}\[\]^"~*?:\\/])""")

        fun quote(text: String) = "\"" + LUCENE.replace(text, """\\$1""") + "\""

        private fun credit(parts: List<MbCredit>) = parts.joinToString("") { (it.name.ifBlank { it.artist.name }) + it.joinphrase }

        /** An official album first, then EP and single; compilations and live releases last; the earliest wins. */
        fun pick(releases: List<MbRelease>): MbRelease? = releases.minWithOrNull(
            compareBy<MbRelease>(
                { (it.status ?: "Official") != "Official" },
                { it.group.secondaryTypes.any { type -> type.equals("Compilation", true) || type.equals("Live", true) } },
                { when (it.group.primaryType?.lowercase()) { "album" -> 0; "ep" -> 1; "single" -> 2; else -> 3 } },
                { it.date.ifBlank { "9999" } },
            ),
        )

        fun parse(body: String): List<Candidate> =
            runCatching { sourceJson.decodeFromString<MbSearch>(body).recordings }.getOrDefault(emptyList())
                .filter { it.title.isNotBlank() }
                .map { recording ->
                    val release = pick(recording.releases)
                    val covers = listOfNotNull(
                        release?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-1200" },
                        release?.group?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-1200" },
                    )
                    Candidate(
                        Source.MusicBrainz, recording.id, recording.title, credit(recording.credit), release?.title.orEmpty(),
                        albumArtist = release?.credit?.let(::credit).orEmpty(),
                        year = release?.date?.take(4)?.toIntOrNull() ?: 0,
                        genre = recording.tags.maxByOrNull { it.count }?.name?.replaceFirstChar { it.uppercase() }.orEmpty(),
                        seconds = ((recording.length ?: 0) / 1000).toInt(),
                        thumb = release?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-250" },
                        covers = covers,
                    )
                }
    }
}
