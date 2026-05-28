package com.randy25.leafdiseasedetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.randy25.leafdiseasedetector.databinding.ActivityCameraBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var classifierHelper: ImageClassifierHelper
    private lateinit var csvLogger: CSVLogger
    private lateinit var resourceMonitor: ResourceMonitor

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Permissions are required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        classifierHelper = ImageClassifierHelper(this)
        csvLogger = CSVLogger(this)
        resourceMonitor = ResourceMonitor(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        binding.captureButton.setOnClickListener {
            captureImage()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
        requestPermissionLauncher.launch(permissions)
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requiredPermissions: Array<String>
        get() = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageAnalysis(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture)

            } catch (exc: Exception) {
                Log.e("CameraActivity", "Camera init failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageAnalysis(imageProxy: ImageProxy) {
        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        val bitmap = try {
            val converted = BitmapUtils.imageProxyToBitmap(imageProxy)
            BitmapUtils.rotateBitmap(converted, rotation)
        } catch (e: Exception) {
            Log.e("CameraActivity", "Conversion failed", e)
            null
        } finally {
            imageProxy.close()
        }

        if (bitmap != null) {
            lifecycleScope.launch {
                val result = classifierHelper.classifyRealtime(bitmap)
                if (result != null) {
                    runOnUiThread {
                        binding.predictionLabel.text = "Prediction: ${result.label}"
                        binding.confidenceScore.text = "Confidence: ${"%.2f".format(result.confidence)}%"
                        binding.latencyDisplay.text = "Latency: ${result.latencyMs} ms"
                    }
                    csvLogger.log(result, resourceMonitor.getCpuUsage(), resourceMonitor.getRamUsage())
                }
            }
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        
        runOnUiThread { binding.captureButton.isEnabled = false }
        Toast.makeText(this, "Capturing...", Toast.LENGTH_SHORT).show()

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees.toFloat()
                        val bitmap = BitmapUtils.imageProxyToBitmap(image)
                        val rotatedBitmap = BitmapUtils.rotateBitmap(bitmap, rotation)
                        saveBitmapToCache(rotatedBitmap)
                        
                        val intent = Intent(this@CameraActivity, ResultActivity::class.java)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("CameraActivity", "Capture error: ${e.message}")
                    } finally {
                        image.close()
                        runOnUiThread { binding.captureButton.isEnabled = true }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraActivity", "Capture failed: ${exception.message}")
                    runOnUiThread { binding.captureButton.isEnabled = true }
                }
            }
        )
    }

    private fun saveBitmapToCache(bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "captured_image.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Cache save error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        classifierHelper.release()
    }
}
