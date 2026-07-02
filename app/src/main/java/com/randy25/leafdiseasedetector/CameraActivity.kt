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
import androidx.activity.result.PickVisualMediaRequest
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

// ============================================================
// DAFTAR PERBAIKAN:
//
// [BUG KRITIS 1] bitmap SELALU null di processImageAnalysis() →
//   Penyebab "classification failed" / "---" di UI.
//   try-catch-finally di Kotlin: jika `finally` dipanggil,
//   nilai return dari blok `try` DIBUANG. Artinya `bitmap`
//   selalu = null meskipun konversi berhasil.
//   → Pisahkan imageProxy.close() dari blok try-catch.
//     Gunakan pola: konversi dulu, close() di finally terpisah.
//
// [BUG KRITIS 2] lifecycleScope.launch{} dipanggil dari background
//   thread (cameraExecutor), bukan Main thread.
//   lifecycleScope tidak thread-safe untuk launch dari background;
//   bisa crash "Method addObserver must be called on the main thread".
//   → Gunakan Dispatchers.Main secara eksplisit atau
//     post ke main handler sebelum launch.
//
// [BUG MINOR] processGalleryImage & captureImage tidak menjalankan
//   klasifikasi sama sekali — hanya save ke cache lalu buka
//   ResultActivity. Hasil klasifikasi di ResultActivity akan
//   kosong/---. Tambah klasifikasi sebelum startActivity.
// ============================================================

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var classifierHelper: ImageClassifierHelper
    private lateinit var csvLogger: CSVLogger
    private lateinit var resourceMonitor: ResourceMonitor

    // Flag apakah inferensi realtime sedang berjalan
    private var isDetecting = false

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
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
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // Tombol mulai/stop deteksi realtime
        binding.startDetectionButton.setOnClickListener { startDetection() }
        binding.stopDetectionButton.setOnClickListener { stopDetection() }

        // Switcher fokus tanaman: Semua / Tomat / Kentang
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
     * Memulai kamera dengan preview + imageCapture saja (TANPA imageAnalysis).
     * Inferensi realtime baru dimulai ketika user menekan tombol "Mulai Deteksi".
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

                // Hanya bind preview + capture, TANPA imageAnalysis
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
     * Mulai inferensi realtime: bind imageAnalysis ke kamera.
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

        // Update UI: sembunyikan tombol mulai, tampilkan overlay + tombol stop
        binding.startDetectionButton.visibility = View.GONE
        binding.metricsOverlay.visibility = View.VISIBLE
        binding.stopDetectionButton.visibility = View.VISIBLE
        Log.d(TAG, "Realtime detection started")
    }

    /**
     * Stop inferensi realtime: unbind imageAnalysis dari kamera.
     * Preview dan capture tetap aktif.
     */
    private fun stopDetection() {
        if (!isDetecting) return
        isDetecting = false

        imageAnalysis?.let { cameraProvider?.unbind(it) }
        imageAnalysis = null

        // Update UI: tampilkan tombol mulai, sembunyikan overlay + tombol stop
        binding.startDetectionButton.visibility = View.VISIBLE
        binding.metricsOverlay.visibility = View.GONE
        binding.stopDetectionButton.visibility = View.GONE

        // Reset teks metrik
        binding.predictionLabel.text = "Prediction: --"
        binding.confidenceScore.text = "Confidence: --%"
        binding.latencyDisplay.text = "Latency: -- ms"
        binding.fpsDisplay.text = "FPS: --"
        Log.d(TAG, "Realtime detection stopped")
    }

    /**
     * Dipanggil dari background thread (cameraExecutor).
     *
     * [PERBAIKAN BUG 1]: Pisahkan imageProxy.close() dari try-catch.
     * Pada kode LAMA, struktur try { ... } catch { } finally { imageProxy.close() }
     * menyebabkan bitmap SELALU null karena di Kotlin nilai return blok try
     * dibuang ketika finally dieksekusi pada saat yang bersamaan dengan return.
     * Ini adalah sumber utama "classification failed" dan tampilan "---".
     *
     * [PERBAIKAN BUG 2]: lifecycleScope.launch{} harus dipanggil dari Main thread.
     * Karena fungsi ini berjalan di cameraExecutor (background), kita harus
     * pindah ke main thread dulu sebelum launch coroutine.
     */
    private fun processImageAnalysis(imageProxy: ImageProxy) {
        // Konversi dan close dipisah dengan benar
        val bitmap: Bitmap? = try {
            val converted = BitmapUtils.imageProxyToBitmap(imageProxy)
            BitmapUtils.rotateBitmap(converted, imageProxy.imageInfo.rotationDegrees.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "Konversi ImageProxy gagal: ${e.message}", e)
            null
        } finally {
            // close() tetap dipanggil, tapi TIDAK mempengaruhi nilai return di atas
            imageProxy.close()
        }

        bitmap ?: return // Jika konversi gagal, hentikan di sini

        // [PERBAIKAN BUG 2]: Post ke Main thread dulu, baru launch lifecycleScope
        // karena lifecycleScope.launch bisa crash jika dipanggil dari background thread.
        runOnUiThread {
            lifecycleScope.launch {
                val result = classifierHelper.classifyRealtime(bitmap)
                if (result != null) {
                    // Kalkulasi FPS dari latensi inferensi
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
     * [PERBAIKAN BUG MINOR]: Capture → klasifikasi → simpan hasil → buka ResultActivity.
     * Kode lama langsung saveBitmapToCache() tanpa klasifikasi,
     * sehingga ResultActivity tidak punya data hasil deteksi.
     */
    private fun captureImage() {
        val imageCapture = imageCapture ?: return

        binding.captureButton.isEnabled = false
        Toast.makeText(this, "Capturing...", Toast.LENGTH_SHORT).show()

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // Konversi dan close dipisah (sama seperti processImageAnalysis)
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

                    // Klasifikasi dulu, BARU buka ResultActivity
                    runOnUiThread {
                        lifecycleScope.launch {
                            val result = classifierHelper.classifyStatic(bitmap)
                            saveBitmapToCache(bitmap)

                            val intent = Intent(this@CameraActivity, ResultActivity::class.java).apply {
                                // Kirim hasil klasifikasi via Intent agar ResultActivity
                                // bisa langsung tampilkan tanpa load ulang
                                if (result != null) {
                                    csvLogger.logStatic(result, resourceMonitor.getCpuUsage(), resourceMonitor.getRamUsage(), "Captured_Image")
                                    putExtra(ResultActivity.EXTRA_LABEL,      result.label)
                                    putExtra(ResultActivity.EXTRA_CONFIDENCE, result.confidence)
                                    putExtra(ResultActivity.EXTRA_LATENCY,    result.latencyMs)
                                }
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
     * [PERBAIKAN BUG MINOR]: Galeri juga perlu klasifikasi sebelum buka ResultActivity.
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