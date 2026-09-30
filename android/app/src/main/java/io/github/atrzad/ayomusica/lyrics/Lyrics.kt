package io.github.atrzad.ayomusica.lyrics

/** A lyrics text: lines with a start time (synced) or without (plain). Same rules as the desktop app. */
data class Lyrics(
    val lines: List<Line>,
    val synced: Boolean,
    val source: String = "",
    val offsetMs: Long = 0,
) {
    data class Line(val timeMs: Long?, val text: String)

    val text: String get() = lines.joinToString("\n") { it.text }

    /** Index of the line sung at [positionMs] (−1 before the first). A positive offset shows lyrics earlier. */
    fun currentIndex(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        val target = positionMs + offsetMs
        var low = 0
        var high = lines.size
        while (low < high) {  // first line starting after target
            val middle = (low + high) / 2
            if ((lines[middle].timeMs ?: 0) <= target) low = middle + 1 else high = middle
        }
        return low - 1
    }

    companion object {
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        private val META = Regex("""^\[[a-z#]+:.*]\s*$""", RegexOption.IGNORE_CASE)
        // Zero-width marks and control characters (some taggers leave NULs inside the text).
        private val INVISIBLE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")

        fun parse(raw: String?, source: String = ""): Lyrics {
            val text = INVISIBLE.replace((raw ?: "").replace("\r\n", "\n").replace('\r', '\n'), "")
            val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val timed = mutableListOf<Line>()
            val plain = mutableListOf<String>()
            for (line in text.split('\n')) {
                val stamps = STAMP.findAll(line).toList()
                val content = WORD_STAMP.replace(STAMP.replace(line, ""), "").trim()
                when {
                    stamps.isNotEmpty() -> stamps.forEach { timed += Line(millis(it), content) }
                    META.matches(line.trim()) -> Unit
                    else -> plain += content
                }
            }
            if (timed.isNotEmpty() && timed.size >= maxOf(1, plain.count { it.isNotEmpty() } / 2)) {
                return Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = source, offsetMs = offset)
            }
            val lines = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }.map { Line(null, it) }
            return Lyrics(lines, synced = false, source = source)
        }

        private fun millis(match: MatchResult): Long {
            val (minutes, seconds, fraction) = match.destructured
            return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + fraction.padEnd(3, '0').take(3).toLong()
        }
    }
}
