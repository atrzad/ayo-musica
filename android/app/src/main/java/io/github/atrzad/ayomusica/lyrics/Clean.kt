package io.github.atrzad.ayomusica.lyrics

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.fold

/**
 * Turns messy tags and file names (YouTube downloads, mostly) into search hints.
 * The same rules as the desktop app (ayo_musica/identify/clean.py), trimmed to what the phone needs.
 */
object Clean {
    private val TOPIC = Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE)
    private val DOWNLOADER = Regex("""\(\s*mp3[_ ]?\d+k?\s*\)|_private$|\s*\[[A-Za-z0-9_-]{11}]\s*$""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""[(\[]([^)\]]*)[)\]]""")
    private val TRAILING = Regex("""\s+[-–—]\s+([^-–—]+)$""")
    private val FEAT = Regex("""(?:^|\s)(?:feat\.?|ft\.?|featuring|part\.?|participa[çc][ãa]o(?: especial)?(?: de)?)\s+(.+)$""",
        RegexOption.IGNORE_CASE)
    private val NOISE = Regex(
        """^(?:official|oficial|music video|lyric video|lyrics?|letra|visuali[sz]er|audio|áudio|video|vídeo|clipe|""" +
            """hd|hq|4k|mv|m/v|explicit|clean|legendado|tradução|remaster(?:ed|izado)?(?: \d{4})?|""" +
            """.*play-?through.*|.*official.*|.*oficial.*|prod\.? .*|produced by .*|\d{4})$""", RegexOption.IGNORE_CASE)
    private val TRACK_PREFIX = Regex("""^\s*\d{1,3}\s*[.\-_)]\s*""")
    private val SPLIT_ARTISTS = Regex("""\s*(?:,|&|\s+e\s+|\s+x\s+|\s+and\s+|\|)\s*""", RegexOption.IGNORE_CASE)
    private val UNKNOWN = setOf("", "<unknown>", "unknown", "desconhecido", "artista desconhecido")

    fun clean(text: String?): String {
        var value = (text ?: "").replace(Regex("[​-‏﻿]"), "")
        value = DOWNLOADER.replace(value, "")
        value = value.replace(Regex("""(?<=\w)_(?=\w)"""), "'")   // You_re → You're
            .replace(Regex("""(?<=\w)_ """), ": ")                // Operation_ Greenbacks → Operation: Greenbacks
            .replace(Regex("""\s+_\s+"""), " | ")                 // OTÁRIO _ Seu Pereira → OTÁRIO | Seu Pereira
        return value.replace(Regex("""\s{2,}"""), " ").trim(' ', '_', '-', '–', '—', '|')
    }

    /** The title without brackets, featured artists and noise such as "(Official Video)". */
    fun coreTitle(text: String?): String {
        var value = BRACKETS.replace(clean(text), " ")
        TRAILING.find(value)?.let { if (NOISE.matches(it.groupValues[1].trim())) value = value.substring(0, it.range.first) }
        value = FEAT.replace(value, "")
        return value.replace(Regex("""\s{2,}"""), " ").trim(' ', '_', '-', '–', '—')
    }

    /** "Tyler |  The Creator - Topic" → ["Tyler, The Creator"]; "Ruas Mc, MELI" → two artists. */
    fun artists(text: String?): List<String> {
        val value = TOPIC.replace(clean(text), "")
        if (fold(value) in UNKNOWN) return emptyList()
        if ("|" in value && value.split("|").all { it.trim().split(" ").size <= 2 }) {
            return listOf(value.replace(Regex("""\s*\|\s*"""), ", "))  // "Tyler | The Creator" is one name
        }
        return SPLIT_ARTISTS.split(value).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** ("Artist", "Title") from "01. Artist - Title (Official Video)(MP3_160K).mp3". */
    fun fromFileName(name: String): Pair<String?, String> {
        var stem = clean(name.substringBeforeLast('.'))
        stem = TRACK_PREFIX.replace(stem, "")
        for (separator in listOf(" - ", " – ", " — ")) {
            if (separator in stem) {
                val (artist, title) = stem.split(separator, limit = 2)
                return artist.trim() to title.trim()
            }
        }
        return null to stem
    }

    data class Reading(val artist: String, val title: String)

    /** The ways to read a song's tags and file name, most likely first. */
    fun readings(song: Song): List<Reading> {
        val (fileArtist, fileTitle) = fromFileName(song.displayName)
        // Without a title tag Android shows the file name, track number included ("03 - Song").
        val title = TRACK_PREFIX.replace(clean(song.title), "").ifBlank { fileTitle }
        val artists = artists(song.artist).ifEmpty { artists(fileArtist) }
        var core = coreTitle(title)
        for (artist in artists) {  // "Deftones – Rx Queen" when the artist is already known
            val rest = core.drop(artist.length).trimStart()
            if (fold(core).startsWith(fold(artist)) && rest.firstOrNull() in listOf('-', '–', '—', '|', ':')) {
                core = rest.drop(1).trim()
            }
        }
        val found = mutableListOf<Reading>()
        val first = artists.firstOrNull().orEmpty()
        if (core.isNotBlank()) found += Reading(first, core)
        if (artists.size > 1) found += Reading(artists.joinToString(", "), core)
        // "OTÁRIO | Seu Pereira" or "Artist – Title" inside the title: both orders.
        for (separator in listOf(" | ", " - ", " – ", " — ")) {
            if (separator in core) {
                val (left, right) = core.split(separator, limit = 2).map { coreTitle(it) }
                val leftArtist = artists(left).firstOrNull().orEmpty()
                val rightArtist = artists(right).firstOrNull().orEmpty()
                found += Reading(leftArtist, right)
                found += Reading(rightArtist, left)
            }
        }
        if (fileArtist != null) found += Reading(artists(fileArtist).firstOrNull().orEmpty(), coreTitle(fileTitle))
        return found.filter { it.title.isNotBlank() }.distinct()
    }

    fun album(song: Song): String {
        val album = TOPIC.replace(clean(song.album), "")
        // YouTube downloads often put the channel/artist name where the album should be.
        return if (fold(album) in artists(song.artist).map(::fold).toSet() || fold(album) in UNKNOWN) "" else album
    }
}
