/*
 * Shazam audio fingerprint, ported from SongRec (https://github.com/marin-m/SongRec) by marin-m,
 * licensed under the GNU General Public License v3.0. Because of this file the Android app is GPL-3.0
 * (see android/LICENSE).
 */
package io.github.atrzad.ayomusica.analyzer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * The fingerprint Shazam recognizes: spectral peaks of 16 kHz mono audio in four frequency bands. Only these peaks
 * are sent (never the sound itself).
 */
class ShazamSignature(samples: ShortArray) {
    class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    val numberSamples = samples.size
    /** Peaks per band (0: 250–520 Hz, 1: 520–1450, 2: 1450–3500, 3: 3500–5500), in time order. */
    val bands: Map<Int, List<Peak>>

    init {
        val found = sortedMapOf<Int, MutableList<Peak>>()
        val generator = Generator { band, peak -> found.getOrPut(band) { mutableListOf() } += peak }
        var position = 0
        while (samples.size - position >= 128) {
            generator.process(samples, position)
            position += 128
        }
        bands = found
    }

    /** The binary message ("data:audio/vnd.shazam.sig;base64,...") Shazam's servers take. */
    fun uri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(binary())

    fun binary(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, peaks) in bands) {
            val buffer = ByteArrayOutputStream()
            var pass = 0
            for (peak in peaks) {
                if (peak.pass - pass >= 255) {
                    buffer.write(0xFF)
                    buffer.u32(peak.pass)
                    pass = peak.pass
                }
                buffer.write(peak.pass - pass)
                buffer.u16(peak.magnitude)
                buffer.u16(peak.bin)
                pass = peak.pass
            }
            val bytes = buffer.toByteArray()
            contents.u32(0x60030040 + band)
            contents.u32(bytes.size)
            contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val message = ByteArrayOutputStream()
        message.u32(0xCAFE2580.toInt())
        message.u32(0)  // CRC-32, filled in below
        message.u32(body.size + 8)
        message.u32(0x94119C00.toInt())
        repeat(3) { message.u32(0) }
        message.u32(3 shl 27)  // 16000 Hz
        repeat(2) { message.u32(0) }
        message.u32((numberSamples + 16000 * 0.24).toInt())
        message.u32((15 shl 19) + 0x40000)
        message.u32(0x40000000)
        message.u32(body.size + 8)
        message.write(body)
        val bytes = message.toByteArray()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        for (i in 0 until 4) bytes[4 + i] = (crc ushr (8 * i)).toByte()
        return bytes
    }

    private fun ByteArrayOutputStream.u32(value: Int) { for (i in 0 until 4) write((value ushr (8 * i)) and 0xFF) }
    private fun ByteArrayOutputStream.u16(value: Int) { write(value and 0xFF); write((value ushr 8) and 0xFF) }

    /** SongRec's SignatureGenerator: a 2048-sample FFT every 128 samples, spreading, then peak picking. */
    private class Generator(private val emit: (band: Int, peak: Peak) -> Unit) {
        private val ring = ShortArray(2048)
        private var ringPosition = 0
        private val fftOutputs = Array(256) { DoubleArray(1025) }
        private var fftPosition = 0
        private val spread = Array(256) { DoubleArray(1025) }
        private var spreadPosition = 0
        private var spreadWritten = 0
        private val real = DoubleArray(2048)
        private val imaginary = DoubleArray(2048)

        fun process(samples: ShortArray, from: Int) {
            for (i in 0 until 128) ring[(ringPosition + i) % 2048] = samples[from + i]
            ringPosition = (ringPosition + 128) % 2048
            fft()
            spreadPeaks()
            if (spreadWritten >= 46) recognize()
        }

        private fun fft() {
            for (i in 0 until 2048) {
                real[i] = HANNING[i] * ring[(ringPosition + i) % 2048]
                imaginary[i] = 0.0
            }
            Fft.transform(real, imaginary)
            val out = fftOutputs[fftPosition]
            for (i in 0 until 1025) out[i] = max((real[i] * real[i] + imaginary[i] * imaginary[i]) / (1 shl 17), 1e-10)
            fftPosition = (fftPosition + 1) % 256
        }

        private fun spreadPeaks() {
            val origin = fftOutputs[(fftPosition - 1 + 256) % 256]
            val last = origin.copyOf()
            for (position in 0 until 1025) {
                if (position < 1023) last[position] = maxOf(last[position], last[position + 1], last[position + 2])
                var value = last[position]
                for (former in intArrayOf(-1, -3, -6)) {
                    val output = spread[((spreadPosition + former) % 256 + 256) % 256]
                    value = max(output[position], value)
                    output[position] = value
                }
            }
            last.copyInto(spread[spreadPosition])
            spreadPosition = (spreadPosition + 1) % 256
            spreadWritten++
        }

        private fun spreadAt(offset: Int) = spread[((spreadPosition + offset) % 256 + 256) % 256]

        private fun recognize() {
            val minus46 = fftOutputs[((fftPosition - 46) % 256 + 256) % 256]
            val minus49 = spreadAt(-49)
            for (bin in 10 until 1015) {
                val value = minus46[bin]
                if (value < 1.0 / 64 || value < minus49[bin - 1]) continue
                var neighbors = 0.0
                for (offset in NEIGHBORS) neighbors = max(minus49[bin + offset], neighbors)
                if (value <= neighbors) continue
                var others = neighbors
                for (offset in OTHERS) others = max(spreadAt(offset)[bin - 1], others)
                if (value <= others) continue
                val pass = spreadWritten - 46
                fun magnitude(v: Double) = ln(max(1.0 / 64, v)) * 1477.3 + 6144
                val peak = magnitude(value)
                val before = magnitude(minus46[bin - 1])
                val after = magnitude(minus46[bin + 1])
                val variation1 = peak * 2 - before - after
                val variation2 = (after - before) * 32 / variation1
                val corrected = bin * 64 + variation2
                val hz = corrected * (16000.0 / 2 / 1024 / 64)
                val band = when {
                    hz < 250 -> continue
                    hz < 520 -> 0
                    hz < 1450 -> 1
                    hz < 3500 -> 2
                    hz <= 5500 -> 3
                    else -> continue
                }
                emit(band, Peak(pass, peak.toInt(), corrected.toInt()))
            }
        }

        companion object {
            // numpy.hanning(2050)[1:-1]: a Hanning window without the zeros at its ends.
            val HANNING = DoubleArray(2048) { 0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049) }
            val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
            val OTHERS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
        }
    }

    /** In-place radix-2 FFT (size 2048). */
    private object Fft {
        private const val N = 2048
        private val cosTable = DoubleArray(N / 2) { cos(2 * PI * it / N) }
        private val sinTable = DoubleArray(N / 2) { sin(2 * PI * it / N) }

        fun transform(re: DoubleArray, im: DoubleArray) {
            var j = 0
            for (i in 1 until N) {
                var bit = N shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var size = 2
            while (size <= N) {
                val half = size / 2
                val step = N / size
                var start = 0
                while (start < N) {
                    for (k in 0 until half) {
                        val c = cosTable[k * step]
                        val s = -sinTable[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * c - im[b] * s
                        val ti = re[b] * s + im[b] * c
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                    }
                    start += size
                }
                size *= 2
            }
        }
    }
}

/**
 * Asks Shazam which song a fingerprint is (the same request SongRec makes). Shazam has no public API for this, so it
 * may stop answering one day; then this source just finds nothing.
 */
class Shazam(private val post: (String, String) -> String? = ::send) {
    /** The song in [samples] (16 kHz mono), or null when Shazam does not know it. */
    fun recognize(samples: ShortArray): Candidate? {
        val signature = ShazamSignature(samples)
        if (signature.bands.values.sumOf { it.size } < 30) return null  // silence or almost
        val now = System.currentTimeMillis()
        val body = """{"geolocation":{"altitude":300,"latitude":45,"longitude":2},""" +
            """"signature":{"samplems":${signature.numberSamples * 1000L / 16000},"timestamp":$now,"uri":"${signature.uri()}"},""" +
            """"timestamp":$now,"timezone":"America/Sao_Paulo"}"""
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${UUID.randomUUID().toString().uppercase()}/" +
            "${UUID.randomUUID()}?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        return parse(post(url, body) ?: return null)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Shazam's answer → candidate, or null when nothing matched. */
        fun parse(body: String): Candidate? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if ((root["matches"] as? JsonArray)?.isEmpty() == true) return null
            val track = root["track"] as? JsonObject ?: return null
            fun JsonObject.text(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            val metadata = (track["sections"] as? JsonArray).orEmpty().map { it.jsonObject }
                .firstOrNull { it.text("type") == "SONG" }?.get("metadata")?.jsonArray.orEmpty()
                .associate { item -> item.jsonObject.text("title") to item.jsonObject.text("text") }
            val images = track["images"] as? JsonObject
            val cover = images?.text("coverarthq")?.ifBlank { null } ?: images?.text("coverart")
            val big = cover?.replace(Regex("""/\d+x\d+(cc|bb)?\.(jpg|png)$"""), "/1000x1000$1.$2")
            val title = track.text("title")
            if (title.isBlank()) return null
            return Candidate(
                Source.Shazam, track.text("key"), title, track.text("subtitle"), metadata["Album"].orEmpty(),
                year = metadata["Released"]?.take(4)?.toIntOrNull() ?: 0,
                genre = (track["genres"] as? JsonObject)?.text("primary").orEmpty(),
                thumb = cover?.ifBlank { null }, covers = listOfNotNull(big, cover).filter { it.isNotBlank() }.distinct(),
            )
        }

        fun send(url: String, body: String): String? {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Content-Language", "en_US")
            connection.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 7 Build/UQ1A.240205.002)")
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                return when (val code = connection.responseCode) {
                    200 -> connection.inputStream.bufferedReader().use { it.readText() }
                    429 -> throw IOException("Shazam pediu para esperar (muitas buscas seguidas)")
                    else -> throw IOException("Shazam respondeu $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
