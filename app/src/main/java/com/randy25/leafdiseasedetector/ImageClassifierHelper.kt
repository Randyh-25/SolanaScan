package com.randy25.leafdiseasedetector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ClassificationResult(
    val label: String,
    val confidence: Float,
    val latencyMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

class ImageClassifierHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val modelName = "shufflenetv2_int8.tflite"
    private val labelsFileName = "labels.txt"

    init {
        initClassifier()
    }

    private fun initClassifier() {
        try {
            val assetManager = context.assets
            val modelBuffer = loadModelFile(assetManager, modelName)
            
            val options = Interpreter.Options()
            options.setNumThreads(4)
            
            interpreter = Interpreter(modelBuffer, options)
            labels = assetManager.open(labelsFileName).bufferedReader().readLines()
            Log.d("ImageClassifier", "Model loaded successfully. Classes: ${labels.size}")
        } catch (e: Exception) {
            Log.e("ImageClassifier", "Error loading model: ${e.message}")
        }
    }

    private fun loadModelFile(assetManager: android.content.res.AssetManager, filename: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    suspend fun classifyRealtime(bitmap: Bitmap): ClassificationResult? {
        return withContext(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()
                
                // 1. Resize
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
                
                // 2. Load ke TensorImage
                val tensorImage = TensorImage.fromBitmap(resizedBitmap)

                // 3. Prepare Output Buffer
                val currentInterpreter = interpreter ?: return@withContext null
                val outputBuffer = TensorBuffer.createFixedSize(
                    intArrayOf(1, labels.size),
                    DataType.FLOAT32
                )

                // 4. Run Inference
                currentInterpreter.run(tensorImage.buffer, outputBuffer.buffer)

                val endTime = System.currentTimeMillis()
                val latency = endTime - startTime

                // 5. Post-process results
                val floatArray: FloatArray = outputBuffer.floatArray
                var maxIndex = 0
                var maxConfidence = -1.0f
                
                for (i in 0 until floatArray.size) {
                    val confidence = floatArray[i]
                    if (confidence > maxConfidence) {
                        maxConfidence = confidence
                        maxIndex = i
                    }
                }

                ClassificationResult(
                    label = if (maxIndex < labels.size) labels[maxIndex] else "Unknown",
                    confidence = (maxConfidence * 100).coerceIn(0f, 100f),
                    latencyMs = latency
                )
            } catch (e: Exception) {
                Log.e("ImageClassifier", "Error during classification: ${e.message}")
                null
            }
        }
    }

    suspend fun classifyStatic(bitmap: Bitmap): ClassificationResult? = classifyRealtime(bitmap)

    fun release() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            Log.e("ImageClassifier", "Error releasing interpreter: ${e.message}")
        }
    }

    companion object {
        const val INPUT_SIZE = 224
    }
}
