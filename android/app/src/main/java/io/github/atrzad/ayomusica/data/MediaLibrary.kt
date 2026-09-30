package io.github.atrzad.ayomusica.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the songs the phone already knows about (MediaStore); nothing is copied or changed. */
class MediaLibrary(private val context: Context) {

    suspend fun load(): List<Song> = withContext(Dispatchers.IO) {
        val columns = buildList {
            addAll(listOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME,
            ))
            add("album_artist")
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH)
        }.toTypedArray()
        val songs = mutableListOf<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000",
            null, null,
        )?.use { cursor ->
            fun index(name: String) = cursor.getColumnIndex(name)
            val id = index(MediaStore.Audio.Media._ID)
            val title = index(MediaStore.Audio.Media.TITLE)
            val artist = index(MediaStore.Audio.Media.ARTIST)
            val album = index(MediaStore.Audio.Media.ALBUM)
            val albumId = index(MediaStore.Audio.Media.ALBUM_ID)
            val duration = index(MediaStore.Audio.Media.DURATION)
            val track = index(MediaStore.Audio.Media.TRACK)
            val year = index(MediaStore.Audio.Media.YEAR)
            val added = index(MediaStore.Audio.Media.DATE_ADDED)
            val name = index(MediaStore.Audio.Media.DISPLAY_NAME)
            val albumArtist = index("album_artist")
            val path = if (Build.VERSION.SDK_INT >= 29) index(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            while (cursor.moveToNext()) {
                val rawTrack = if (track >= 0) cursor.getInt(track) else 0
                songs += Song(
                    id = cursor.getLong(id),
                    title = cursor.getString(title).orEmpty().ifBlank { cursor.getString(name).orEmpty() },
                    artist = cleanUnknown(cursor.getString(artist)),
                    album = cleanUnknown(cursor.getString(album)),
                    albumId = cursor.getLong(albumId),
                    albumArtist = if (albumArtist >= 0) cleanUnknown(cursor.getString(albumArtist)) else "",
                    durationMs = cursor.getLong(duration),
                    track = rawTrack % 1000,          // MediaStore packs the disc as thousands
                    disc = rawTrack / 1000,
                    year = if (year >= 0) cursor.getInt(year) else 0,
                    dateAdded = if (added >= 0) cursor.getLong(added) else 0,
                    relativePath = if (path >= 0) cursor.getString(path).orEmpty() else "",
                    displayName = cursor.getString(name).orEmpty(),
                )
            }
        }
        songs.sortedBy { fold(it.title) }
    }

    private fun cleanUnknown(value: String?): String =
        value?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING } ?: ""
}
