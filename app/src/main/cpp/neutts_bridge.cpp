/**
 * NeuTTS Bridge - Native C++ implementation
 *
 * JNI bridge between Kotlin and the NeuTTS-Air GGUF backbone (llama.cpp).
 * Responsible only for the text+reference-codes -> acoustic-codes step.
 * PCM synthesis (NeuCodec decode) happens in Kotlin via ONNX Runtime.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstring>
#include <thread>
#include <chrono>
#include <algorithm>
#include <android/log.h>
#include <cstdint>

#define TAG "NeuTTSBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "llama.h"

// <|speech_N|> vocab size is fixed by NeuTTS-Air's NeuCodec codebook (0..65535)
// across all known backbones (air, nano); only the base token ID and the
// generation start/end token IDs vary per model vocab.
static constexpr int32_t SPEECH_CODE_VOCAB_SIZE = 65536;
static constexpr int32_t MAX_NEW_TOKENS         = 2048;

struct NeuTTSContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> shouldStop{false};
    std::atomic<bool> isRunning{false};
    std::mutex mu;

    // Special token IDs -- differ per backbone (each GGUF has its own vocab),
    // set by initNativeEngine and verified via llama-tokenize for each model.
    llama_token speechGenerationStart = -1;
    llama_token speechGenerationEnd = -1;
    llama_token speechCodeBase = -1;

    // Full token sequence of the previously-decoded prompt. KV state at
    // position i depends only on tokens [0..i], so if the new prompt shares
    // a token prefix with this one (typically the boilerplate + reference-
    // voice phonemes, identical across calls for the same voice), that
    // prefix's cache entries are still valid and can be reused as-is.
    std::vector<llama_token> cachedPromptTokens;
};

static NeuTTSContext g_ctx;

extern "C" jint
Java_com_example_neutts_NeuTtsEngine_initNativeEngine(
    JNIEnv* env, jobject /* this */, jstring jModelPath,
    jint speechGenerationStart, jint speechGenerationEnd, jint speechCodeBase) {

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("initNativeEngine: model=%s", modelPath);

    std::lock_guard<std::mutex> lock(g_ctx.mu);

    if (g_ctx.ctx) { llama_free(g_ctx.ctx); g_ctx.ctx = nullptr; }
    if (g_ctx.model) { llama_model_free(g_ctx.model); g_ctx.model = nullptr; }
    g_ctx.cachedPromptTokens.clear();
    g_ctx.speechGenerationStart = speechGenerationStart;
    g_ctx.speechGenerationEnd = speechGenerationEnd;
    g_ctx.speechCodeBase = speechCodeBase;

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_ctx.model = llama_model_load_from_file(modelPath, model_params);
    if (!g_ctx.model) {
        LOGE("Failed to load model from %s", modelPath);
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        return -1;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096;
    // Must cover the whole prompt in one llama_decode call (ref-voice codes +
    // text can easily exceed the default 512-token batch); llama_decode()
    // hard-asserts n_tokens_all <= n_batch instead of auto-splitting.
    ctx_params.n_batch = 4096;
    const int32_t n_threads = std::max(1u, std::thread::hardware_concurrency());
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    g_ctx.ctx = llama_init_from_model(g_ctx.model, ctx_params);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!g_ctx.ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(g_ctx.model);
        g_ctx.model = nullptr;
        return -1;
    }

    LOGI("NeuTTS backbone loaded successfully");
    return 0;
}

/**
 * Run the backbone autoregressively over `prompt` and return the generated
 * <|speech_N|> code indices (already offset back to 0..65535) as an int array.
 * Generation stops at <|SPEECH_GENERATION_END|>, EOG, or MAX_NEW_TOKENS.
 */
static std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text) {
    int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), tokens.data(), n, true, true);
    return tokens;
}

extern "C" jintArray
Java_com_example_neutts_NeuTtsEngine_nativeGenerateCodes(
    JNIEnv* env, jobject /* this */, jstring jPrompt) {

    std::lock_guard<std::mutex> lock(g_ctx.mu);

    if (!g_ctx.ctx || !g_ctx.model) {
        LOGE("nativeGenerateCodes: engine not initialized");
        return env->NewIntArray(0);
    }

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    g_ctx.shouldStop = false;
    g_ctx.isRunning = true;

    const llama_vocab* vocab = llama_model_get_vocab(g_ctx.model);

    std::vector<llama_token> promptTokens = tokenize(vocab, prompt);

    // Reuse the KV cache for however many leading tokens this prompt shares
    // with the previously-decoded one (typically the boilerplate + reference
    // -voice phonemes, identical across calls for the same voice). KV state
    // at position i depends only on tokens [0..i], so any genuine token-for-
    // token prefix match is safe to reuse regardless of length.
    size_t reuseLen = 0;
    const size_t maxShared = std::min(g_ctx.cachedPromptTokens.size(), promptTokens.size());
    while (reuseLen < maxShared && g_ctx.cachedPromptTokens[reuseLen] == promptTokens[reuseLen]) {
        reuseLen++;
    }
    // Must decode at least one new token so position bookkeeping stays sane
    // (also handles the degenerate case of an identical prompt repeated).
    if (reuseLen >= promptTokens.size()) reuseLen = promptTokens.size() - 1;

    llama_memory_t mem = llama_get_memory(g_ctx.ctx);
    llama_memory_seq_rm(mem, /*seq_id=*/0, /*p0=*/(llama_pos)reuseLen, /*p1=*/-1);
    g_ctx.cachedPromptTokens = promptTokens;

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::vector<int32_t> speechCodes;

    llama_token* decodeStart = promptTokens.data() + reuseLen;
    const int32_t decodeLen = (int32_t)(promptTokens.size() - reuseLen);
    llama_batch batch = llama_batch_get_one(decodeStart, decodeLen);
    llama_token newToken = -1;

    auto t0 = std::chrono::steady_clock::now();
    long long prefillMs = 0;

    for (int step = 0; step < MAX_NEW_TOKENS && !g_ctx.shouldStop; step++) {
        if (llama_decode(g_ctx.ctx, batch) != 0) {
            LOGE("llama_decode failed at step %d", step);
            break;
        }

        if (step == 0) {
            auto t1 = std::chrono::steady_clock::now();
            prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        }

        newToken = llama_sampler_sample(sampler, g_ctx.ctx, -1);
        llama_sampler_accept(sampler, newToken);

        if (newToken == g_ctx.speechGenerationEnd || llama_vocab_is_eog(vocab, newToken)) {
            break;
        }

        if (newToken >= g_ctx.speechCodeBase && newToken < g_ctx.speechCodeBase + SPEECH_CODE_VOCAB_SIZE) {
            speechCodes.push_back(newToken - g_ctx.speechCodeBase);
        }
        // Non-speech tokens (e.g. speechGenerationStart echoed back) are ignored.

        batch = llama_batch_get_one(&newToken, 1);
    }

    auto t2 = std::chrono::steady_clock::now();
    long long totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t0).count();

    llama_sampler_free(sampler);
    g_ctx.isRunning = false;

    LOGI("nativeGenerateCodes: produced %zu speech codes; n_prompt=%zu reused=%zu decoded=%d prefill=%lldms total=%lldms gen=%lldms",
         speechCodes.size(), promptTokens.size(), reuseLen, decodeLen, prefillMs, totalMs, totalMs - prefillMs);

    jintArray result = env->NewIntArray((jsize)speechCodes.size());
    if (!speechCodes.empty()) {
        env->SetIntArrayRegion(result, 0, (jsize)speechCodes.size(), speechCodes.data());
    }
    return result;
}

extern "C" void
Java_com_example_neutts_NeuTtsEngine_nativeStop(JNIEnv* /* env */, jobject /* this */) {
    g_ctx.shouldStop = true;
}

extern "C" void
Java_com_example_neutts_NeuTtsEngine_nativeCleanup(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_ctx.mu);
    g_ctx.shouldStop = true;
    if (g_ctx.ctx) { llama_free(g_ctx.ctx); g_ctx.ctx = nullptr; }
    if (g_ctx.model) { llama_model_free(g_ctx.model); g_ctx.model = nullptr; }
    g_ctx.cachedPromptTokens.clear();
    llama_backend_free();
}

static void ggmlLogToAndroid(ggml_log_level level, const char* text, void* /* user_data */) {
    int prio = (level == GGML_LOG_LEVEL_ERROR) ? ANDROID_LOG_ERROR
             : (level == GGML_LOG_LEVEL_WARN)  ? ANDROID_LOG_WARN
                                                : ANDROID_LOG_INFO;
    __android_log_print(prio, TAG, "%s", text);
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    llama_log_set(ggmlLogToAndroid, nullptr);
    LOGI("NeuTTS bridge JNI loaded");
    return JNI_VERSION_1_6;
}
