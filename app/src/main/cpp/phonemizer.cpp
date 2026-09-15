/**
 * Phonemizer bridge - JNI wrapper around the official espeak-ng library.
 *
 * Converts English text to IPA phonemes before it reaches the NeuTTS-Air
 * backbone, matching how the reference NeuTTS-Air pipeline (Python
 * `phonemizer` + espeak-ng backend) prepares text: the GGUF model was
 * fine-tuned on phoneme sequences, not raw text, so skipping this step
 * produces garbled/unconditioned output.
 */

#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define TAG "Phonemizer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_mu;
static bool g_initialized = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_kingster_neutts_EspeakPhonemizer_nativeInit(JNIEnv* env, jclass, jstring jDataPath) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_initialized) return JNI_TRUE;

    const char* dataPath = env->GetStringUTFChars(jDataPath, nullptr);
    int sampleRate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, dataPath, 0);
    env->ReleaseStringUTFChars(jDataPath, dataPath);

    if (sampleRate == -1) {
        LOGE("espeak_Initialize failed");
        return JNI_FALSE;
    }

    espeak_ERROR voiceResult = espeak_SetVoiceByName("en-us");
    if (voiceResult != EE_OK) {
        LOGE("espeak_SetVoiceByName(en-us) failed: %d", voiceResult);
        return JNI_FALSE;
    }

    g_initialized = true;
    return JNI_TRUE;
}

/**
 * Converts [text] to IPA phonemes (espeak-ng's espeak_TextToPhonemes, mode
 * IPA), matching `phonemizer.EspeakBackend(with_stress=True)` on Python.
 * Returns clauses joined with a space, since NeuTTS-Air's prompt template
 * expects one flat phoneme string per input.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_github_kingster_neutts_EspeakPhonemizer_nativePhonemize(JNIEnv* env, jclass, jstring jText) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_initialized) {
        LOGE("nativePhonemize: not initialized");
        return env->NewStringUTF("");
    }

    const char* textChars = env->GetStringUTFChars(jText, nullptr);
    std::string text(textChars);
    env->ReleaseStringUTFChars(jText, textChars);

    std::string result;
    const void* textPtr = text.c_str();
    constexpr int PHONEME_MODE_IPA = 0x02;
    while (textPtr != nullptr) {
        const char* clause = espeak_TextToPhonemes(&textPtr, espeakCHARS_UTF8, PHONEME_MODE_IPA);
        if (clause == nullptr) break;
        if (!result.empty() && clause[0] != '\0') result += ' ';
        result += clause;
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_kingster_neutts_EspeakPhonemizer_nativeTerminate(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_initialized) {
        espeak_Terminate();
        g_initialized = false;
    }
}
