// Bridge between Kotlin (voice/Whisper.kt) and whisper.cpp: load a model, hear 16 kHz mono audio and
// return every token with its start and end time, as "t0\tt1\ttext" lines (times in milliseconds).
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_cancel{false};
static JavaVM *g_vm = nullptr;
static jobject g_listener = nullptr;
static jmethodID g_progress = nullptr;

static bool abort_cb(void *) { return g_cancel.load(); }

static void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *) {
    if (!g_vm || !g_listener || !g_progress) return;
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    }
    env->CallVoidMethod(g_listener, g_progress, progress);
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
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

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_atrzad_ayomusica_voice_Whisper_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray audio,
                                                         jstring language, jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    g_cancel = false;
    g_listener = listener ? env->NewGlobalRef(listener) : nullptr;
    g_progress = listener ? env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(I)V") : nullptr;

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

    jsize count = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    int result = whisper_full(ctx, params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (g_listener) env->DeleteGlobalRef(g_listener);
    g_listener = nullptr;
    if (result != 0) return nullptr;

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int s = 0; s < segments; ++s) {
        const int tokens = whisper_full_n_tokens(ctx, s);
        for (int t = 0; t < tokens; ++t) {
            whisper_token_data data = whisper_full_get_token_data(ctx, s, t);
            const char *text = whisper_full_get_token_text(ctx, s, t);
            if (!text || data.id >= whisper_token_eot(ctx)) continue;  // special tokens
            out += std::to_string(data.t0 * 10) + "\t" + std::to_string(data.t1 * 10) + "\t" + text + "\n";
        }
    }
    return env->NewStringUTF(out.c_str());
}
