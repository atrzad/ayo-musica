// Chromaprint fingerprint for AcoustID lookups (see analyzer/AcoustId.kt).
#include <jni.h>
#include <chromaprint.h>

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_atrzad_ayomusica_analyzer_Chromaprint_fingerprint(JNIEnv *env, jclass, jshortArray samples, jint rate) {
    ChromaprintContext *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!ctx) return nullptr;
    jstring out = nullptr;
    if (chromaprint_start(ctx, rate, 1)) {
        const jsize count = env->GetArrayLength(samples);
        jshort *data = env->GetShortArrayElements(samples, nullptr);
        const bool fed = chromaprint_feed(ctx, data, count);
        env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
        char *fingerprint = nullptr;
        if (fed && chromaprint_finish(ctx) && chromaprint_get_fingerprint(ctx, &fingerprint) && fingerprint) {
            out = env->NewStringUTF(fingerprint);
            chromaprint_dealloc(fingerprint);
        }
    }
    chromaprint_free(ctx);
    return out;
}
