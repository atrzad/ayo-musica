package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.data.durationText
import kotlinx.coroutines.delay

/** Dispositivos: the account's other devices, like Spotify Connect — continue here, send there, control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    devices: List<RemoteDevice>,
    playingHere: Boolean,
    onRefresh: () -> Unit,
    onPlayHere: (RemoteDevice) -> Unit,
    onPlayThere: (RemoteDevice) -> Unit,
    onCommand: (RemoteDevice, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // While open, the list follows what the other devices do.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(3_000)
        }
    }
    // Opened all the way (half open hid the buttons below), scrolling when there are many devices.
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dispositivos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (devices.isEmpty()) {
                Text("Nenhum outro aparelho ainda. Abra o Ayo Música no computador (ou em outro celular) com a mesma conta.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            devices.forEach { device ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (device.platform == "android") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null,
                        tint = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text(when {
                            !device.online -> "Fora do ar"
                            device.title.isBlank() -> "Nada tocando"
                            else -> (if (device.playing) "Tocando " else "Pausado em ") + "“${device.title}” · ${device.artist}"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (device.title.isNotBlank() && device.durationMs > 0) {
                            Text("${durationText(device.positionMs)} de ${durationText(device.durationMs)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (device.online && device.title.isNotBlank()) {
                    // Remote control of that device.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { onCommand(device, "previous") }) { Icon(Icons.Rounded.SkipPrevious, "Anterior lá") }
                        IconButton(onClick = { onCommand(device, "toggle") }) {
                            Icon(if (device.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (device.playing) "Pausar lá" else "Retomar lá")
                        }
                        IconButton(onClick = { onCommand(device, "next") }) { Icon(Icons.Rounded.SkipNext, "Próxima lá") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (device.title.isNotBlank()) Button(onClick = { onPlayHere(device); onDismiss() }) { Text("Continuar aqui") }
                    if (device.online && playingHere) OutlinedButton(onClick = { onPlayThere(device); onDismiss() }) { Text("Tocar lá") }
                }
            }
        }
    }
}

/** "Continuar de onde parou": shown on the home screen when another device played something recently. */
@Composable
fun ContinueBanner(device: RemoteDevice, onContinue: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onContinue).padding(start = 14.dp, end = 4.dp,
            top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (device.platform == "android") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Continuar de ${device.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("${device.title} · ${device.artist}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        TextButton(onClick = onContinue) { Text("Continuar") }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Agora não", tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}
