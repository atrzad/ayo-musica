// Bridge between Kotlin (voice/Whisper.kt) and whisper.cpp: load a model, hear 16 kHz mono audio and
// return every token with its start and end time, as "t0\tt1\ttext" lines (times in milliseconds).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_cancel{false};
static JavaVM *g_vm = nullptr;
// One transcription at a time: two models at once would not fit in a phone's memory.
static std::mutex g_busy;

static bool abort_cb(void *) { return g_cancel.load(); }

// What one call needs in the progress callback (no globals shared between calls).
struct Call {
    jobject listener = nullptr;  // global reference, valid on any thread
    jmethodID method = nullptr;
};

static void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *data) {
    auto *call = static_cast<Call *>(data);
    if (!g_vm || !call || !call->listener || !call->method) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(call->listener, call->method, progress);
    if (env->ExceptionCheck()) env->ExceptionClear();  // a failing listener must not break the native side
    if (attached) g_vm->DetachCurrentThread();  // a thread left attached makes Android abort the app when it ends
}

static void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "whisper", "%s", text);
}

// Length of the part of `text` that ends on a whole UTF-8 character (whisper may split one between tokens).
static size_t whole_chars(const std::string &text) {
    size_t n = text.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const auto byte = static_cast<unsigned char>(text[n - back]);
        if ((byte & 0xC0) == 0x80) continue;  // continuation byte: keep looking for the lead
        const size_t length = byte >= 0xF0 ? 4 : byte >= 0xE0 ? 3 : byte >= 0xC0 ? 2 : 1;
        return length > back ? n - back : n;
    }
    return n;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    whisper_log_set(log_cb, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_atrzad_ayomusica_voice_Whisper_load(JNIEnv *env, jclass, jstring path) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrzad_ayomusica_voice_Whisper_free(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrzad_ayomusica_voice_Whisper_cancel(JNIEnv *, jclass) { g_cancel = true; }

// Returns the tokens as UTF-8 bytes ("t0\tt1\ttext\n" each, times in ms), not a Java string: whisper's text is not
// always valid "modified UTF-8" (emoji, odd bytes), and NewStringUTF aborts the app on that.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_atrzad_ayomusica_voice_Whisper_transcribeBytes(JNIEnv *env, jclass, jlong handle, jfloatArray audio,
                                                         jstring language, jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return nullptr;
    std::lock_guard<std::mutex> lock(g_busy);
    g_cancel = false;
    Call call;
    if (listener) {
        call.listener = env->NewGlobalRef(listener);
        call.method = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(I)V");
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = lang;
    params.n_threads = threads;
    params.token_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;
    params.suppress_blank = true;
    params.abort_callback = abort_cb;
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = &call;

    const jsize count = env->GetArrayLength(audio);
    __android_log_print(ANDROID_LOG_INFO, "AyoWhisper", "transcribe %d samples (%d s), %d threads, language %s",
                        count, count / 16000, threads, lang);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const int result = whisper_full(ctx, params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (call.listener) env->DeleteGlobalRef(call.listener);
    __android_log_print(ANDROID_LOG_INFO, "AyoWhisper", "transcribe finished: %d", result);
    if (result != 0) return nullptr;

    std::string out;
    std::string pending;  // the start of a character split between tokens
    const int segments = whisper_full_n_segments(ctx);
    for (int s = 0; s < segments; ++s) {
        const int tokens = whisper_full_n_tokens(ctx, s);
        for (int t = 0; t < tokens; ++t) {
            whisper_token_data data = whisper_full_get_token_data(ctx, s, t);
            const char *text = whisper_full_get_token_text(ctx, s, t);
            if (!text || data.id >= whisper_token_eot(ctx)) continue;  // special tokens
            std::string piece = pending + text;
            const size_t whole = whole_chars(piece);
            pending = piece.substr(whole);
            piece.resize(whole);
            if (piece.empty()) continue;
            for (char &c : piece) if (c == '\n' || c == '\t') c = ' ';
            out += std::to_string(data.t0 * 10) + "\t" + std::to_string(data.t1 * 10) + "\t" + piece + "\n";
        }
    }
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

// The language whisper settled on in the last transcription ("pt", "en"...), useful after one with "auto".
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_atrzad_ayomusica_voice_Whisper_language(JNIEnv *env, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("auto");
    const int id = whisper_full_lang_id(ctx);
    const char *name = id >= 0 ? whisper_lang_str(id) : nullptr;
    return env->NewStringUTF(name ? name : "auto");
}
