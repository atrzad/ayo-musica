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
               lengthMs: Long = MAX_SECONDS * 1000L, progress: ((Float) -> Unit)? = null): FloatArray {
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
        // Room for the whole stretch up front (no copies while growing).
        val seconds = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000_000 else 240
        val out = FloatArrayBuilder((minOf(limit, RATE * (seconds + 2)) + RATE).toInt())
        var floats = FloatArray(0)
        var shortsRead = ShortArray(0)
        var lastReported = -1
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
                // Never wait while there is work: waiting 10 ms per audio frame made a 4-minute song take over a minute.
                var fed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
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
                        fed = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, if (fed) 0 else 5_000)
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
                        // Read the whole buffer at once and mix/resample in one tight loop (millions of samples).
                        val count: Int
                        if (floatPcm) {
                            val source = buffer.asFloatBuffer()
                            count = source.remaining()
                            if (floats.size < count) floats = FloatArray(count)
                            source.get(floats, 0, count)
                        } else {
                            val source = buffer.asShortBuffer()
                            count = source.remaining()
                            if (shortsRead.size < count) shortsRead = ShortArray(count)
                            source.get(shortsRead, 0, count)
                            if (floats.size < count) floats = FloatArray(count)
                            for (i in 0 until count) floats[i] = shortsRead[i] / 32768f
                        }
                        var i = 0
                        while (i + channels <= count) {
                            var sum = 0f
                            for (c in 0 until channels) sum += floats[i + c]
                            val sample = sum / channels
                            while (position <= inputFrames) {  // linear interpolation to 16 kHz
                                val t = (position - (inputFrames - 1)).toFloat().coerceIn(0f, 1f)
                                out.add(previous + (sample - previous) * t)
                                position += step
                            }
                            previous = sample
                            inputFrames++
                            i += channels
                        }
                        progress?.let { report ->
                            val second = out.size / RATE
                            if (second != lastReported) {  // about once per second of audio
                                lastReported = second
                                report((out.size.toFloat() / out.capacity).coerceIn(0f, 1f))
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
        val capacity: Int get() = data.size
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
