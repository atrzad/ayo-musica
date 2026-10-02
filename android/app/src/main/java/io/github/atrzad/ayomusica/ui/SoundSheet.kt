package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrzad.ayomusica.playback.AudioEffects
import io.github.atrzad.ayomusica.playback.SleepTimer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP = listOf(15, 30, 45, 60, 90)
private val PRESET_NAMES = mapOf("Classical" to "Clássica", "Flat" to "Plano", "Heavy Metal" to "Metal",
    "Hip Hop" to "Hip-hop")

/** Equalizer on/off, presets, bands and bass (Configurações → Equalizador). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerContent() {
    val effects by AudioEffects.ui.collectAsStateWithLifecycle()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizador", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Switch(effects.enabled, AudioEffects::setEnabled, enabled = effects.available)
        }
        if (!effects.available) {
            Text("O equalizador fica disponível depois que uma música começar a tocar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            effects.presets.forEachIndexed { index, name ->
                FilterChip(effects.preset == index, { AudioEffects.usePreset(index) },
                    label = { Text(PRESET_NAMES[name] ?: name) })
            }
            FilterChip(effects.preset == AudioEffects.CUSTOM, {}, label = { Text("Personalizado") })
        }
        effects.bandsHz.forEachIndexed { band, hz ->
            val level = effects.levels.getOrElse(band) { 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodySmall)
                // Neutral track: a band at 0 dB sits in the middle without a heavy bar up to it.
                val track = MaterialTheme.colorScheme.outlineVariant
                Slider(level.toFloat(), { AudioEffects.setLevel(band, it.toInt()) }, Modifier.weight(1f),
                    valueRange = effects.minLevel.toFloat()..effects.maxLevel.toFloat(),
                    colors = SliderDefaults.colors(activeTrackColor = track, inactiveTrackColor = track))
                Text("%+.0f dB".format(level / 100f), Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (effects.bassSupported) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Graves", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                Slider(effects.bass.toFloat(), { AudioEffects.setBass(it.toInt()) }, Modifier.weight(1f),
                    valueRange = 0f..1000f)
                Text("${effects.bass / 10}%", Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = AudioEffects::reset) { Text("Zerar") }
    }
}

/** Speed and sleep timer (from the player's menu). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSleepContent(speed: Float, onSpeed: (Float) -> Unit, sleep: SleepTimer.Mode, onSleep: (Int?) -> Unit) {
    Column {
        Text("Velocidade", style = MaterialTheme.typography.titleLarge)
        Text("A voz mantém o tom.", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEEDS.forEach { value ->
                FilterChip(kotlin.math.abs(speed - value) < 0.01f, { onSpeed(value) },
                    label = { Text("${value.toString().removeSuffix(".0").replace('.', ',')}×") })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Timer de sono", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Text(when (sleep) {
                is SleepTimer.Mode.Minutes -> "Para às " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sleep.endsAt))
                SleepTimer.Mode.EndOfSong -> "Para no fim desta música"
                SleepTimer.Mode.Off -> ""
            }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(sleep == SleepTimer.Mode.Off, { onSleep(null) }, label = { Text("Desligado") })
            SLEEP.forEach { minutes ->
                FilterChip(false, { onSleep(minutes) }, label = {
                    Text(if (minutes < 60) "$minutes min" else "${minutes / 60}h${if (minutes % 60 > 0) "${minutes % 60}" else ""}")
                })
            }
            FilterChip(sleep == SleepTimer.Mode.EndOfSong, { onSleep(0) }, label = { Text("Fim da música") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSleepSheet(speed: Float, onSpeed: (Float) -> Unit, sleep: SleepTimer.Mode, onSleep: (Int?) -> Unit,
                    onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            SpeedSleepContent(speed, onSpeed, sleep, onSleep)
        }
    }
}
