package io.github.atrzad.ayomusica.lyrics

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Lyrics stored inside the audio file: MP3 (ID3), FLAC (Vorbis comments) and M4A/MP4 (©lyr). */
object EmbeddedLyrics {
    private const val MAX_BLOCK = 8 * 1024 * 1024

    fun read(input: InputStream): Lyrics? {
        val stream = BufferedInputStream(input, 64 * 1024)
        stream.mark(16)
        val magic = ByteArray(8)
        val read = stream.read(magic)
        stream.reset()
        if (read < 8) return null
        return when {
            magic.startsWith("ID3") -> Id3Lyrics.read(stream)
            magic.startsWith("fLaC") -> flac(stream)
            String(magic, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(stream)
            else -> null
        }
    }

    private fun ByteArray.startsWith(text: String) = text.indices.all { this[it] == text[it].code.toByte() }

    // ── FLAC: metadata blocks after "fLaC"; type 4 is VORBIS_COMMENT (little-endian lengths) ──
    private fun flac(input: InputStream): Lyrics? {
        val data = DataInputStream(input)
        data.skipFully(4)
        while (true) {
            val header = data.read().takeIf { it >= 0 } ?: return null
            val length = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            if (header and 0x7F == 4 && length <= MAX_BLOCK) {
                val block = ByteArray(length).also(data::readFully)
                return vorbisComments(block)
            }
            data.skipFully(length.toLong())
            if (header and 0x80 != 0) return null  // last metadata block
        }
    }

    /** Vorbis comment block → lyrics from SYNCEDLYRICS, LYRICS or UNSYNCEDLYRICS. */
    fun vorbisComments(block: ByteArray): Lyrics? {
        var position = 0
        fun int(): Int {
            val value = (block[position].toInt() and 0xFF) or ((block[position + 1].toInt() and 0xFF) shl 8) or
                ((block[position + 2].toInt() and 0xFF) shl 16) or ((block[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }
        if (block.size < 8) return null
        position += int()                   // vendor string
        val count = int()
        val found = mutableMapOf<String, String>()
        repeat(count) {
            if (position + 4 > block.size) return@repeat
            val length = int()
            if (length < 0 || position + length > block.size) return@repeat
            val comment = String(block, position, length, Charsets.UTF_8)
            position += length
            val key = comment.substringBefore('=').uppercase()
            if (key in LYRIC_KEYS && key !in found) found[key] = comment.substringAfter('=')
        }
        val options = LYRIC_KEYS.mapNotNull { found[it] }.map { Lyrics.parse(it, "embutida") }
            .filter { lyrics -> lyrics.lines.any { it.text.isNotBlank() } }
        return options.firstOrNull { it.synced } ?: options.firstOrNull()
    }

    private val LYRIC_KEYS = listOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS")

    // ── MP4: moov › udta › meta › ilst › ©lyr › data ──
    private val CONTAINERS = setOf("moov", "udta", "meta", "ilst", "©lyr")

    private fun mp4(input: InputStream): Lyrics? {
        val data = DataInputStream(input)
        return try {
            atoms(data, Long.MAX_VALUE)
        } catch (_: EOFException) {
            null
        }
    }

    private fun atoms(data: DataInputStream, limit: Long): Lyrics? {
        var consumed = 0L
        while (consumed + 8 <= limit) {
            var size = data.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(data::readFully).toString(Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                size = data.readLong()
                header = 16
            } else if (size == 0L) {
                size = limit - consumed  // up to the end
            }
            if (size < header) return null
            val body = size - header
            when {
                type == "meta" -> {
                    data.skipFully(4)  // version and flags
                    atoms(data, body - 4)?.let { return it }
                }
                type == "data" && body in 8..MAX_BLOCK.toLong() -> {
                    data.skipFully(8)  // type and locale
                    val text = ByteArray((body - 8).toInt()).also(data::readFully).toString(Charsets.UTF_8)
                    return Lyrics.parse(text, "embutida").takeIf { lyrics -> lyrics.lines.any { it.text.isNotBlank() } }
                }
                type in CONTAINERS -> atoms(data, body)?.let { return it }
                else -> data.skipFully(body)
            }
            consumed += size
        }
        return null
    }

    private fun DataInputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left -= 1
            } else {
                left -= skipped
            }
        }
    }

    private fun DataInputStream.skipFully(count: Int) = skipFully(count.toLong())
}
