package io.github.atrzad.ayomusica.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.atrzad.ayomusica.data.Artwork
import java.io.IOException
import java.util.concurrent.Executors

/** Notification and lock-screen covers: songs point at their own file, whose tags hold the picture. */
@UnstableApi
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val fallback = DataSourceBitmapLoader.Builder(context).build()

    override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != "content") return fallback.loadBitmap(uri)
        return executor.submit<Bitmap> {
            Artwork.load(context, uri, 512) ?: throw IOException("Sem capa")
        }
    }
}
