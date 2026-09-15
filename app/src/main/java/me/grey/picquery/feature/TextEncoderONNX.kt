// FILE: app/src/main/java/me/grey/picquery/feature/TextEncoderONNX.kt
package me.grey.picquery.feature

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import me.grey.picquery.common.AssetUtil
import me.grey.picquery.feature.base.TextEncoder
import java.nio.IntBuffer
import java.nio.LongBuffer

abstract class TextEncoderONNX(private val context: Context) : TextEncoder {
    private val TAG = this.javaClass.simpleName
    abstract val modelPath: String
    abstract val modelType: Int

    // Shared, process-wide ORT environment. Not closed per call.
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile
    private var ortSession: OrtSession? = null

    private var tokenizer: BPETokenizer? = null

    init {
        Log.d(TAG, "Init $TAG")
    }

    private fun ensureSession(): OrtSession {
        ortSession?.let { return it }
        synchronized(this) {
            ortSession?.let { return it }
            val options = OrtSession.SessionOptions().apply {
                addConfigEntry("session.load_model_format", "ORT")
            }
            val created = try {
                ortEnv.createSession(AssetUtil.assetFilePath(context, modelPath), options)
            } finally {
                options.close()
            }
            ortSession = created
            return created
        }
    }

    override fun encode(input: String): FloatArray {
        val tokenizer = tokenizer ?: BPETokenizer(context).also { tokenizer = it }
        val token = tokenizer.tokenize(input)
        val intBuffer = IntBuffer.wrap(token.first)
        val shape = token.second

        val session = ensureSession()
        val inputName = session.inputNames.iterator().next()

        val tensor = when (modelType) {
            0 -> OnnxTensor.createTensor(ortEnv, intBuffer, shape)
            1 -> {
                val longBuffer = LongBuffer.allocate(intBuffer.capacity()).apply {
                    while (intBuffer.hasRemaining()) {
                        put(intBuffer.get().toLong())
                    }
                    flip()
                }
                OnnxTensor.createTensor(ortEnv, longBuffer, shape)
            }
            else -> throw IllegalArgumentException("Unknown buffer type")
        }

        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { output ->
                val resultBuffer = output.get(0) as OnnxTensor
                return resultBuffer.floatBuffer.array()
            }
        }
    }
}