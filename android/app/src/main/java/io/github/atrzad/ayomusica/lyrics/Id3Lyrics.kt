package io.github.atrzad.ayomusica.lyrics

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Lyrics embedded in an MP3's ID3v2 tag: USLT (plain or LRC text) and SYLT (synced).
 * Reads only the tag at the start of the file.
 */
object Id3Lyrics {
    private const val MAX_TAG = 16 * 1024 * 1024

    fun read(input: InputStream): Lyrics? {
        val header = ByteArray(10)
        if (input.readNBytesCompat(header) < 10 || header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val version = header[3].toInt()
        if (version !in 3..4) return null
        val size = syncsafe(header, 6)
        if (size <= 0 || size > MAX_TAG) return null
        val tag = ByteArray(size)
        if (input.readNBytesCompat(tag) < size) return null
        var position = 0
        if (header[5].toInt() and 0x40 != 0) {  // extended header
            val extended = if (version == 4) syncsafe(tag, 0) else int(tag, 0) + 4
            position += extended
        }
        var plain: Lyrics? = null
        while (position + 10 <= size) {
            val id = String(tag, position, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = if (version == 4) syncsafe(tag, position + 4) else int(tag, position + 4)
            val start = position + 10
            if (frameSize <= 0 || start + frameSize > size) break
            val frame = tag.copyOfRange(start, start + frameSize)
            when (id) {
                "SYLT" -> sylt(frame)?.let { return it }
                "USLT" -> uslt(frame)?.let { lyrics -> if (lyrics.synced) return lyrics else if (plain == null) plain = lyrics }
            }
            position = start + frameSize
        }
        return plain
    }

    private fun uslt(frame: ByteArray): Lyrics? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt()
        val descriptorEnd = terminator(frame, 4, encoding)
        val textStart = descriptorEnd + width(encoding)
        if (textStart > frame.size) return null
        val text = decode(frame.copyOfRange(textStart, frame.size), encoding)
        return Lyrics.parse(text, "embutida").takeIf { lyrics -> lyrics.lines.any { it.text.isNotBlank() } }
    }

    private fun sylt(frame: ByteArray): Lyrics? {
        if (frame.size < 7 || frame[4].toInt() != 2) return null  // timestamps must be milliseconds
        val encoding = frame[0].toInt()
        var position = terminator(frame, 6, encoding) + width(encoding)
        val lines = mutableListOf<Lyrics.Line>()
        while (position < frame.size) {
            val end = terminator(frame, position, encoding)
            val text = decode(frame.copyOfRange(position, end.coerceAtMost(frame.size)), encoding).trim()
            position = end + width(encoding)
            if (position + 4 > frame.size) break
            lines += Lyrics.Line(int(frame, position).toLong() and 0xFFFFFFFFL, text)
            position += 4
        }
        return if (lines.isEmpty()) null else Lyrics(lines.sortedBy { it.timeMs }, synced = true, source = "embutida")
    }

    private fun width(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun terminator(data: ByteArray, from: Int, encoding: Int): Int {
        var index = from
        if (width(encoding) == 2) {
            while (index + 1 < data.size && !(data[index].toInt() == 0 && data[index + 1].toInt() == 0)) index += 2
        } else {
            while (index < data.size && data[index].toInt() != 0) index++
        }
        return index.coerceAtMost(data.size)
    }

    private fun decode(bytes: ByteArray, encoding: Int): String = when (encoding) {
        0 -> String(bytes, Charsets.ISO_8859_1)
        1 -> String(bytes, Charsets.UTF_16)
        2 -> String(bytes, Charsets.UTF_16BE)
        else -> String(bytes, Charset.forName("UTF-8"))
    }.trimEnd('\u0000')

    private fun syncsafe(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0x7F shl 21) or (data[at + 1].toInt() and 0x7F shl 14) or
            (data[at + 2].toInt() and 0x7F shl 7) or (data[at + 3].toInt() and 0x7F)

    private fun int(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
