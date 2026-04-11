package com.example.polyglotapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

class OnnxTransformer(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        val opts = OrtSession.SessionOptions()

        val encFile = copyAssetToFile(context, "encoder.onnx")
        val decFile = copyAssetToFile(context, "decoder.onnx")

        encoder = env.createSession(encFile.absolutePath, opts)
        decoder = env.createSession(decFile.absolutePath, opts)
    }

    private fun copyAssetToFile(context: Context, name: String): File {
        val file = File(context.filesDir, name)

        if (!file.exists()) {
            context.assets.open(name).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return file
    }

    fun encode(srcTokens: LongArray, seqLen: Int): FloatArray {
        val tensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(srcTokens),
            longArrayOf(1L, seqLen.toLong())
        )
        return tensor.use {
            encoder.run(mapOf("src" to tensor)).use { out ->
                (out[0] as OnnxTensor).toFloatArray()
            }
        }
    }

    fun decode(
        tgtTokens: LongArray,
        memory: FloatArray,
        srcLen: Int,
        modelDim: Int
    ): FloatArray {
        val tgtLen = tgtTokens.size

        val tgtTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tgtTokens),
            longArrayOf(1L, tgtLen.toLong())
        )
        val memTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(memory),
            longArrayOf(1L, srcLen.toLong(), modelDim.toLong())
        )

        return tgtTensor.use {
            memTensor.use {
                decoder.run(mapOf("tgt" to tgtTensor, "memory" to memTensor)).use { out ->
                    (out[0] as OnnxTensor).toFloatArray()
                }
            }
        }
    }

    fun close() {
        encoder.close()
        decoder.close()
        env.close()
    }

    // Безопасное извлечение — floatBuffer может иметь ненулевой position
    private fun OnnxTensor.toFloatArray(): FloatArray {
        val buf = this.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }
}