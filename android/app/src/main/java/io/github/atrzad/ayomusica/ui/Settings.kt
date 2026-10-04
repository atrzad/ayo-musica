package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.analyzer.Verdict
import io.github.atrzad.ayomusica.data.Song

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.Tutorial -> Icons.Rounded.TouchApp
    SettingsPage.Analyzer -> Icons.Rounded.AutoFixHigh
    SettingsPage.Equalizer -> Icons.Rounded.Equalizer
    SettingsPage.UseModes -> Icons.Rounded.DirectionsCar
    SettingsPage.Themes -> Icons.Rounded.Palette
    SettingsPage.HomeTabs -> Icons.Rounded.Home
    SettingsPage.LyricsSettings -> Icons.Rounded.Lyrics
}

private fun SettingsPage.detail(): String = when (this) {
    SettingsPage.Tutorial -> "Os gestos e botões do app, passo a passo"
    SettingsPage.Analyzer -> "Busca título, artista, álbum, ano e capa oficial das músicas"
    SettingsPage.Equalizer -> "Ajustar, ligar e desligar"
    SettingsPage.UseModes -> "Normal, carro, simplificado e tela cheia"
    SettingsPage.Themes -> "Cores do app: claro, escuro ou AMOLED"
    SettingsPage.HomeTabs -> "Quais abas aparecem e em que ordem"
    SettingsPage.LyricsSettings -> "Busca de letras na internet"
}

@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit, version: String) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(SettingsPage.entries.toList()) { page ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(page.icon(), null)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(page.title, style = MaterialTheme.typography.titleMedium)
                    Text(page.detail(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Ayo Música $version", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EqualizerPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { EqualizerContent() }
}

@Composable
fun UseModePage(current: UseMode, onSelect: (UseMode) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onFullscreen(!fullscreen) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tela cheia", style = MaterialTheme.typography.titleMedium)
                Text("Esconde a barra de status e a de navegação. Deslize da borda da tela para vê-las por um instante.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(fullscreen, onFullscreen)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        UseMode.entries.forEach { mode ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(mode) }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == current, { onSelect(mode) })
                Column(Modifier.padding(start = 8.dp)) {
                    Text(mode.title, style = MaterialTheme.typography.titleMedium)
                    Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Which tabs show (in the round button or the fixed bar), their order, and where the app opens. */
@Composable
fun HomeTabsPage(tabs: List<Tab>, start: Tab, onTabs: (List<Tab>) -> Unit, onStart: (Tab) -> Unit) {
    val ordered = tabs + Tab.entries.filter { it !in tabs }
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        item {
            Text("Abas da tela inicial", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(ordered, key = { it.name }) { tab ->
            val shown = tab in tabs
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(shown, { on -> onTabs(if (on) tabs + tab else tabs - tab) }, enabled = !shown || tabs.size > 1)
                Icon(tab.icon(), null)
                Text(tab.title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                if (shown) {
                    val at = tabs.indexOf(tab)
                    IconButton(onClick = { onTabs(tabs.toMutableList().apply { add(at - 1, removeAt(at)) }) }, enabled = at > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Subir")
                    }
                    IconButton(onClick = { onTabs(tabs.toMutableList().apply { add(at + 1, removeAt(at)) }) },
                        enabled = at < tabs.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Descer") }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Abrir o app em", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { tab -> FilterChip(tab == start, { onStart(tab) }, label = { Text(tab.title) }) }
            }
        }
    }
}

@Composable
fun LyricsSettingsPage(online: Boolean, onOnline: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Buscar letras na internet", style = MaterialTheme.typography.titleMedium)
                Text("No LRCLIB, banco aberto de letras sincronizadas. Envia artista, título, álbum e duração.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(online, onOnline)
        }
        Text("Sem letra ou letra sem tempos? Na tela da letra (deslize para baixo no Tocando agora) use " +
            "Buscar letra para escolher outra, ou Sincronizar para marcar o tempo de cada linha tocando na tela.",
            Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class AnalyzerTab(val title: String) { Corrected("Corrigidas"), Review("Revisar"), NotFound("Não achadas"), Ignored("Ignoradas") }

private fun AnalysisItem.tab(): AnalyzerTab = when {
    applied -> AnalyzerTab.Corrected
    ignored -> AnalyzerTab.Ignored
    proposal != null -> AnalyzerTab.Review
    else -> AnalyzerTab.NotFound
}

/** Music Analyzer: finds the real title, artist, album, year and official cover of songs online. */
@Composable
fun AnalyzerPage(
    state: AnalysisState,
    items: List<AnalysisItem>,
    counts: AnalyzerCounts,
    onAnalyze: (Boolean) -> Unit,
    onStop: () -> Unit,
    onAccept: (AnalysisItem) -> Unit,
    onUndo: (AnalysisItem) -> Unit,
    onUndoAll: () -> Unit,
    onAcceptAll: () -> Unit,
    onSearch: (AnalysisItem) -> Unit,
    onIgnore: (AnalysisItem) -> Unit,
    onUnignore: (AnalysisItem) -> Unit,
    onRetryNotFound: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AnalyzerTab.Review) }
    val byTab = remember(items) { items.groupBy { it.tab() } }
    val shown = byTab[tab].orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Procura cada música no Deezer, no Apple Music e no MusicBrainz pelas tags e pelo nome do arquivo e " +
                "completa título, artista, álbum, ano, gênero e a capa oficial. Quando título, artista e duração batem, " +
                "aplica sozinho; o resto fica para você revisar. Os resultados ficam salvos: a próxima análise continua " +
                "de onde parou. Seus arquivos não são alterados.",
                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${counts.pending} com informação faltando ainda por analisar · ${counts.analyzed} já analisadas · " +
                "${counts.corrected} corrigidas", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    OutlinedButton(onClick = onStop) { Text("Parar") }
                } else {
                    Button(onClick = { onAnalyze(false) }, enabled = counts.pending > 0) {
                        Text(if (counts.analyzed > 0) "Continuar análise" else "Analisar as com problemas")
                    }
                    OutlinedButton(onClick = { onAnalyze(true) }, enabled = counts.pendingAll > 0) { Text("Todas") }
                }
            }
            if (state.running || state.total > 0) {
                LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    Modifier.fillMaxWidth())
                Text("${state.done} de ${state.total} nesta análise" + if (state.running) "" else " · parada",
                    Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (items.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyzerTab.entries.forEach { option ->
                        val count = byTab[option]?.size ?: 0
                        if (option != AnalyzerTab.Ignored || count > 0) {
                            FilterChip(tab == option, { tab = option }, label = { Text("${option.title} ($count)") })
                        }
                    }
                }
                when (tab) {
                    AnalyzerTab.Corrected -> if (shown.any { it.manual == null }) {
                        TextButton(onClick = onUndoAll) { Text("Desfazer as do analisador") }
                    }
                    AnalyzerTab.Review -> if (shown.isNotEmpty()) {
                        val accepting = state.accepting
                        Button(onClick = onAcceptAll, Modifier.fillMaxWidth().padding(vertical = 4.dp), enabled = accepting == null) {
                            Text(if (accepting != null) "Aceitando ${accepting.first + 1} de ${accepting.second}…"
                                else "Aceitar todas (${shown.size})")
                        }
                        if (accepting != null) LinearProgressIndicator(
                            progress = { (accepting.first + 1f) / accepting.second }, Modifier.fillMaxWidth())
                    }
                    AnalyzerTab.NotFound -> if (shown.isNotEmpty() && !state.running) {
                        TextButton(onClick = onRetryNotFound) { Text("Procurar de novo as não achadas") }
                    }
                    AnalyzerTab.Ignored -> Text("Não são analisadas nem aparecem nas listas. Toque em Voltar para trazer uma de volta.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tab == AnalyzerTab.Review || tab == AnalyzerTab.NotFound) {
                    Text("Nenhuma serve? Procurar busca à mão nas fontes; Ignorar tira a música da lista de vez.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (shown.isEmpty()) Text("Nada aqui.", Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(shown, key = { it.song.id }) { item -> AnalysisRow(item, onAccept, onUndo, onSearch, onIgnore, onUnignore) }
    }
}

@Composable
private fun AnalysisRow(
    item: AnalysisItem,
    onAccept: (AnalysisItem) -> Unit,
    onUndo: (AnalysisItem) -> Unit,
    onSearch: (AnalysisItem) -> Unit,
    onIgnore: (AnalysisItem) -> Unit,
    onUnignore: (AnalysisItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(item.song.artUri, 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(before(item.song), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val manual = item.manual
                if (manual != null) {
                    Text("→ ${manual.artist} — ${manual.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(manual.album.ifBlank { null }, manual.year.takeIf { it > 0 }?.toString(), "corrigida à mão")
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else item.proposal?.let { proposal ->
                    val o = proposal.override
                    Text("→ ${o.artist} — ${o.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(o.album.ifBlank { null }, o.year.takeIf { it > 0 }?.toString(), proposal.source.label,
                        "confiança ${proposal.score}").joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            when (item.tab()) {
                AnalyzerTab.Corrected -> {
                    OutlinedButton(onClick = { onSearch(item) }) { Text("Procurar") }
                    OutlinedButton(onClick = { onUndo(item) }) { Text("Desfazer") }
                }
                AnalyzerTab.Review -> {
                    TextButton(onClick = { onIgnore(item) }) { Text("Ignorar") }
                    OutlinedButton(onClick = { onSearch(item) }) { Text("Procurar") }
                    Button(onClick = { onAccept(item) }) { Text("Aceitar") }
                }
                AnalyzerTab.NotFound -> {
                    TextButton(onClick = { onIgnore(item) }) { Text("Ignorar") }
                    OutlinedButton(onClick = { onSearch(item) }) { Text("Procurar") }
                }
                AnalyzerTab.Ignored -> {
                    OutlinedButton(onClick = { onSearch(item) }) { Text("Procurar") }
                    Button(onClick = { onUnignore(item) }) { Text("Voltar") }
                }
            }
        }
    }
}

private fun before(song: Song) = "${song.artist.ifBlank { "?" }} — ${song.title}" +
    (if (song.album.isNotBlank()) " · ${song.album}" else "")
