package com.randy25.leafdiseasedetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var classifierHelper: ImageClassifierHelper
    private lateinit var csvLogger: CSVLogger
    private lateinit var resourceMonitor: ResourceMonitor

    /** Status yang menandakan apakah inferensi realtime sedang berjalan. */
    private var isDetecting = false

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processGalleryImage(uri)
        } else {
            Log.d(TAG, "User membatalkan pilihan gambar")
        }
    }

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
        csvLogger        = CSVLogger(this)
        resourceMonitor  = ResourceMonitor(this)
        cameraExecutor   = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        binding.captureButton.setOnClickListener { captureImage() }
        binding.galleryButton.setOnClickListener {
            pickMediaLauncher.launch("image/*")
        }

        binding.startDetectionButton.setOnClickListener { startDetection() }
        binding.stopDetectionButton.setOnClickListener { stopDetection() }

        binding.focusToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnFocusTomato -> FocusMode.TOMATO
                    R.id.btnFocusPotato -> FocusMode.POTATO
                    else -> FocusMode.ALL
                }
                classifierHelper.focusMode = mode
                val modeText = when (mode) {
                    FocusMode.TOMATO -> "Tomat"
                    FocusMode.POTATO -> "Kentang"
                    FocusMode.ALL -> "Semua"
                }
                Toast.makeText(this, "Fokus: $modeText", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Focus mode changed to: $mode")
            }
        }
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(requiredPermissions)
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

    /**
     * Memulai kamera dengan preview dan imageCapture (tanpa imageAnalysis).
     * Inferensi realtime baru akan dimulai ketika pengguna menekan tombol "Mulai Deteksi".
     */
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

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Camera init failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Memulai inferensi realtime dengan mengikat imageAnalysis ke kamera.
     */
    private fun startDetection() {
        if (isDetecting) return
        isDetecting = true

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageAnalysis(imageProxy)
                }
            }

        try {
            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis!!
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Bind imageAnalysis failed", exc)
            isDetecting = false
            return
        }

        binding.startDetectionButton.visibility = View.GONE
        binding.metricsOverlay.visibility = View.VISIBLE
        binding.stopDetectionButton.visibility = View.VISIBLE
        Log.d(TAG, "Realtime detection started")
    }

    /**
     * Menghentikan inferensi realtime dengan melepaskan ikatan imageAnalysis dari kamera.
     * Preview dan fitur pengambilan gambar akan tetap aktif.
     */
    private fun stopDetection() {
        if (!isDetecting) return
        isDetecting = false

        imageAnalysis?.let { cameraProvider?.unbind(it) }
        imageAnalysis = null

        binding.startDetectionButton.visibility = View.VISIBLE
        binding.metricsOverlay.visibility = View.GONE
        binding.stopDetectionButton.visibility = View.GONE

        binding.predictionLabel.text = "Prediction: --"
        binding.confidenceScore.text = "Confidence: --%"
        binding.latencyDisplay.text = "Latency: -- ms"
        binding.fpsDisplay.text = "FPS: --"
        Log.d(TAG, "Realtime detection stopped")
    }

    /**
     * Memproses analisis gambar dari thread latar belakang (cameraExecutor).
     *
     * Mengkonversi ImageProxy menjadi Bitmap dan menjalankan inferensi realtime.
     * Pastikan coroutine dijalankan pada Main thread untuk mencegah terjadinya crash pada lifecycleScope.
     */
    private fun processImageAnalysis(imageProxy: ImageProxy) {
        val bitmap: Bitmap? = try {
            val converted = BitmapUtils.imageProxyToBitmap(imageProxy)
            BitmapUtils.rotateBitmap(converted, imageProxy.imageInfo.rotationDegrees.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "Konversi ImageProxy gagal: ${e.message}", e)
            null
        } finally {
            imageProxy.close()
        }

        bitmap ?: return

        runOnUiThread {
            lifecycleScope.launch {
                val result = classifierHelper.classifyRealtime(bitmap)
                if (result != null) {
                    val fps = if (result.latencyMs > 0) 1000.0 / result.latencyMs else 0.0

                    binding.predictionLabel.text  = "Prediction: ${result.label}"
                    binding.confidenceScore.text  = "Confidence: ${"%.2f".format(Locale.US, result.confidence)}%"
                    binding.latencyDisplay.text   = "Latency: ${result.latencyMs} ms"
                    binding.fpsDisplay.text        = "FPS: ${"%.1f".format(Locale.US, fps)}"
                    csvLogger.logRealtime(result, fps, resourceMonitor.getCpuUsage(), resourceMonitor.getRamUsage())
                } else {
                    Log.w(TAG, "classifyRealtime() mengembalikan null")
                }
            }
        }
    }

    /**
     * Menangkap gambar, melakukan klasifikasi, menyimpan hasil, dan membuka ResultActivity.
     */
    private fun captureImage() {
        val imageCapture = imageCapture ?: return

        binding.captureButton.isEnabled = false
        Toast.makeText(this, "Capturing...", Toast.LENGTH_SHORT).show()

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap: Bitmap? = try {
                        val raw = BitmapUtils.imageProxyToBitmap(image)
                        BitmapUtils.rotateBitmap(raw, image.imageInfo.rotationDegrees.toFloat())
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture konversi gagal: ${e.message}", e)
                        null
                    } finally {
                        image.close()
                    }

                    if (bitmap == null) {
                        runOnUiThread { binding.captureButton.isEnabled = true }
                        return
                    }

                    runOnUiThread {
                        lifecycleScope.launch {
                            val result = classifierHelper.classifyStatic(bitmap)
                            saveBitmapToCache(bitmap)

                            val intent = Intent(this@CameraActivity, ResultActivity::class.java).apply {
                                if (result != null) {
                                    csvLogger.logStatic(result, resourceMonitor.getCpuUsage(), resourceMonitor.getRamUsage(), "Captured_Image")
                                    putExtra(ResultActivity.EXTRA_LABEL,      result.label)
                                    putExtra(ResultActivity.EXTRA_CONFIDENCE, result.confidence)
                                    putExtra(ResultActivity.EXTRA_LATENCY,    result.latencyMs)
                                }
                                putExtra(ResultActivity.EXTRA_IMAGE_NAME, "Captured_Image")
                            }
                            startActivity(intent)
                            binding.captureButton.isEnabled = true
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture gagal: ${exception.message}")
                    runOnUiThread { binding.captureButton.isEnabled = true }
                }
            }
        )
    }

    /**
     * Memproses gambar yang dipilih dari galeri untuk diklasifikasikan sebelum membuka ResultActivity.
     */
    private fun processGalleryImage(uri: Uri) {
        val bitmap = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal decode gambar galeri: ${e.message}", e)
            null
        }

        if (bitmap == null) {
            Toast.makeText(this, "Gagal memuat gambar", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = classifierHelper.classifyStatic(bitmap)
            saveBitmapToCache(bitmap)
            
            val imageName = getFileName(uri)

            val intent = Intent(this@CameraActivity, ResultActivity::class.java).apply {
                if (result != null) {
                    csvLogger.logStatic(result, resourceMonitor.getCpuUsage(), resourceMonitor.getRamUsage(), imageName)
                    putExtra(ResultActivity.EXTRA_LABEL,      result.label)
                    putExtra(ResultActivity.EXTRA_CONFIDENCE, result.confidence)
                    putExtra(ResultActivity.EXTRA_LATENCY,    result.latencyMs)
                }
                putExtra(ResultActivity.EXTRA_IMAGE_NAME, imageName)
            }
            startActivity(intent)
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "captured_image.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache save error: ${e.message}")
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Gallery_Image"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        classifierHelper.release()
        csvLogger.close()
    }

    companion object {
        private const val TAG = "CameraActivity"
    }
}