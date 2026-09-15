package com.example.neutts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log

/**
 * NeuTTS Engine Service
 *
 * Implements the Android TextToSpeechService interface so that the system
 * and third-party apps (Google Maps, etc.) can route speech through the
 * on-device NeuTTS-Air pipeline (see [NeuTtsEngine]).
 */
class NeuTTSService : TextToSpeechService() {

    companion object {
        private const val TAG = "NeuTTSService"
    }

    private var engine: NeuTtsEngine? = null
    private var referenceVoice: ReferenceVoice? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NeuTTSService created")

        // Model download can be slow/offline-unavailable; do it off the
        // service's main thread so onCreate() returns promptly. Synthesis
        // requests arriving before this completes are rejected (engine null).
        Thread {
            try {
                val prefs = getSharedPreferences("neutts_config", MODE_PRIVATE)
                val voiceName = prefs.getString("voice_name", NeuTtsEngine.BUNDLED_VOICES.first())
                    ?: NeuTtsEngine.BUNDLED_VOICES.first()
                val backboneId = prefs.getString("backbone_id", NeuTtsEngine.BACKBONES.first().id)
                val backbone = NeuTtsEngine.BACKBONES.firstOrNull { it.id == backboneId }
                    ?: NeuTtsEngine.BACKBONES.first()

                val (gguf, onnx) = NeuTtsEngine.ensureModelsDownloaded(this, backbone)
                engine = NeuTtsEngine.create(this, backbone, gguf.absolutePath, onnx.absolutePath)
                referenceVoice = NeuTtsEngine.loadBundledReferenceVoice(this, voiceName)
                Log.i(TAG, "NeuTTS engine ready (voice=$voiceName, backbone=${backbone.id})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NeuTTS engine: ${e.message}", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NeuTTSService destroyed")
        engine?.close()
        engine = null
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang == "eng") TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        Log.d(TAG, "onStop called")
        engine?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val text = request.charSequenceText?.toString()
        if (text.isNullOrEmpty()) {
            callback.done()
            return
        }

        val activeEngine = engine
        val reference = referenceVoice
        if (activeEngine == null || reference == null) {
            Log.e(TAG, "NeuTTS engine not initialized")
            callback.done()
            return
        }

        callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
        try {
            val pcm = activeEngine.synthesize(text, reference)
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
            }
            // SynthesisCallback caps chunk size; stream in pieces.
            val maxChunk = callback.maxBufferSize
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(maxChunk, bytes.size - offset)
                callback.audioAvailable(bytes, offset, len)
                offset += len
            }
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed: ${e.message}", e)
        } finally {
            callback.done()
        }
    }
}
