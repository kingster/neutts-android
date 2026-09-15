package com.github.kingster.neutts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.IntBuffer

/**
 * Wraps the NeuCodec ONNX decoder: acoustic code indices -> 24kHz PCM.
 * Model contract (neuphonic/neucodec-onnx-decoder-int8):
 *   input  "codes": int32 [1, 1, N]
 *   output "audio": float32 [1, 1, 480*N] (480 samples/code, 24kHz)
 */
class NeuCodecDecoder(modelPath: String) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())

    /** Decodes acoustic codes into 16-bit PCM samples. */
    fun decode(codes: IntArray): ShortArray {
        if (codes.isEmpty()) return ShortArray(0)

        val shape = longArrayOf(1, 1, codes.size.toLong())
        OnnxTensor.createTensor(env, IntBuffer.wrap(codes), shape).use { inputTensor ->
            session.run(mapOf("codes" to inputTensor)).use { result ->
                val audio = result[0].value as Array<Array<FloatArray>>
                val samples = audio[0][0]
                return ShortArray(samples.size) { i ->
                    val clamped = samples[i].coerceIn(-1.0f, 1.0f)
                    (clamped * Short.MAX_VALUE).toInt().toShort()
                }
            }
        }
    }

    override fun close() {
        session.close()
    }
}
