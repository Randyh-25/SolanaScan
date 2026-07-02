package com.randy25.leafdiseasedetector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Mode fokus deteksi tanaman.
 * ALL = semua label aktif, TOMATO = hanya label tomat, POTATO = hanya label kentang.
 */
enum class FocusMode {
    ALL, TOMATO, POTATO
}

data class ClassificationResult(
    val label: String,
    val confidence: Float,
    val latencyMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

class ImageClassifierHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    /** Mode fokus deteksi: atur dari CameraActivity via switcher */
    var focusMode: FocusMode = FocusMode.ALL

    // Nama model dan label
    private val modelName = "shufflenetv2_int8.tflite"
    private val labelsFileName = "labels.txt"

    private var inputTensorType: DataType = DataType.FLOAT32
    private var outputTensorType: DataType = DataType.FLOAT32

    private var inputScale: Float = 0f
    private var inputZeroPoint: Int = 0
    private var outputScale: Float = 0f
    private var outputZeroPoint: Int = 0

    // Menyimpan jumlah kelas langsung dari bentuk asli model
    private var modelOutputClasses: Int = 0

    // Pre-allocated buffers untuk menghindari alokasi ulang setiap frame
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var pixelValues: IntArray? = null

    init {
        initClassifier()
    }

    private fun initClassifier() {
        try {
            val assetManager = context.assets
            val modelBuffer = loadModelFile(assetManager, modelName)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }

            interpreter = Interpreter(modelBuffer, options)

            // Baca labels dan filter baris kosong agar tidak ada error index OutOfBounds
            labels = assetManager.open(labelsFileName).bufferedReader().readLines().filter { it.isNotBlank() }

            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)

            inputTensorType = inputTensor.dataType()
            outputTensorType = outputTensor.dataType()

            // Baca jumlah kelas dari model asli (Biasanya shape: [1, 13] -> ambil index ke-1 yaitu 13)
            modelOutputClasses = outputTensor.shape()[1]

            // Ekstrak Parameter Kuantisasi Input
            val inQuant = inputTensor.quantizationParams()
            inputScale = inQuant?.scale ?: 0f
            inputZeroPoint = inQuant?.zeroPoint ?: 0

            // Ekstrak Parameter Kuantisasi Output
            val outQuant = outputTensor.quantizationParams()
            outputScale = outQuant?.scale ?: 0f
            outputZeroPoint = outQuant?.zeroPoint ?: 0

            // Pre-alokasi buffer agar tidak dibuat ulang setiap frame
            val inputBytesPerChannel = if (inputTensorType == DataType.FLOAT32) 4 else 1
            inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * inputBytesPerChannel).apply {
                order(ByteOrder.nativeOrder())
            }

            val outputBytesPerChannel = if (outputTensorType == DataType.FLOAT32) 4 else 1
            outputBuffer = ByteBuffer.allocateDirect(modelOutputClasses * outputBytesPerChannel).apply {
                order(ByteOrder.nativeOrder())
            }

            pixelValues = IntArray(INPUT_SIZE * INPUT_SIZE)

            Log.d("ImageClassifier", "Model Loaded | Input: $inputTensorType | Output: $outputTensorType | Classes: $modelOutputClasses")
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
                val currentInterpreter = interpreter ?: return@withContext null
                val inBuf = inputBuffer ?: return@withContext null
                val outBuf = outputBuffer ?: return@withContext null
                val intValues = pixelValues ?: return@withContext null

                val startTime = SystemClock.uptimeMillis()

                // 1. Resize Bitmap ke 224x224
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

                // 2. Siapkan Input ByteBuffer (reuse pre-allocated buffer)
                inBuf.clear()
                resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

                // 3. Preprocessing Manual: ImageNet Normalization + Quantization
                var pixel = 0
                for (i in 0 until INPUT_SIZE) {
                    for (j in 0 until INPUT_SIZE) {
                        val valPixel = intValues[pixel++]

                        // Ekstrak RGB dan Normalisasi persis seperti PyTorch transforms.Normalize
                        val r = (((valPixel shr 16 and 0xFF) / 255.0f) - 0.485f) / 0.229f
                        val g = (((valPixel shr 8 and 0xFF) / 255.0f) - 0.456f) / 0.224f
                        val b = (((valPixel and 0xFF) / 255.0f) - 0.406f) / 0.225f

                        when (inputTensorType) {
                            DataType.FLOAT32 -> {
                                inBuf.putFloat(r)
                                inBuf.putFloat(g)
                                inBuf.putFloat(b)
                            }
                            DataType.INT8 -> {
                                inBuf.put(quantizeToInt8(r, inputScale, inputZeroPoint))
                                inBuf.put(quantizeToInt8(g, inputScale, inputZeroPoint))
                                inBuf.put(quantizeToInt8(b, inputScale, inputZeroPoint))
                            }
                            DataType.UINT8 -> {
                                inBuf.put(quantizeToUint8(r, inputScale, inputZeroPoint))
                                inBuf.put(quantizeToUint8(g, inputScale, inputZeroPoint))
                                inBuf.put(quantizeToUint8(b, inputScale, inputZeroPoint))
                            }
                            else -> {}
                        }
                    }
                }

                // 4. Siapkan Output ByteBuffer (reuse pre-allocated buffer)
                outBuf.clear()

                // 5. Eksekusi Inferensi
                currentInterpreter.run(inBuf, outBuf)

                val endTime = SystemClock.uptimeMillis()
                val latency = endTime - startTime

                // 6. Postprocessing: Dequantize (Ubah kembali INT8 ke Float / Logit murni)
                outBuf.rewind()
                val logits = FloatArray(modelOutputClasses)
                for (i in 0 until modelOutputClasses) {
                    when (outputTensorType) {
                        DataType.FLOAT32 -> logits[i] = outBuf.float
                        DataType.INT8 -> {
                            val qVal = outBuf.get() // Java byte is signed (-128 to 127)
                            logits[i] = (qVal - outputZeroPoint) * outputScale
                        }
                        DataType.UINT8 -> {
                            val qVal = outBuf.get().toInt() and 0xFF
                            logits[i] = (qVal - outputZeroPoint) * outputScale
                        }
                        else -> logits[i] = 0f
                    }
                }

                // 7. Softmax (Sama persis seperti penerapan fungsi eksponensial di Python)
                val maxLogit = logits.maxOrNull() ?: 0f
                var sumExp = 0f
                val probabilities = FloatArray(logits.size)

                for (i in logits.indices) {
                    // Pengurangan maxLogit dilakukan untuk stabilitas numerik (mencegah NaN/Infinity)
                    probabilities[i] = exp((logits[i] - maxLogit).toDouble()).toFloat()
                    sumExp += probabilities[i]
                }

                for (i in probabilities.indices) {
                    probabilities[i] /= sumExp // Menghasilkan probabilitas murni 0.0 s/d 1.0
                }

                // Filter berdasarkan FocusMode: matikan label yang tidak sesuai
                if (focusMode != FocusMode.ALL) {
                    for (i in probabilities.indices) {
                        val rawLabel = if (i < labels.size) labels[i] else ""
                        val isTomato = rawLabel.startsWith("Tomato", ignoreCase = true)
                        val isPotato = rawLabel.startsWith("Potato", ignoreCase = true)
                        when (focusMode) {
                            FocusMode.TOMATO -> if (!isTomato) probabilities[i] = 0f
                            FocusMode.POTATO -> if (!isPotato) probabilities[i] = 0f
                            else -> { /* ALL — tidak difilter */ }
                        }
                    }
                    // Re-normalisasi agar total probabilitas = 1.0
                    val filteredSum = probabilities.sum()
                    if (filteredSum > 0f) {
                        for (i in probabilities.indices) {
                            probabilities[i] /= filteredSum
                        }
                    }
                }

                var maxIndex = 0
                var maxConfidence = 0f
                for (i in probabilities.indices) {
                    if (probabilities[i] > maxConfidence) {
                        maxConfidence = probabilities[i]
                        maxIndex = i
                    }
                }

                // 8. Pemetaan ke Label String dengan proteksi pencegah Force Close
                val rawLabel = if (maxIndex < labels.size) labels[maxIndex] else "Class_$maxIndex"
                // 9. Terjemahkan label mentah model → format akademis naskah penelitian
                val finalLabel = LABEL_MAP[rawLabel] ?: rawLabel

                ClassificationResult(
                    label = finalLabel,
                    confidence = (maxConfidence * 100).coerceIn(0f, 100f),
                    latencyMs = latency
                )
            } catch (e: Exception) {
                Log.e("ImageClassifier", "Error during classification: ${e.message}", e)
                null
            }
        }
    }

    // Fungsi Kuantisasi presisi seperti Numpy np.clip & np.round
    private fun quantizeToInt8(value: Float, scale: Float, zeroPoint: Int): Byte {
        if (scale == 0f) return 0
        val q = (value / scale) + zeroPoint
        return q.roundToInt().coerceIn(-128, 127).toByte()
    }

    private fun quantizeToUint8(value: Float, scale: Float, zeroPoint: Int): Byte {
        if (scale == 0f) return 0
        val q = (value / scale) + zeroPoint
        return q.roundToInt().coerceIn(0, 255).toByte()
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

        /**
         * Pemetaan label mentah model → format akademis sesuai naskah penelitian.
         * Key HARUS cocok persis dengan isi labels.txt.
         */
        val LABEL_MAP: Map<String, String> = mapOf(
            "Potato___healthy"                              to "Potato Healthy (K01)",
            "Potato___Early_blight"                         to "Potato Early Blight (K02)",
            "Potato___Late_blight"                          to "Potato Late Blight (K03)",
            "Tomato_healthy"                                to "Tomato Healthy (T01)",
            "Tomato_Early_blight"                           to "Tomato Early Blight (T02)",
            "Tomato_Late_blight"                            to "Tomato Late Blight (T03)",
            "Tomato_Bacterial_spot"                         to "Tomato Bacterial Spot (T04)",
            "Tomato_Leaf_Mold"                              to "Tomato Leaf Mold (T05)",
            "Tomato__Target_Spot"                           to "Tomato Target Spot (T06)",
            "Tomato_Septoria_leaf_spot"                     to "Tomato Septoria Leaf Spot (T07)",
            "Tomato_Spider_mites_Two_spotted_spider_mite"   to "Tomato Spider Mites (T08)",
            "Tomato__Tomato_YellowLeaf__Curl_Virus"         to "Tomato Yellow Leaf Curl Virus (T09)",
            "Tomato__Tomato_mosaic_virus"                   to "Tomato Mosaic Virus (T10)"
        )
    }
}