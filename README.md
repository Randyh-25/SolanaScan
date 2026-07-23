# 🏛️ Rangkuman Arsitektur Sistem — LeafDiseaseDetector

> Aplikasi Android **Edge AI** untuk deteksi penyakit daun tanaman (Kentang & Tomat) menggunakan model **ShuffleNetV2 1.0x** yang berjalan 100% offline di perangkat.

---

## 1. Informasi Umum Proyek

| Item | Detail |
|---|---|
| **Nama Aplikasi** | LeafDiseaseDetector |
| **Package** | `com.randy25.leafdiseasedetector` |
| **Bahasa** | Kotlin |
| **Min SDK** | 24 (Android 7.0) |
| **Target SDK** | 36 (Android 15) |
| **Build System** | Gradle KTS + Version Catalog (`libs.versions.toml`) |
| **Pola Arsitektur** | Activity-based (View Layer) + Helper Classes |

---

## 2. Diagram Arsitektur

### 2.0 Overview (Garis Besar)

Diagram berikut menunjukkan alur sistem secara keseluruhan dalam 4 blok utama:

```mermaid
graph TB
    USER["Pengguna"]
    UI["UI Layer (Camera & Result Screen)"]
    ENGINE["Inference Engine (Preprocessing + TFLite)"]
    MODEL["On-Device Model (ShuffleNetV2 INT8)"]
    LOG["Monitoring & Logging"]

    USER -->|"Arahkan kamera / Pilih gambar"| UI
    UI -->|"Bitmap gambar"| ENGINE
    ENGINE -->|"Load & Jalankan"| MODEL
    MODEL -->|"Probabilitas 13 kelas"| ENGINE
    ENGINE -->|"Label + Confidence + Latency"| UI
    UI -->|"Tampilkan hasil"| USER
    UI -.->|"Catat metrik"| LOG
```

> **Pengguna** berinteraksi melalui UI (kamera real-time atau gallery) → gambar diteruskan ke **Inference Engine** yang memproses dan menjalankan model TFLite → hasil klasifikasi dikembalikan ke UI → ditampilkan kepada pengguna. Secara paralel, metrik performa dicatat oleh modul monitoring.

### 2.1 Diagram Detail (Per Komponen)

```mermaid
graph TB
    subgraph "Presentation Layer"
        CA["CameraActivity"]
        RA["ResultActivity"]
    end

    subgraph "ML / Inference Layer"
        ICH["ImageClassifierHelper"]
        BU["BitmapUtils"]
    end

    subgraph "Monitoring & Logging Layer"
        RM["ResourceMonitor"]
        CSV["CSVLogger"]
    end

    subgraph "Assets (On-Device)"
        MODEL["shufflenetv2_int8.tflite<br/>(1.3 MB, INT8 Quantized)"]
        LABELS["labels.txt<br/>(13 kelas)"]
    end

    subgraph "Android Platform APIs"
        CAMERAX["CameraX<br/>(Preview + ImageAnalysis + ImageCapture)"]
        TFLITE["TensorFlow Lite<br/>Interpreter"]
    end

    CA -->|"Real-time frames"| BU
    CA -->|"Capture / Gallery"| BU
    BU -->|"Bitmap 224×224"| ICH
    ICH -->|"Load model"| TFLITE
    TFLITE -->|"Read"| MODEL
    ICH -->|"Read"| LABELS
    ICH -->|"ClassificationResult"| CA
    ICH -->|"ClassificationResult"| RA
    CA -->|"Intent + Extras"| RA
    CA -->|"Log result"| CSV
    CA -->|"CPU / RAM"| RM
    CA -->|"Preview Feed"| CAMERAX
```

---

## 3. Struktur Direktori Proyek

```
AndroidAPPProject/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── shufflenetv2_int8.tflite      ← Model TFLite (INT8)
│       │   └── labels.txt                     ← 13 kelas penyakit
│       ├── java/com/randy25/leafdiseasedetector/
│       │   ├── CameraActivity.kt              ← Layar utama + kamera
│       │   ├── ResultActivity.kt              ← Layar hasil klasifikasi
│       │   ├── ImageClassifierHelper.kt       ← Inference engine
│       │   ├── BitmapUtils.kt                 ← Konversi & preprocessing gambar
│       │   ├── CSVLogger.kt                   ← Logging hasil ke CSV
│       │   ├── ResourceMonitor.kt             ← Monitor CPU & RAM
│       │   └── ui/theme/                      ← Jetpack Compose theme (unused)
│       └── res/
│           ├── layout/
│           │   ├── activity_camera.xml         ← Layout kamera real-time
│           │   └── activity_result.xml         ← Layout hasil detail
│           └── values/, drawable/, mipmap-*/
└── build.gradle.kts, settings.gradle.kts
```

---

## 4. Komponen Utama & Tanggung Jawab

### 4.1 Presentation Layer (Activities)

#### [CameraActivity.kt](file:///d:/Documents/Semester%208/Tugas%20Akhir/AndroidAPPProject/app/src/main/java/com/randy25/leafdiseasedetector/CameraActivity.kt) — **Launcher / Layar Utama**

| Fitur | Implementasi |
|---|---|
| **Camera Preview** | CameraX `PreviewView` dengan `DEFAULT_BACK_CAMERA` |
| **Real-time Inference** | `ImageAnalysis` (backpressure: `KEEP_ONLY_LATEST`) → setiap frame diklasifikasi |
| **Capture** | `ImageCapture` → klasifikasi static → simpan ke cache → buka `ResultActivity` |
| **Gallery Picker** | `PickVisualMedia` API → decode bitmap → klasifikasi → buka `ResultActivity` |
| **HUD Overlay** | Menampilkan: Prediction Label, Confidence %, Latency ms |
| **Threading** | `cameraExecutor` (single-thread) untuk capture/analysis, `lifecycleScope` + `runOnUiThread` untuk UI |

#### [ResultActivity.kt](file:///d:/Documents/Semester%208/Tugas%20Akhir/AndroidAPPProject/app/src/main/java/com/randy25/leafdiseasedetector/ResultActivity.kt) — **Layar Hasil Detail**

| Fitur | Implementasi |
|---|---|
| **Tampilan Gambar** | Membaca `captured_image.jpg` dari cache |
| **Hasil Klasifikasi** | Menerima via `Intent Extras` (label, confidence, latency) |
| **Fallback** | Jika extras kosong → inisialisasi ulang `ImageClassifierHelper` → klasifikasi dari cache |
| **Info Ditampilkan** | Label penyakit, Confidence %, Latency ms, Capture timestamp |

---

### 4.2 ML / Inference Layer

#### [ImageClassifierHelper.kt](file:///d:/Documents/Semester%208/Tugas%20Akhir/AndroidAPPProject/app/src/main/java/com/randy25/leafdiseasedetector/ImageClassifierHelper.kt) — **Inference Engine**

Komponen inti yang membungkus seluruh pipeline TFLite:

```mermaid
graph LR
    A["Bitmap Input"] --> B["Resize 224×224"]
    B --> C["ImageNet Normalize<br/>mean=[0.485,0.456,0.406]<br/>std=[0.229,0.224,0.225]"]
    C --> D{"Input Type?"}
    D -->|FLOAT32| E1["putFloat(r,g,b)"]
    D -->|INT8| E2["quantizeToInt8()"]
    D -->|UINT8| E3["quantizeToUint8()"]
    E1 --> F["TFLite Interpreter.run()"]
    E2 --> F
    E3 --> F
    F --> G{"Output Type?"}
    G -->|FLOAT32| H1["Read float logits"]
    G -->|INT8/UINT8| H2["Dequantize logits"]
    H1 --> I["Softmax"]
    H2 --> I
    I --> J["Top-1 Label + Confidence%"]
```

| Detail | Nilai |
|---|---|
| **Model** | `shufflenetv2_int8.tflite` (1.3 MB, INT8 Post-Training Quantization) |
| **Input** | `[1, 224, 224, 3]` — RGB normalized (ImageNet stats) |
| **Output** | `[1, 13]` — 13 kelas (logits → softmax → probabilitas) |
| **Threads** | 4 CPU threads |
| **Quantization** | Adaptif: membaca `dataType()` dan `quantizationParams()` saat init |
| **Suspend** | `classifyRealtime()` dan `classifyStatic()` berjalan di `Dispatchers.Default` |

#### [BitmapUtils.kt](file:///d:/Documents/Semester%208/Tugas%20Akhir/AndroidAPPProject/app/src/main/java/com/randy25/leafdiseasedetector/BitmapUtils.kt) — **Utilitas Gambar**

| Fungsi | Deskripsi |
|---|---|
| `imageProxyToBitmap()` | Konversi `ImageProxy` (YUV_420_888 / JPEG) → `Bitmap` via CameraX built-in |
| `resizeBitmap()` | Resize ke ukuran target (224×224) |
| `rotateBitmap()` | Rotasi sesuai orientasi sensor kamera |

---

### 4.3 Monitoring & Logging Layer

#### [ResourceMonitor.kt](file:///d:/Documents/Semester%208/Tugas%20Akhir/AndroidAPPProject/app/src/main/java/com/randy25/leafdiseasedetector/ResourceMonitor.kt)

| Metrik | Cara Pengukuran |
|---|---|
| **RAM (PSS)** | `Debug.getMemoryInfo()` → `totalPss / 1024` → MB |
| **CPU %** | Delta `Process.getElapsedCpuTime()` / Delta `SystemClock.elapsedRealtime()` × 100 |

#### [CSVLogger.kt](file:///d:/Documents/Semester%208/Tugas%20Akhir/AndroidAPPProject/app/src/main/java/com/randy25/leafdiseasedetector/CSVLogger.kt)

- **Lokasi**: `Documents/leaf_detection_logs.csv` (external files dir)
- **Kolom**: `Timestamp, Label, Confidence, Latency_ms, CPU_Usage, RAM_Usage_MB`
- Setiap frame real-time yang berhasil diklasifikasi → 1 baris CSV

---

## 5. Alur Data (Data Flow)

### 5.1 Mode Real-time (Kamera)

```mermaid
sequenceDiagram
    participant Camera as CameraX
    participant Exec as cameraExecutor
    participant BU as BitmapUtils
    participant ICH as ImageClassifierHelper
    participant UI as Main Thread (UI)
    participant Log as CSVLogger

    Camera->>Exec: ImageProxy (setiap frame)
    Exec->>BU: imageProxyToBitmap() + rotateBitmap()
    BU-->>Exec: Bitmap
    Exec->>UI: runOnUiThread
    UI->>ICH: classifyRealtime(bitmap)
    Note over ICH: Resize → Normalize → Quantize → Infer → Softmax
    ICH-->>UI: ClassificationResult
    UI->>UI: Update HUD overlay
    UI->>Log: log(result, cpu, ram)
```

### 5.2 Mode Snapshot (Capture / Gallery)

```mermaid
sequenceDiagram
    participant User as User
    participant CA as CameraActivity
    participant ICH as ImageClassifierHelper
    participant RA as ResultActivity

    User->>CA: Tap Capture / Pick Gallery
    CA->>CA: toBitmap() + rotateBitmap()
    CA->>ICH: classifyStatic(bitmap)
    ICH-->>CA: ClassificationResult
    CA->>CA: saveBitmapToCache()
    CA->>RA: Intent(label, confidence, latency)
    RA->>RA: displayResult()
```

---

## 6. Kelas Penyakit yang Dideteksi (13 Kelas)

| # | Label | Tanaman |
|---|---|---|
| 0 | Potato___Early_blight | 🥔 Kentang |
| 1 | Potato___Late_blight | 🥔 Kentang |
| 2 | Potato___healthy | 🥔 Kentang |
| 3 | Tomato__Target_Spot | 🍅 Tomat |
| 4 | Tomato__YellowLeaf_Curl_Virus | 🍅 Tomat |
| 5 | Tomato__Tomato_mosaic_virus | 🍅 Tomat |
| 6 | Tomato_Bacterial_spot | 🍅 Tomat |
| 7 | Tomato_Early_blight | 🍅 Tomat |
| 8 | Tomato_Late_blight | 🍅 Tomat |
| 9 | Tomato_Leaf_Mold | 🍅 Tomat |
| 10 | Tomato_Septoria_leaf_spot | 🍅 Tomat |
| 11 | Tomato_Spider_mites | 🍅 Tomat |
| 12 | Tomato_healthy | 🍅 Tomat |

---

## 7. Technology Stack & Dependencies

```mermaid
graph LR
    subgraph "Core"
        K["Kotlin (JVM 17)"]
        AG["Android Gradle 36"]
    end

    subgraph "UI Framework"
        VB["ViewBinding (XML Layouts)"]
        MAT["Material Components"]
        COMPOSE["Jetpack Compose (theme only)"]
    end

    subgraph "Camera"
        CX["CameraX 1.4.0<br/>(camera-core, camera2,<br/>lifecycle, view)"]
    end

    subgraph "ML Runtime"
        TFL["TensorFlow Lite 2.17.0"]
        TFLS["TFLite Support 0.4.4"]
        LITERT["LiteRT API 1.0.1<br/>(substituted)"]
    end

    subgraph "Async"
        CR["Kotlin Coroutines 1.7.3"]
        LC["Lifecycle ViewModel/LiveData 2.7.0"]
    end

    K --> VB
    K --> CX
    K --> TFL
    K --> CR
    TFL -.->|"dependency substitution"| LITERT
```

> [!NOTE]
> Terdapat **dependency substitution** di `build.gradle.kts`: `tensorflow-lite-api` di-redirect ke `com.google.ai.edge.litert:litert-api:1.0.1` untuk kompatibilitas.

---

## 8. Status Pengembangan (Progress)

| Tahap | Status | Keterangan |
|---|---|---|
| ✅ Setup Gradle & Project | **Done** | Dependencies lengkap |
| ✅ Model & Label Assets | **Done** | `shufflenetv2_int8.tflite` + `labels.txt` |
| ✅ AndroidManifest | **Done** | Permissions, 2 Activities terdaftar |
| ✅ Utility & Helpers | **Done** | `BitmapUtils`, `ImageClassifierHelper`, `CSVLogger`, `ResourceMonitor` sudah ada |
| ✅ CameraActivity | **Done** | Kode ada, perlu testing di device |
| ✅ ResultActivity | **Done** | Kode ada, perlu testing di device |
| ✅ Resource Monitoring | **Done** | CSV logging sudah terimplementasi |

---

## 9. Catatan Arsitektural

> [!IMPORTANT]
> **Bug yang Sudah Diperbaiki** (didokumentasikan langsung di kode):
> 1. **BUG KRITIS 1**: `bitmap` selalu `null` di `processImageAnalysis()` karena `finally { imageProxy.close() }` dalam Kotlin membuang return value blok `try`. Solusi: pisahkan close dari try-catch.
> 2. **BUG KRITIS 2**: `lifecycleScope.launch{}` dipanggil dari background thread → crash. Solusi: gunakan `runOnUiThread {}` sebelum launch.
> 3. **BUG MINOR**: Capture/Gallery tidak menjalankan klasifikasi sebelum membuka `ResultActivity`. Solusi: tambahkan klasifikasi dan kirim hasil via Intent extras.

> [!TIP]
> **Peluang Improvement**:
> - Migrasi dari Activity-based ke **MVVM** penuh dengan `ViewModel` + `StateFlow`
> - Compose theme sudah ada tapi belum digunakan — bisa migrasi UI ke full Compose
> - `CSVLogger` menulis file secara sinkron di setiap frame → pertimbangkan batching atau coroutine channel
> - `classifyStatic()` saat ini hanya memanggil `classifyRealtime()` — bisa dioptimasi khusus untuk gambar resolusi tinggi
