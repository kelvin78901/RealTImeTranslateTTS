#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define LOG_TAG "EspeakJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::once_flag g_once;
    std::mutex g_mu;
    bool g_inited = false;
    bool g_terminated = false;
    std::string g_voice;  // 留空，等待上层显式设置（严格按 config）
    std::string g_data_dir;

    void ensure_init() {
        std::call_once(g_once, []() {
            if (g_data_dir.empty()) {
                LOGE("❌ data_dir is empty, cannot initialize espeak");
                g_inited = false;
                return;
            }

            LOGI("Initializing espeak with data_dir: %s", g_data_dir.c_str());
            const char* data_path = g_data_dir.c_str();

            int sr = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_path, 0);
            if (sr <= 0) {
                LOGE("❌ espeak_Initialize failed with code %d (path=%s)", sr, data_path);
                g_inited = false;
                return;
            }
            LOGI("espeak_Initialize succeeded, sample_rate=%d", sr);

            // 不在此默认设置 voice，等待上层通过 nativeSetVoice 严格设定
            g_inited = true;
            g_terminated = false;
            LOGI("✅ eSpeak initialized successfully");
        });
    }
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication1_tts_Phonemizer_nativeInit(JNIEnv* env, jobject /*thiz*/, jstring jDataPath) {
    LOGI("nativeInit called");

    if (!jDataPath) {
        LOGE("❌ jDataPath is null!");
        return;
    }

    const char* cpath = env->GetStringUTFChars(jDataPath, nullptr);
    if (!cpath) {
        LOGE("❌ Failed to get UTF chars from jDataPath");
        return;
    }

    g_data_dir = cpath;
    LOGI("Received data_dir: %s", g_data_dir.c_str());
    env->ReleaseStringUTFChars(jDataPath, cpath);

    try {
        ensure_init();
        if (g_inited) {
            LOGI("✅ nativeInit completed successfully");
        } else {
            LOGE("❌ nativeInit failed - ensure_init returned false");
        }
    } catch (const std::exception& e) {
        LOGE("❌ Exception in nativeInit: %s", e.what());
    } catch (...) {
        LOGE("❌ Unknown exception in nativeInit");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication1_tts_Phonemizer_nativeSetVoice(JNIEnv* env, jobject /*thiz*/, jstring jname) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    if (!name) return JNI_FALSE;
    ensure_init();
    if (!g_inited) {
        env->ReleaseStringUTFChars(jname, name);
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lk(g_mu);
    espeak_ERROR err = espeak_SetVoiceByName(name);
    if (err != EE_OK) {
        LOGE("setVoice(%s) failed: %d", name, err);
        env->ReleaseStringUTFChars(jname, name);
        return JNI_FALSE;
    }
    g_voice = name;
    LOGI("setVoice -> %s", g_voice.c_str());
    env->ReleaseStringUTFChars(jname, name);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication1_tts_Phonemizer_ipaFromText(
        JNIEnv* env, jobject /*thiz*/, jstring jtext) {
    const char* cText = env->GetStringUTFChars(jtext, nullptr);
    if (!cText) return env->NewStringUTF("");

    ensure_init();
    if (!g_inited) {
        LOGE("❌ eSpeak not initialized");
        env->ReleaseStringUTFChars(jtext, cText);
        return env->NewStringUTF("");
    }

    // 严格使用当前 voice（由上层 setVoice 设定），不再自动切换
    std::lock_guard<std::mutex> lk(g_mu);

    // 使用 IPA + 空格分隔
    int phoneme_mode = espeakPHONEMES_IPA | (' ' << 8);
    const void* ptr = cText;
    const char* ipa = espeak_TextToPhonemes(&ptr, espeakCHARS_UTF8, phoneme_mode);

    jstring out = env->NewStringUTF(ipa ? ipa : "");
    env->ReleaseStringUTFChars(jtext, cText);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication1_tts_Phonemizer_nativeTerminate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_inited && !g_terminated) {
        espeak_Terminate();
        g_terminated = true;
        g_inited = false;
        g_voice.clear();
        LOGI("eSpeak terminated");
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_myapplication1_tts_Phonemizer_nativeListVoices(JNIEnv* env, jobject /*thiz*/) {
    ensure_init();
    if (!g_inited) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    std::lock_guard<std::mutex> lk(g_mu);

    const espeak_VOICE** list = espeak_ListVoices(nullptr);
    if (!list) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    // 先计数
    int count = 0;
    for (const espeak_VOICE** p = list; *p != nullptr; ++p) {
        ++count;
    }
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(count, strCls, nullptr);

    // name 优先；若无则用 identifier
    int i = 0;
    for (const espeak_VOICE** p = list; *p != nullptr; ++p) {
        const espeak_VOICE* v = *p;
        const char* name = v && v->name ? v->name : (v && v->identifier ? v->identifier : "");
        jstring jname = env->NewStringUTF(name);
        env->SetObjectArrayElement(arr, i++, jname);
        env->DeleteLocalRef(jname);
    }
    return arr;
}

jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad (espeak-jni) OK & registered");
    return JNI_VERSION_1_6;
}
