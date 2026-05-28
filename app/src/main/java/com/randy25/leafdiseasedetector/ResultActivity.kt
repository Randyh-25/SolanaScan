package com.randy25.leafdiseasedetector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.randy25.leafdiseasedetector.databinding.ActivityResultBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private lateinit var classifierHelper: ImageClassifierHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        classifierHelper = ImageClassifierHelper(this)

        // Load bitmap dari cache
        val cachedBitmap = loadBitmapFromCache()
        if (cachedBitmap != null) {
            binding.capturedImage.setImageBitmap(cachedBitmap)

            // Jalankan klasifikasi statis
            lifecycleScope.launch {
                val result = classifierHelper.classifyStatic(cachedBitmap)

                if (result != null) {
                    // Update UI dengan hasil
                    binding.resultLabel.text = result.label
                    binding.resultConfidence.text = "${"%.2f".format(result.confidence)}%"
                    binding.resultLatency.text = "${result.latencyMs} ms"

                    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val timeString = dateFormat.format(Date(result.timestamp))
                    binding.resultTimestamp.text = timeString

                    Log.d(
                        "ResultActivity",
                        "Classification complete: ${result.label} (${result.confidence}%) in ${result.latencyMs}ms"
                    )
                } else {
                    Toast.makeText(
                        this@ResultActivity,
                        "Classification failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Back button
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadBitmapFromCache(): Bitmap? {
        return try {
            val cacheDir = cacheDir
            val file = File(cacheDir, "captured_image.jpg")
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ResultActivity", "Error loading bitmap from cache: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifierHelper.release()
    }
}