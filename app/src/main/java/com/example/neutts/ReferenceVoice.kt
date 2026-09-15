package com.example.neutts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * A bundled/imported reference voice: NeuCodec acoustic codes for a short
 * reference clip, plus the transcript of what is said in that clip.
 * NeuTTS-Air's backbone needs both to clone the voice.
 */
data class ReferenceVoice(val codes: IntArray, val text: String)

/**
 * Reads the int32 code tensor out of a NeuTTS-Air `.pt` reference-voice file.
 *
 * These files are plain `torch.save(tensor)` output: a zip archive containing
 * `<name>/data.pkl` (a tiny pickle wrapping `torch._utils._rebuild_tensor_v2`)
 * and `<name>/data/0` (the raw little-endian int32 storage). We don't need a
 * pickle interpreter — the storage entry alone is the full flat code array,
 * shape (N, 1) in row-major order, i.e. just N sequential int32 codes.
 */
object ReferenceVoiceCodec {
    fun loadCodes(path: String): IntArray {
        ZipFile(File(path)).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith("/data/0") }
                ?: throw IllegalArgumentException("Not a torch tensor .pt file: $path")
            val bytes = zip.getInputStream(entry).readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = bytes.size / 4
            return IntArray(n) { buf.getInt(it * 4) }
        }
    }
}
