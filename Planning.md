# Rencana Pengembangan Aplikasi Android Edge AI (Leaf Disease Detection)

**Dokumen ini ditujukan sebagai panduan komprehensif bagi AI Agent / Developer untuk membangun aplikasi Android MVP (Minimum Viable Product) secara langsung tanpa perlu proses tanya-jawab berulang.**

---

## Progress Checklist
- [x] **Tahap 1**: Setup Gradle dependency & Initial Project Setup
- [x] **Tahap 2**: Persiapan File Model & Label (Assets)
- [x] **Tahap 3**: Update AndroidManifest.xml (Permissions & Activities)
- [x] **Tahap 4**: Implementasi Utility & Helpers (In Progress)
- [x] **Tahap 5**: Implementasi CameraActivity (Real-time Inference)
- [x] **Tahap 6**: Implementasi ResultActivity (Static Inference)
- [x] **Tahap 7**: Monitoring Resource & CSV Logging

---

## 1. Konteks Proyek
Aplikasi ini adalah bagian dari Tugas Akhir tentang penggunaan Edge AI untuk deteksi penyakit daun tanaman keras (Kentang dan Tomat). Model yang digunakan adalah **ShuffleNetV2 1.0x** yang telah dikonversi menjadi format **TensorFlow Lite (TFLite) INT8** melalui _Post-Training Quantization_.

### Kriteria Utama:
1. **Real-time Inference**: Analisis video stream secara langsung dari kamera, menampilkan bounding box / label prediksi tertinggi dan metrik **Latensi (ms)** di layar.
2. **Snapshot Inference**: Tombol jepret (capture) untuk mengambil foto beresolusi tinggi, menjalankan inferensi secara statis pada foto tersebut, dan menampilkan halaman hasil secara detail (Confidence Score % dan Latensi ms).
3. **Offline**: 100% berjalan tanpa API eksternal/cloud.
4. **Android Stack**: Kotlin, CameraX, TensorFlow Lite, Coroutines.

---

## 2. Arsitektur & Teknologi
*   **Bahasa Pemrograman**: Kotlin
*   **Minimum SDK**: 24 (Android 7.0)
*   **Target SDK**: 34/36 (Android 14/15)
*   **Pola Arsitektur**: MVVM (Model-View-ViewModel)
*   **Kamera**: AndroidX CameraX (Use Cases: `Preview`, `ImageAnalysis`, `ImageCapture`).
*   **Machine Learning**: `org.tensorflow:tensorflow-lite:2.14.0` dan `org.tensorflow:tensorflow-lite-support`.

---

## 3. Persiapan Model Pembelajaran Mesin (ML) [COMPLETED]
File model berikut sudah ada di folder `app/src/main/assets/`:
1. `shufflenetv2_int8.tflite`
2. `labels.txt`

---

## 4. Alur UI/UX (User Flow)

### A. Layar 1: Utama / Kamera (Real-time View)
- `PreviewView` (Kamera Feed)
- `OverlayView`: Top-1 Class, Confidence Score, Inference Latency.
- FAB: Tombol Capture.

### B. Layar 2: Hasil Detail (Static View)
- `ImageView`: Foto hasil jepretan.
- `CardView`: Kelas, Confidence %, Latency (ms), Capture Time.

---

## 5. Konfigurasi build.gradle.kts (Update Required)
Pastikan `buildFeatures` menyertakan `viewBinding` agar layout dapat diakses dengan mudah.

```kotlin
android {
    // ... existing config ...
    buildFeatures {
        compose = true
        viewBinding = true // TAMBAHKAN INI
    }
    
    aaptOptions {
        noCompress("tflite")
    }
}
```

---

## 6. Implementasi Teknis (Langkah Berikutnya)

### Tahap 1: BitmapUtils.kt
Buat utilitas untuk konversi `ImageProxy` (YUV_420_888) ke `Bitmap` dan rotasi/resize.

### Tahap 2: ImageClassifierHelper.kt
Helper untuk load model TFLite dan menjalankan inference (`classifyRealtime` & `classifyStatic`).

### Tahap 3: Layout XML
Buat `activity_camera.xml` dan `activity_result.xml` sesuai rancangan di section 13 & 15 sebelumnya.

### Tahap 4: CameraActivity.kt
Hubungkan CameraX dengan `ImageClassifierHelper` untuk analisis real-time.

### Tahap 5: ResultActivity.kt
Tampilkan hasil jepretan dan jalankan klasifikasi statis.

---

## 7. Rincian Kode (Reference)
(Gunakan rincian kode dari section 11-20 pada dokumen perencanaan awal untuk implementasi).
