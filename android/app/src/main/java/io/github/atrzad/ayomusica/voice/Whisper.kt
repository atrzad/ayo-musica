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
    /** "t0\tt1\ttext" per token (ms), or null if it failed or was cancelled. */
    @JvmStatic external fun transcribe(handle: Long, audio: FloatArray, language: String, threads: Int, listener: Progress?): String?
}
