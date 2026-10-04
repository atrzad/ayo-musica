package io.github.atrzad.ayomusica.analyzer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the analyzer concluded about one song. */
@Serializable
data class SavedResult(val verdict: Verdict, val proposal: Proposal? = null)

@Serializable
data class AnalysisData(
    /** Every song already looked up, in the order it was analyzed: the next run goes on from where this one stopped. */
    val results: Map<Long, SavedResult> = emptyMap(),
    /** Songs the person chose to ignore: never analyzed or listed again (until they bring them back). */
    val ignored: Set<Long> = emptySet(),
)

/** The analyzer's memory, in a JSON file in the app's storage; written in the background, a moment after changes. */
class AnalysisStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "analysis.json"))

    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(read())
    val data: StateFlow<AnalysisData> = state
    private val writer = Executors.newSingleThreadExecutor()
    private val pending = AtomicBoolean(false)

    private fun read(): AnalysisData = runCatching {
        if (file.exists()) json.decodeFromString<AnalysisData>(file.readText()) else AnalysisData()
    }.getOrDefault(AnalysisData())

    fun put(id: Long, result: SavedResult) = change { it.copy(results = it.results + (id to result)) }

    fun ignore(id: Long) = change { it.copy(ignored = it.ignored + id) }

    fun unignore(id: Long) = change { it.copy(ignored = it.ignored - id) }

    /** Forgets the results with this verdict, so the next run looks those songs up again. */
    fun forget(verdict: Verdict) = change { data -> data.copy(results = data.results.filterValues { it.verdict != verdict }) }

    private fun change(edit: (AnalysisData) -> AnalysisData) {
        state.update(edit)
        // Many changes in a row (an analysis run) become one write.
        if (pending.compareAndSet(false, true)) writer.execute {
            Thread.sleep(1500)
            pending.set(false)
            write()
        }
    }

    /** Writes now (when an analysis stops). */
    fun flush() = writer.execute { write() }

    @Synchronized private fun write() {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(state.value))
        temp.renameTo(file)
    }
}
