package io.github.atrzad.ayomusica.voice

/** whisper.cpp (native, see src/main/cpp). Runs on the device; nothing is sent anywhere. */
object Whisper {
    fun interface Progress {
        fun onProgress(percent: Int)
    }

    init {
        System.loadLibrary("ayowhisper")
    }

    @JvmStatic external fun load(model: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun cancel()
    @JvmStatic private external fun transcribeBytes(handle: Long, audio: FloatArray, language: String, threads: Int,
                                                    listener: Progress?): ByteArray?

    /** "t0\tt1\ttext" per token (ms), or null if it failed or was cancelled. One at a time (the native side waits). */
    fun transcribe(handle: Long, audio: FloatArray, language: String, threads: Int, listener: Progress?): String? =
        transcribeBytes(handle, audio, language, threads, listener)?.toString(Charsets.UTF_8)
    /** The language the last transcription used ("pt", "en"...): what whisper detected when asked for "auto". */
    @JvmStatic external fun language(handle: Long): String
}
