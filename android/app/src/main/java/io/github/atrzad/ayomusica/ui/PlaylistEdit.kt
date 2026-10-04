package io.github.atrzad.ayomusica.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.data.Artwork
import io.github.atrzad.ayomusica.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The playlist's own picture as a uri to show, or null (then the first song's cover is shown). */
val Playlist.artUri: Uri? get() = coverFile?.takeIf { java.io.File(it).exists() }?.let { Uri.fromFile(java.io.File(it)) }

/** Editar playlist: picture (from the photo picker, no permission needed), title and description. */
@Composable
fun EditPlaylistDialog(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, image: Uri?, removeImage: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(playlist.name) }
    var description by remember { mutableStateOf(playlist.description) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var removed by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            picked = uri
            removed = false
        }
    }
    val choose = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val shown = picked ?: playlist.artUri?.takeIf { !removed }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = choose),
                        contentAlignment = Alignment.Center) {
                        if (shown != null) PickedImage(shown) else Icon(Icons.Rounded.AddPhotoAlternate, "Escolher imagem",
                            Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = choose) { Text(if (shown != null) "Trocar imagem" else "Escolher imagem") }
                        if (shown != null) TextButton(onClick = { picked = null; removed = true }) { Text("Remover imagem") }
                    }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Título") }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Descrição") },
                    minLines = 2, maxLines = 5)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, picked, removed) }, enabled = name.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** A picture from the photo picker or the app's storage, read off the main thread. */
@Composable
private fun PickedImage(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Artwork.decode(it, 400) } }
                .getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}
