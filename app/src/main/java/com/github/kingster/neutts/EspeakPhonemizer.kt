package com.github.kingster.neutts

import android.content.Context
import java.io.File

/**
 * Converts English text to IPA phonemes via the vendored espeak-ng library
 * (app/src/main/cpp/espeak-ng, official espeak-ng/espeak-ng source), matching
 * how the reference NeuTTS-Air pipeline phonemizes text before it reaches the
 * GGUF backbone (see Python `phonemizer.EspeakBackend`). Without this step
 * the model receives raw English text, which is out-of-distribution for a
 * model trained exclusively on phoneme sequences.
 */
class EspeakPhonemizer private constructor() : AutoCloseable {

    companion object {
        init { System.loadLibrary("phonemizer") }

        private const val ASSET_DIR = "espeak-ng-data"

        /** Extracts the bundled espeak-ng-data (English only) if needed and initializes espeak-ng. */
        fun create(context: Context): EspeakPhonemizer {
            val dataDir = File(context.filesDir, ASSET_DIR)
            if (!dataDir.exists()) {
                copyAssetDir(context, ASSET_DIR, dataDir)
            }
            check(nativeInit(dataDir.absolutePath)) { "espeak-ng initialization failed" }
            return EspeakPhonemizer()
        }

        private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
            val entries = context.assets.list(assetPath) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val childAssetPath = "$assetPath/$entry"
                val childDest = File(destDir, entry)
                val subEntries = context.assets.list(childAssetPath)
                if (!subEntries.isNullOrEmpty()) {
                    copyAssetDir(context, childAssetPath, childDest)
                } else {
                    context.assets.open(childAssetPath).use { input ->
                        childDest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        @JvmStatic
        private external fun nativeInit(dataPath: String): Boolean

        @JvmStatic
        private external fun nativePhonemize(text: String): String

        @JvmStatic
        private external fun nativeTerminate()
    }

    /** Converts [text] to a single IPA phoneme string (clauses joined with spaces). */
    fun phonemize(text: String): String = nativePhonemize(text)

    override fun close() = nativeTerminate()
}
