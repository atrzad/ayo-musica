package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Which way the animated finger moves on a page (null: no gesture, just an icon). */
private enum class Motion { Left, Right, Down, Up, Fan, Tap }

private data class Step(val title: String, val text: String, val icon: ImageVector, val motions: List<Motion>)

private val STEPS = listOf(
    Step("Tela inicial",
        "Toque em AYO PLAYER, no alto, para abrir as Configurações. À direita fica a música que está tocando: " +
            "toque nela para abrir o Tocando agora, ou arraste para o lado para voltar ou avançar.",
        Icons.Rounded.MusicNote, listOf(Motion.Tap, Motion.Right)),
    Step("Trocar de aba",
        "Segure o botão redondo embaixo e arraste na direção da aba: Músicas, Playlists, Álbuns, Artistas… " +
            "A aba apontada cresce; solte para abrir. Um toque simples mostra as abas para tocar.",
        Icons.Rounded.TouchApp, listOf(Motion.Fan)),
    Step("Gestos no Tocando agora",
        "Deslize para a direita (uma vez): próxima música.\nDeslize para a esquerda (uma vez): volta ao começo " +
            "da música; duas vezes seguidas: volta uma música.\nDeslize para baixo: abre a letra.",
        Icons.Rounded.MusicNote, listOf(Motion.Right, Motion.Left, Motion.Down)),
    Step("Aleatório, repetir e curtir",
        "As setas do aleatório se cruzam quando ele está ligado. Repetir: um toque repete a lista ou o álbum, " +
            "dois toques repetem só a música. O coração guarda a música em Curtidas.",
        Icons.Rounded.Repeat, listOf(Motion.Tap)),
    Step("Letra",
        "A linha cantada fica em destaque; toque numa linha para ir até ela. Deslize para cima para voltar ao " +
            "Tocando agora. Sem letra? Use Buscar letra. Letra sem tempos? Sincronizar pela voz ou tocando.",
        Icons.Rounded.Lyrics, listOf(Motion.Up)),
    Step("Modos",
        "Em Configurações → Modo: o modo carro deita a tela e deixa só botões grandes; o modo simplificado troca " +
            "os gestos por botões, com a música tocando numa barra embaixo, acima das abas fixas. Também dá para escolher tema, equalizador e as abas.",
        Icons.Rounded.DirectionsCar, emptyList()),
)

/** How to use the app: shown on the first run, and from Configurações → Como usar. */
@Composable
fun TutorialScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { STEPS.size }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Como usar", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDone) { Text("Pular") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val step = STEPS[page]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    GestureDemo(step.icon, step.motions)
                    Spacer(Modifier.height(32.dp))
                    Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(step.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(STEPS.size) { index ->
                    Box(Modifier.padding(4.dp).size(if (index == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            val last = pager.currentPage == STEPS.lastIndex
            Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                Modifier.fillMaxWidth().height(56.dp)) { Text(if (last) "Começar" else "Próximo") }
        }
    }
}

/** A phone-like card with the step's icon and a finger dot sliding the way the gesture goes. */
@Composable
private fun GestureDemo(icon: ImageVector, motions: List<Motion>) {
    val transition = rememberInfiniteTransition(label = "gesture")
    val count = motions.size.coerceAtLeast(1)
    val time by transition.animateFloat(0f, count.toFloat(),
        infiniteRepeatable(tween(1600 * count, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "time")
    val motion = motions.getOrNull(time.toInt().coerceAtMost(count - 1))
    val t = time - time.toInt()
    val dot = MaterialTheme.colorScheme.primary
    val trail = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Box(Modifier.size(width = 200.dp, height = 260.dp).clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (motion != null) Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val reach = size.minDimension * 0.32f
            val (from, to) = when (motion) {
                Motion.Right -> Offset(center.x - reach, center.y + reach) to Offset(center.x + reach, center.y + reach)
                Motion.Left -> Offset(center.x + reach, center.y + reach) to Offset(center.x - reach, center.y + reach)
                Motion.Down -> Offset(center.x + reach, center.y - reach) to Offset(center.x + reach, center.y + reach)
                Motion.Up -> Offset(center.x + reach, center.y + reach) to Offset(center.x + reach, center.y - reach)
                Motion.Fan -> Offset(center.x, size.height - 28.dp.toPx()) to Offset(center.x - reach * 0.7f, center.y - reach * 0.4f)
                Motion.Tap -> Offset(center.x + reach * 0.6f, center.y - reach) to Offset(center.x + reach * 0.6f, center.y - reach)
            }
            val progress = (t * 1.3f).coerceAtMost(1f)
            val at = Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
            drawLine(trail, from, at, 10.dp.toPx(), StrokeCap.Round)
            val radius = if (motion == Motion.Tap) 14.dp.toPx() * (1f - 0.3f * kotlin.math.sin(t * Math.PI.toFloat())) else 14.dp.toPx()
            drawCircle(dot, radius, at)
        }
    }
}
