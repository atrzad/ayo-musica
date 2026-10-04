package io.github.atrzad.ayomusica.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Any audio file → 16 kHz mono floats (-1..1), what whisper expects. */
object AudioDecoder {
    private const val RATE = 16_000
    private const val MAX_SECONDS = 15 * 60

    /** The whole song, or only [lengthMs] from [fromMs] (recognizing by the sound needs a few seconds, not all). */
    fun decode(context: Context, uri: Uri, cancelled: () -> Boolean = { false }, fromMs: Long = 0,
               lengthMs: Long = MAX_SECONDS * 1000L): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("Sem trilha de áudio")
        extractor.selectTrack(track)
        if (fromMs > 0) extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val limit = RATE * minOf(lengthMs, MAX_SECONDS * 1000L) / 1000
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = FloatArrayBuilder(RATE * 240)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var position = 0.0          // resampling position in input frames
        var previous = 0f
        var inputFrames = 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (!cancelled()) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val step = rate.toDouble() / RATE
                        if (floatPcm) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += floats.get() }
                                val sample = sum / channels
                                while (position <= inputFrames) {  // linear interpolation to 16 kHz
                                    val t = (position - (inputFrames - 1)).toFloat()
                                    out.add(previous + (sample - previous) * t.coerceIn(0f, 1f))
                                    position += step
                                }
                                previous = sample
                                inputFrames++
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += shorts.get() / 32768f }
                                val sample = sum / channels
                                while (position <= inputFrames) {
                                    val t = (position - (inputFrames - 1)).toFloat()
                                    out.add(previous + (sample - previous) * t.coerceIn(0f, 1f))
                                    position += step
                                }
                                previous = sample
                                inputFrames++
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (out.size >= limit) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return out.toArray()
    }

    /** 16 kHz floats → 16-bit samples (what Shazam's and Chromaprint's fingerprints take). */
    fun shorts(audio: FloatArray): ShortArray =
        ShortArray(audio.size) { (audio[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

    private class FloatArrayBuilder(capacity: Int) {
        private var data = FloatArray(capacity)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
