package io.github.atrzad.ayomusica.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.atrzad.ayomusica.data.Song
import kotlinx.serialization.Serializable

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(shownArtist)
            .setAlbumTitle(shownAlbum)
            .setAlbumArtist(albumArtist.ifBlank { null })
            .setTrackNumber(track.takeIf { it > 0 })
            .setDurationMs(durationMs.takeIf { it > 0 })
            .setArtworkUri(artUri)  // the official cover, or the file's own (ArtworkBitmapLoader)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            // How the other devices know this song (its tags, not the Analyzer's corrections): continue/play there.
            .setExtras(android.os.Bundle().apply {
                putString(SYNC_KEY, syncKey.ifBlank { io.github.atrzad.ayomusica.sync.SongKeys.of(this@toMediaItem) })
            })
            .build(),
    )
    .build()

const val SYNC_KEY = "ayo.syncKey"

/** The song key a queued item travels with (see [SYNC_KEY]); computed from what is shown for older items. */
fun MediaItem.syncKey(): String = mediaMetadata.extras?.getString(SYNC_KEY)
    ?: io.github.atrzad.ayomusica.sync.SongKeys.of(mediaMetadata.artist?.toString().orEmpty(),
        mediaMetadata.title?.toString().orEmpty(), mediaMetadata.durationMs ?: 0)

/** A file opened from another app (not in the library). */
fun externalMediaItem(uri: Uri, name: String): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setIsPlayable(true).setIsBrowsable(false).build())
    .build()

/** The queue saved between sessions (what each MediaItem needs to be played again). */
@Serializable
data class SavedItem(
    val mediaId: String,
    val uri: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
) {
    fun toMediaItem(): MediaItem {
        val parsed = Uri.parse(uri)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(parsed)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(parsed).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                    .setDurationMs(durationMs.takeIf { it > 0 }).setArtworkUri(parsed)
                    .setIsPlayable(true).setIsBrowsable(false).build(),
            )
            .build()
    }

    companion object {
        fun of(item: MediaItem): SavedItem? {
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return null
            val metadata = item.mediaMetadata
            return SavedItem(item.mediaId, uri.toString(), metadata.title?.toString().orEmpty(),
                metadata.artist?.toString().orEmpty(), metadata.albumTitle?.toString().orEmpty(),
                metadata.durationMs ?: 0)
        }
    }
}
