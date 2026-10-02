package io.github.atrzad.ayomusica.lyrics

/** Synced lyrics → LRC text (the delay is baked into the times). */
object LrcWriter {
    fun write(lyrics: Lyrics, title: String = "", artist: String = ""): String {
        val head = listOfNotNull(title.takeIf { it.isNotBlank() }?.let { "[ti:$it]" },
            artist.takeIf { it.isNotBlank() }?.let { "[ar:$it]" }, "[re:Ayo Música]")
        val body = lyrics.lines.map { line ->
            val time = line.timeMs ?: return@map line.text
            val ms = maxOf(0, time - lyrics.offsetMs)
            "[%02d:%02d.%02d]%s".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 10, line.text)
        }
        return (head + body).joinToString("\n") + "\n"
    }
}
