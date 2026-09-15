package com.github.kingster.neutts

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A selectable GGUF backbone: download URL + its model-specific special token IDs. */
data class Backbone(
    val id: String,
    val displayName: String,
    val ggufUrl: String,
    val speechGenerationStart: Int,
    val speechGenerationEnd: Int,
    val speechCodeBase: Int,
)

/**
 * End-to-end NeuTTS-Air pipeline: text + reference voice -> 24kHz PCM16.
 *
 *   1. Build the NeuTTS-Air BPE prompt (ref text + ref codes + input text).
 *   2. Run the GGUF backbone (llama.cpp, via JNI) autoregressively to get
 *      generated acoustic codes.
 *   3. Decode those codes to PCM with the NeuCodec ONNX decoder.
 *
 * Shared by MainActivity (manual test synthesis) and NeuTTSService (system
 * TTS integration) so the prompt format and pipeline wiring live in one place.
 */
class NeuTtsEngine private constructor(
    private val codec: NeuCodecDecoder,
    private val phonemizer: EspeakPhonemizer
) : AutoCloseable {

    companion object {
        init { System.loadLibrary("neutts_bridge") }

        private const val TEXT_PROMPT_START = "<|TEXT_PROMPT_START|>"
        private const val TEXT_PROMPT_END = "<|TEXT_PROMPT_END|>"
        private const val SPEECH_GENERATION_START = "<|SPEECH_GENERATION_START|>"

        private const val CODEC_URL =
            "https://storage.googleapis.com/fk-binaries/models/neucodec-onnx-decoder-int8-model.onnx"

        /**
         * Selectable GGUF backbones. Token IDs verified per-model via
         * llama-tokenize. First entry is the default (nano: much faster
         * prefill/generation on-device; air trades speed for quality).
         */
        val BACKBONES = listOf(
            Backbone(
                id = "nano",
                displayName = "NeuTTS-Nano (229M, faster)",
                ggufUrl = "https://storage.googleapis.com/fk-binaries/models/neutts-nano-Q4_0.gguf",
                speechGenerationStart = 128260,
                speechGenerationEnd = 128261,
                speechCodeBase = 128262,
            ),
            Backbone(
                id = "air",
                displayName = "NeuTTS-Air (748M, higher quality)",
                ggufUrl = "https://storage.googleapis.com/fk-binaries/models/neutts-air-Q4_0.gguf",
                speechGenerationStart = 151669,
                speechGenerationEnd = 151670,
                speechCodeBase = 151671,
            ),
        )

        /** Directory where downloaded model files are cached. */
        fun modelsDir(context: Context): File = File(context.filesDir, "models")

        /**
         * Downloads [backbone]'s GGUF and the NeuCodec ONNX decoder into
         * app-private storage if not already present (both native libs need
         * real file paths, not streams). Safe to call repeatedly; skips
         * files that already exist. Runs on the calling thread -- call from
         * a background thread. [onProgress] reports (label, 0f..1f) for
         * whichever file is currently downloading.
         */
        fun ensureModelsDownloaded(
            context: Context,
            backbone: Backbone,
            onProgress: (String, Float) -> Unit = { _, _ -> },
        ): Pair<File, File> {
            val dir = modelsDir(context)
            dir.mkdirs()
            val gguf = File(dir, "${backbone.id}.gguf")
            val onnx = File(dir, "neucodec_decoder.onnx")
            if (!gguf.exists()) downloadFile(backbone.ggufUrl, gguf) { onProgress("backbone", it) }
            if (!onnx.exists()) downloadFile(CODEC_URL, onnx) { onProgress("codec", it) }
            return gguf to onnx
        }

        private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
            val tmp = File(dest.parentFile, "${dest.name}.part")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Download failed (${connection.responseCode}) for $url"
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        if (total > 0) onProgress(readTotal.toFloat() / total)
                    }
                }
            }
            check(tmp.renameTo(dest)) { "Failed to finalize download for ${dest.name}" }
        }

        /** Names of bundled reference voices (asset files "<name>.pt" + "<name>.txt"). */
        val BUNDLED_VOICES = listOf("sayoni", "dave")

        /** Loads a bundled reference voice by name (see [BUNDLED_VOICES]). */
        fun loadBundledReferenceVoice(context: Context, name: String): ReferenceVoice {
            val tmp = File(context.cacheDir, "$name.pt")
            if (!tmp.exists()) {
                context.assets.open("$name.pt").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val codes = ReferenceVoiceCodec.loadCodes(tmp.absolutePath)
            val text = context.assets.open("$name.txt").use { it.readBytes().decodeToString() }.trim()
            return ReferenceVoice(codes, text)
        }

        /** Builds the pipeline. Call [close] when done (e.g. Service#onDestroy). */
        fun create(context: Context, backbone: Backbone, ggufPath: String, onnxPath: String): NeuTtsEngine {
            val result = initNativeEngine(
                ggufPath,
                backbone.speechGenerationStart,
                backbone.speechGenerationEnd,
                backbone.speechCodeBase,
            )
            check(result == 0) { "Failed to load GGUF backbone from $ggufPath" }
            return NeuTtsEngine(NeuCodecDecoder(onnxPath), EspeakPhonemizer.create(context))
        }

        @JvmStatic
        private external fun initNativeEngine(
            modelPath: String,
            speechGenerationStart: Int,
            speechGenerationEnd: Int,
            speechCodeBase: Int,
        ): Int

        @JvmStatic
        private external fun nativeGenerateCodes(prompt: String): IntArray

        @JvmStatic
        private external fun nativeStop()

        @JvmStatic
        private external fun nativeCleanup()
    }

    /** Synthesizes [text] cloning [reference]'s voice. Returns 24kHz PCM16 samples. */
    fun synthesize(text: String, reference: ReferenceVoice): ShortArray {
        val t0 = System.currentTimeMillis()
        val prompt = buildPrompt(text, reference)
        val t1 = System.currentTimeMillis()
        val generatedCodes = nativeGenerateCodes(prompt)
        val t2 = System.currentTimeMillis()
        if (generatedCodes.isEmpty()) return ShortArray(0)
        val pcm = codec.decode(generatedCodes)
        val t3 = System.currentTimeMillis()
        Log.i("NeuTtsEngine", "timing: phonemize=${t1 - t0}ms backbone=${t2 - t1}ms codec=${t3 - t2}ms")
        return pcm
    }

    fun stop() = nativeStop()

    override fun close() {
        nativeCleanup()
        codec.close()
        phonemizer.close()
    }

    /**
     * NeuTTS-Air's exact GGUF prompt template (see neuphonic/neutts-air
     * `NeuTTS._infer_ggml`): a chat-style wrapper, not just the bare special
     * tokens -- the model was fine-tuned on this literal framing and treats
     * anything else as an immediate end-of-speech. Both ref_text and text are
     * phonemized first (espeak-ng, IPA) since the model was trained on
     * phoneme sequences, not raw text.
     *
     * The native side finds and reuses whatever token prefix this shares
     * with the previous call (typically the boilerplate + ref phonemes,
     * which are identical across calls for the same voice) -- see
     * neutts_bridge.cpp's longest-common-prefix KV cache reuse.
     */
    private fun buildPrompt(text: String, reference: ReferenceVoice): String {
        val refCodeTokens = reference.codes.joinToString(separator = "") { "<|speech_$it|>" }
        val refPhonemes = phonemizer.phonemize(reference.text)
        val textPhonemes = phonemizer.phonemize(text)
        return "user: Convert the text to speech:$TEXT_PROMPT_START$refPhonemes $textPhonemes" +
            "$TEXT_PROMPT_END\nassistant:$SPEECH_GENERATION_START$refCodeTokens"
    }
}
