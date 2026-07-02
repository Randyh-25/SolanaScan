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

// ID view yang benar sesuai activity_result.xml:
//   capturedImage     → ImageView gambar hasil foto
//   resultLabel       → TextView nama penyakit
//   resultConfidence  → TextView confidence score
//   resultLatency     → TextView inferensi latency
//   resultTimestamp   → TextView waktu capture
//   backButton        → Button kembali ke kamera

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

        if (label != null && confidence >= 0f) {
            // Jalur normal: data sudah dikirim dari CameraActivity via extras
            displayResult(label, confidence, latency)
        } else {
            // Fallback: extras tidak ada, klasifikasi ulang dari cache
            Log.w(TAG, "Tidak ada extras, fallback klasifikasi dari cache.")
            classifierFromCache()
        }

        binding.backButton.setOnClickListener { finish() }
    }

    private fun displayResult(label: String, confidence: Float, latency: Long) {
        // Tampilkan gambar dari cache
        val cacheFile = File(cacheDir, "captured_image.jpg")
        if (cacheFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            binding.capturedImage.setImageBitmap(bitmap)
        }

        binding.resultLabel.text      = label
        binding.resultConfidence.text = "${"%.2f".format(Locale.US, confidence)}%"
        binding.resultLatency.text    = if (latency > 0) "$latency ms" else "-- ms"
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

        // Inisialisasi classifier hanya saat fallback dibutuhkan
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
        private const val TAG      = "ResultActivity"
    }
}