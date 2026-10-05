package io.github.atrzad.ayomusica.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size

/** Cover art of a song, read from the file's tags through MediaStore. Cached in memory. */
object Artwork {
    // An eighth of the app's memory (often 32–64 MB): enough for the screens in view, never the whole library.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = mutableSetOf<String>()

    fun cached(uri: Uri, size: Int): Bitmap? = cache.get("$uri@$size")

    /** Blocking: call off the main thread. Null when the song has no cover. */
    fun load(context: Context, uri: Uri, size: Int): Bitmap? {
        val key = "$uri@$size"
        cache.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }
        val bitmap = runCatching {
            if (uri.scheme == "file") {  // official cover saved by the analyzer
                uri.path?.let { java.io.File(it).readBytes() }?.let { decode(it, size) }
            } else if (uri.scheme == "https" || uri.scheme == "http") {  // a cloud song's cover
                cloudCover(context, uri.toString())?.let { decode(it, size) }
            } else if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            } else {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, uri)
                        embeddedPicture?.let { decode(it, size) }
                    } finally {
                        release()
                    }
                }
            }
        }.getOrNull()
        if (bitmap == null) synchronized(missing) { missing += key } else cache.put(key, bitmap)
        return bitmap
    }

    /** The server's picture, kept on disk too (so covers show offline after the first time). */
    private fun cloudCover(context: Context, url: String): ByteArray? {
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(context.cacheDir, "cloud-covers/$name")
        if (file.exists()) return file.readBytes()
        if (!io.github.atrzad.ayomusica.sync.Account.owns(url)) return null
        val ok = runCatching { io.github.atrzad.ayomusica.sync.Api.download(url.removePrefix(io.github.atrzad.ayomusica.sync.Account.server), file) }
            .getOrDefault(false)
        return if (ok) file.readBytes() else null
    }

    fun decode(data: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
