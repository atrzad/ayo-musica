package io.github.atrzad.ayomusica.voice

import android.content.Context
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.lyrics.Align
import io.github.atrzad.ayomusica.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Syncs plain lyrics by listening to the song with whisper.cpp on the phone. The voice model (about 60 MB)
 * is downloaded once, when first needed.
 */
class VoiceSync(private val context: Context) {
    val model = File(context.filesDir, "whisper/ggml-base-q5_1.bin")
    @Volatile private var cancelled = false

    val hasModel: Boolean get() = model.length() > MODEL_BYTES * 0.9

    /** The voice engine is built for ARMv8.2 phones (fp16 + dot product); older processors can't run it. */
    val supported: Boolean by lazy {
        val features = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "asimddp" in features && ("asimdhp" in features || "fphp" in features)
            "x86_64" -> " avx " in " ${features.replace('\n', ' ')} " || "avx" in features
            else -> false
        }
    }

    suspend fun downloadModel(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        cancelled = false
        model.parentFile?.mkdirs()
        val partial = File(model.parentFile, "${model.name}.part")
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != 200) throw IOException("O servidor do modelo respondeu ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        if (cancelled) throw IOException("Cancelado")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        progress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (partial.length() < MODEL_BYTES * 0.9) {
            partial.delete()
            throw IOException("O download do modelo veio incompleto. Tente de novo.")
        }
        partial.renameTo(model)
    }

    enum class Stage(val text: String) { Download("Baixando o modelo de voz"), Decode("Lendo o áudio"),
        Listen("Ouvindo a música"), Align("Encaixando a letra") }

    /** (synced lyrics, share of words heard). Throws on errors; null when too little was understood. */
    suspend fun sync(song: Song, plain: Lyrics, progress: (Stage, Float) -> Unit): Pair<Lyrics, Double>? =
        withContext(Dispatchers.Default) {
            cancelled = false
            progress(Stage.Decode, -1f)
            val audio = AudioDecoder.decode(context, song.uri, cancelled = { cancelled })
            if (cancelled) throw IOException("Cancelado")
            progress(Stage.Listen, 0f)
            val handle = Whisper.load(model.absolutePath)
            if (handle == 0L) throw IOException("Não deu para abrir o modelo de voz. Baixe de novo.")
            val tokens = try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                Whisper.transcribe(handle, audio, Align.language(plain.text), threads) { percent ->
                    progress(Stage.Listen, percent / 100f)
                }
            } finally {
                Whisper.free(handle)
            }
            if (cancelled || tokens == null) throw IOException("Cancelado")
            progress(Stage.Align, -1f)
            Align.align(plain, Align.words(tokens))?.takeIf { it.second >= 0.2 }
        }

    /** No lyrics anywhere: writes them down from the singing itself, with times. Null when nothing like lyrics was heard. */
    suspend fun transcribe(song: Song, progress: (Stage, Float) -> Unit): Lyrics? = withContext(Dispatchers.Default) {
        cancelled = false
        progress(Stage.Decode, -1f)
        val audio = AudioDecoder.decode(context, song.uri, cancelled = { cancelled })
        if (cancelled) throw IOException("Cancelado")
        progress(Stage.Listen, 0f)
        val handle = Whisper.load(model.absolutePath)
        if (handle == 0L) throw IOException("Não deu para abrir o modelo de voz. Baixe de novo.")
        val tokens = try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            // Whisper guesses the language from the first 30 s, often an instrumental intro, and then hears nothing.
            // So the language comes from 30 s in the middle of the song (where someone is usually singing) first.
            val rate = 16_000
            val from = (audio.size / 3).coerceAtMost(maxOf(0, audio.size - 30 * rate))
            val sample = audio.copyOfRange(from, minOf(audio.size, from + 30 * rate))
            val heard = Whisper.transcribe(handle, sample, "auto", threads, null)
            if (cancelled || heard == null) throw IOException("Cancelado")
            // What whisper itself detected on that stretch; the lyrics' common words as a second opinion.
            val language = Whisper.language(handle).takeIf { it != "auto" && it.isNotBlank() }
                ?: Align.language(Align.words(heard).joinToString(" ") { it.text }).takeIf { it != "auto" }
                ?: java.util.Locale.getDefault().language.takeIf { it in setOf("pt", "en", "es") } ?: "auto"
            Whisper.transcribe(handle, audio, language, threads) { percent -> progress(Stage.Listen, percent / 100f) }
        } finally {
            Whisper.free(handle)
        }
        if (cancelled || tokens == null) throw IOException("Cancelado")
        Align.transcript(Align.words(tokens))
    }

    fun cancel() {
        cancelled = true
        Whisper.cancel()
    }

    companion object {
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
        const val MODEL_BYTES = 59_707_625L
    }
}
