package com.randy25.leafdiseasedetector

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.randy25.leafdiseasedetector.databinding.ActivityResultBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity untuk menampilkan hasil klasifikasi.
 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var classifierHelper: ImageClassifierHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val label      = intent.getStringExtra(EXTRA_LABEL)
        val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, -1f)
        val latency    = intent.getLongExtra(EXTRA_LATENCY, -1L)
        val imageName  = intent.getStringExtra(EXTRA_IMAGE_NAME) ?: "--"

        if (label != null && confidence >= 0f) {
            displayResult(label, confidence, latency, imageName)
        } else {
            Log.w(TAG, "Tidak ada extras, fallback klasifikasi dari cache.")
            classifierFromCache()
        }

        binding.backButton.setOnClickListener { finish() }
    }

    private fun displayResult(label: String, confidence: Float, latency: Long, imageName: String = "--") {
        val cacheFile = File(cacheDir, "captured_image.jpg")
        if (cacheFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            binding.capturedImage.setImageBitmap(bitmap)
        }

        binding.resultLabel.text      = label
        binding.resultConfidence.text = "${"%.2f".format(Locale.US, confidence)}%"
        binding.resultLatency.text    = if (latency > 0) "$latency ms" else "-- ms"
        binding.resultImageName.text  = imageName
        binding.resultTimestamp.text  = SimpleDateFormat(
            "dd MMM yyyy, HH:mm:ss", Locale.getDefault()
        ).format(Date())
    }

    private fun classifierFromCache() {
        val cacheFile = File(cacheDir, "captured_image.jpg")
        if (!cacheFile.exists()) {
            Log.e(TAG, "Cache kosong dan tidak ada extras!")
            binding.resultLabel.text = "Error: tidak ada data"
            return
        }

        val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
        binding.capturedImage.setImageBitmap(bitmap)

        classifierHelper = ImageClassifierHelper(this)

        lifecycleScope.launch {
            val result = classifierHelper?.classifyStatic(bitmap)
            if (result != null) {
                displayResult(result.label, result.confidence, result.latencyMs)
            } else {
                binding.resultLabel.text     = "Klasifikasi gagal"
                binding.resultConfidence.text = "-- %"
                binding.resultLatency.text    = "-- ms"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifierHelper?.release()
    }

    companion object {
        const val EXTRA_LABEL      = "extra_label"
        const val EXTRA_CONFIDENCE = "extra_confidence"
        const val EXTRA_LATENCY    = "extra_latency"
        const val EXTRA_IMAGE_NAME = "extra_image_name"
        private const val TAG      = "ResultActivity"
    }
}