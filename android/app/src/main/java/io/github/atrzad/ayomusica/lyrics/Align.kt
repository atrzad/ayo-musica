package io.github.atrzad.ayomusica.lyrics

import io.github.atrzad.ayomusica.data.fold

/**
 * Gives times to plain lyrics from the words heard in the song: the lyric words and the heard words are
 * aligned as two sequences (longest common subsequence); each line starts at its first matched word, and
 * lines without a match are placed between their neighbours. Same idea as the desktop app.
 */
object Align {
    data class Word(val text: String, val startMs: Long, val endMs: Long)

    /** whisper tokens ("t0\tt1\ttext" lines) → words; tokens without a leading space continue a word. */
    fun words(tokens: String): List<Word> {
        val words = mutableListOf<Word>()
        var bracket = false  // inside a whisper note like "[Música]" or "(risos)", which comes in pieces
        for (line in tokens.lines()) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) continue
            val (start, end, text) = parts
            if ('[' in text || '(' in text) bracket = true
            if (bracket) {
                if (']' in text || ')' in text) bracket = false
                continue
            }
            if (text.isBlank()) continue
            val t0 = start.toLongOrNull() ?: continue
            val t1 = end.toLongOrNull() ?: t0
            if (text.startsWith(" ") || words.isEmpty()) words += Word(text.trim(), t0, t1)
            else words[words.lastIndex] = words.last().copy(text = words.last().text + text.trim(), endMs = t1)
        }
        return words.filter { word -> word.text.any { it.isLetterOrDigit() } }
    }

    private fun clean(word: String) = fold(word).filter { it.isLetterOrDigit() }

    /**
     * Lyrics from the words heard alone (no text to align to): a new line after a pause, at the end of a sentence, or
     * when a line gets long. Null when too little was heard to be lyrics (an instrumental, say).
     */
    fun transcript(heard: List<Word>, source: String = "transcrita"): Lyrics? {
        val lines = mutableListOf<Lyrics.Line>()
        var current = mutableListOf<Word>()
        fun close() {
            if (current.isNotEmpty()) {
                val text = current.joinToString(" ") { it.text }.replaceFirstChar { it.uppercase() }
                lines += Lyrics.Line(current.first().startMs, text)
            }
            current = mutableListOf()
        }
        for (word in heard) {
            val last = current.lastOrNull()
            val pause = last != null && word.startMs - last.endMs > 800
            val sentence = last != null && (current.size >= 3 && last.text.last() in ".!?" ||
                current.size >= 5 && last.text.last() in ",;:")
            if (pause || sentence || current.size >= 9) close()
            current += word
        }
        close()
        return Lyrics(lines, synced = true, source = source).takeIf { lines.size >= 4 && heard.size >= 15 }
    }

    /** (synced lyrics, share of lyric words that were heard) or null. */
    fun align(lyrics: Lyrics, heard: List<Word>): Pair<Lyrics, Double>? {
        val lines = lyrics.lines.map { it.text }
        val lyricWords = mutableListOf<String>()
        val owner = mutableListOf<Int>()
        lines.forEachIndexed { number, text ->
            fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.forEach {
                lyricWords += it
                owner += number
            }
        }
        val spoken = heard.map { clean(it.text) to it.startMs }.filter { it.first.isNotEmpty() }
        if (lyricWords.isEmpty() || spoken.isEmpty()) return null
        val n = lyricWords.size
        val m = spoken.size
        // Longest common subsequence, with a traceback.
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (lyricWords[i] == spoken[j].first) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val starts = arrayOfNulls<Long>(lines.size)
        var i = 0
        var j = 0
        var matched = 0
        while (i < n && j < m) {
            when {
                lyricWords[i] == spoken[j].first -> {
                    val line = owner[i]
                    if (starts[line] == null) starts[line] = spoken[j].second
                    matched++; i++; j++
                }
                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
        // Times must grow line by line; a match that goes backwards is noise.
        var last = -1L
        for (k in starts.indices) {
            val time = starts[k] ?: continue
            if (time <= last) starts[k] = null else last = time
        }
        val known = starts.indices.filter { starts[it] != null }
        if (known.isEmpty()) return null
        val withText = lines.indices.filter { lines[it].isNotBlank() }
        for (k in withText) {
            if (starts[k] != null) continue
            val before = known.lastOrNull { it < k }
            val after = known.firstOrNull { it > k }
            starts[k] = when {
                before != null && after != null ->
                    starts[before]!! + (starts[after]!! - starts[before]!!) * (k - before) / (after - before)
                before != null -> starts[before]!! + 2500L * (k - before)
                else -> maxOf(0L, starts[after!!]!! - 2500L * (after - k))
            }
        }
        val synced = withText.map { Lyrics.Line(starts[it], lines[it]) }.sortedBy { it.timeMs }
        return Lyrics(synced, synced = true, source = "voz") to matched.toDouble() / n
    }

    private val PORTUGUESE = setOf("que", "nao", "eu", "voce", "pra", "meu", "minha", "uma", "com", "sem", "tudo", "mais",
        "quando", "gente", "ta", "tem", "isso", "ela", "ele", "vida", "amor", "mim", "teu", "tua", "nos", "sou")
    private val ENGLISH = setOf("the", "and", "you", "my", "to", "it", "is", "in", "me", "your", "that", "we", "be", "on",
        "love", "all", "what", "im", "dont", "cant", "just", "like", "know", "with", "for", "this")
    private val SPANISH = setOf("que", "el", "la", "yo", "tu", "mi", "con", "por", "para", "pero", "como", "todo", "quiero")

    /** "pt", "en", "es" or "auto" from the lyrics' most common short words. */
    fun language(text: String): String {
        val words = fold(text).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.size < 8) return "auto"
        val scores = mapOf("pt" to words.count { it in PORTUGUESE }, "en" to words.count { it in ENGLISH },
            "es" to words.count { it in SPANISH })
        val best = scores.maxBy { it.value }
        return if (best.value >= maxOf(3, (words.size * 0.04).toInt())) best.key else "auto"
    }
}
