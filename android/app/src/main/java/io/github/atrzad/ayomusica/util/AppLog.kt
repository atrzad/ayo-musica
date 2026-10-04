package io.github.atrzad.ayomusica.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's diary, for the error report the person can send (Configurações → Relatório de erros): what it was doing,
 * the errors, crashes with their stack, and — on the next start — why Android closed the app (native crash, out of
 * memory, not responding). Kept in the app's storage, about 1 MB at most; nothing leaves the phone unless sent.
 */
object AppLog {
    private const val TAG = "AyoMusica"
    private const val MAX_BYTES = 512 * 1024
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ayo-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var folder: File? = null
    @Volatile private var app: Context? = null
    /** Set at start when Android says the app closed abnormally last time (shown as a hint to send the report). */
    val lastExitProblem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun init(context: Context) {
        if (folder != null) return
        synchronized(this) {
            if (folder != null) return
            app = context.applicationContext
            folder = File(context.filesDir, "logs").apply { mkdirs() }
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Written right away (the process is about to die), not through the background writer.
                runCatching { append("CRASH na thread ${thread.name}\n${stack(error)}", now = true) }
                previous?.uncaughtException(thread, error)
            }
        }
        i("App", "início: ${device(context)}")
        recordExits(context)
    }

    fun i(tag: String, message: String) {
        Log.i(TAG, "$tag: $message")
        append("I $tag: $message")
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "$tag: $message", error)
        append("W $tag: $message" + (error?.let { "\n" + stack(it) } ?: ""))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "$tag: $message", error)
        append("E $tag: $message" + (error?.let { "\n" + stack(it) } ?: ""))
    }

    private fun stack(error: Throwable) = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().take(6000)

    private fun file() = folder?.let { File(it, "app.log") }

    private fun append(line: String, now: Boolean = false) {
        val target = file() ?: return
        val text = "${synchronized(time) { time.format(Date()) }} $line\n"
        val work = {
            if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, "app.1.log"))
            target.appendText(text)
        }
        if (now) work() else writer.execute { runCatching(work) }
    }

    /** Why Android ended the app's previous runs (Android 11+), logged once each. */
    private fun recordExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        writer.execute {
            runCatching {
                val manager = context.getSystemService(ActivityManager::class.java)
                val seenFile = File(folder, "exits.seen")
                val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
                val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                    .filter { it.timestamp > seen }.sortedBy { it.timestamp }
                for (exit in exits) {
                    val line = "fim anterior: ${reason(exit.reason)} em ${time.format(Date(exit.timestamp))}" +
                        " · ${exit.description.orEmpty()} · memória ${exit.pss / 1024} MB (pss), ${exit.rss / 1024} MB (rss)" +
                        " · processo ${exit.processName}"
                    append(if (exit.reason in PROBLEMS) "E Saída: $line" else "I Saída: $line", now = true)
                    if (exit.reason in PROBLEMS && exit.processName == context.packageName) lastExitProblem.value = reason(exit.reason)
                    // ANRs come with the threads' stacks: the first part says where it was stuck.
                    if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                        exit.traceInputStream?.bufferedReader()?.use { reader ->
                            append("Rastros do travamento:\n" + reader.lineSequence().take(150).joinToString("\n"), now = true)
                        }
                    }
                }
                exits.maxOfOrNull { it.timestamp }?.let { seenFile.writeText(it.toString()) }
            }.onFailure { Log.w(TAG, "exit reasons", it) }
        }
    }

    private val PROBLEMS = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    private fun reason(code: Int) = when (code) {
        ApplicationExitInfo.REASON_CRASH -> "erro no app (Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "erro no código nativo (voz/impressão digital)"
        ApplicationExitInfo.REASON_ANR -> "app parou de responder"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android fechou por falta de memória"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excessivo de recursos"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falha ao abrir"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "fechado pela pessoa"
        ApplicationExitInfo.REASON_USER_STOPPED -> "parado nas configurações"
        ApplicationExitInfo.REASON_EXIT_SELF -> "saiu normalmente"
        ApplicationExitInfo.REASON_SIGNALED -> "encerrado pelo sistema"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permissão mudou"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependência encerrada"
        ApplicationExitInfo.REASON_OTHER -> "outro motivo"
        else -> "motivo $code"
    }

    fun device(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return "Ayo Música ${info?.versionName} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} · RAM ${memory.totalMem / 1_048_576} MB, " +
            "livre ${memory.availMem / 1_048_576} MB${if (memory.lowMemory) " (pouca)" else ""}"
    }

    /** Memory free right now, for the logs of heavy work (voice). */
    fun memory(): String {
        val context = app ?: return ""
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        return "RAM livre ${memory.availMem / 1_048_576} MB, Java ${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}/" +
            "${runtime.maxMemory() / 1_048_576} MB"
    }

    /**
     * The report to send: device, processor features, the diary and the app's own system log (which also holds the
     * native crash lines). Written to a file the share sheet can attach.
     */
    fun report(context: Context): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val out = File(dir, "ayo-musica-relatorio-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
        val cpu = runCatching {
            // ARM lists "Features" per core (the voice needs asimddp and asimdhp on every one); x86 calls them "flags".
            File("/proc/cpuinfo").readLines().filter {
                it.startsWith("Features") || it.startsWith("Hardware") || it.startsWith("CPU part") ||
                    it.startsWith("flags") || it.startsWith("model name")
            }.distinct().joinToString("\n")
        }.getOrDefault("")
        val diary = listOf("app.1.log", "app.log").mapNotNull { name ->
            File(folder ?: File(context.filesDir, "logs"), name).takeIf { it.exists() }?.readText()
        }.joinToString("")
        val system = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500", "-b", "main,crash")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrDefault("(não deu para ler o log do sistema)")
        out.writeText(buildString {
            appendLine("Relatório de erros do Ayo Música")
            appendLine(device(context))
            appendLine()
            appendLine("== Processador ==")
            appendLine(cpu)
            appendLine()
            appendLine("== Diário do app ==")
            appendLine(diary.takeLast(400_000))
            appendLine()
            appendLine("== Log do sistema (só deste app) ==")
            appendLine(system.takeLast(400_000))
        })
        return out
    }

    fun clear() {
        writer.execute { folder?.listFiles()?.filter { it.name.endsWith(".log") }?.forEach { it.delete() } }
    }
}
