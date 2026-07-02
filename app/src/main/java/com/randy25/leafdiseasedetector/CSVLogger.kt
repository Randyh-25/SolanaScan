package com.randy25.leafdiseasedetector

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CSVLogger(private val context: Context) {

    private var realtimeFile: File? = null
    private var staticFile: File? = null
    private var realtimeWriter: BufferedWriter? = null

    private val realtimeFileName = "realtime_logs.csv"
    private val staticFileName = "static_logs.csv"

    // Counter untuk periodic flush (setiap N baris)
    private var realtimeWriteCount = 0
    private val flushInterval = 10

    init {
        val dir = getDocumentsDir()
        realtimeFile = createFile(dir, realtimeFileName)
        staticFile = createFile(dir, staticFileName)

        // Buka BufferedWriter untuk realtime agar tidak open/close setiap frame
        try {
            realtimeFile?.let {
                realtimeWriter = BufferedWriter(FileWriter(it, true))
            }
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error opening realtime writer: ${e.message}")
        }
    }

    private fun getDocumentsDir(): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun createFile(dir: File, name: String): File? {
        return try {
            val file = File(dir, name)
            if (!file.exists()) {
                val writer = FileWriter(file, true)
                writer.append("Timestamp,Image_Name,Label,Confidence,Latency_ms,FPS,CPU_Usage,RAM_Usage_MB\n")
                writer.flush()
                writer.close()
                Log.d("CSVLogger", "CSV file created at: ${file.absolutePath}")
            }
            file
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error creating CSV file ($name): ${e.message}")
            null
        }
    }

    /**
     * Log hasil inferensi realtime kamera.
     * Data ditulis ke realtime_logs.csv menggunakan BufferedWriter
     * yang tetap terbuka agar tidak open/close setiap frame.
     */
    fun logRealtime(result: ClassificationResult, fps: Double, cpuUsage: String, ramUsage: Long) {
        try {
            val writer = realtimeWriter ?: return
            val row = buildRow(result, fps, cpuUsage, ramUsage, "Realtime_Camera")
            writer.write(row)
            realtimeWriteCount++

            // Flush secara periodik, bukan setiap baris
            if (realtimeWriteCount >= flushInterval) {
                writer.flush()
                realtimeWriteCount = 0
            }
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error writing realtime CSV: ${e.message}")
        }
    }

    /**
     * Log hasil inferensi dari capture atau galeri.
     * Data ditulis ke static_logs.csv.
     * Karena jarang dipanggil, open/close per panggilan masih aman.
     */
    fun logStatic(result: ClassificationResult, cpuUsage: String, ramUsage: Long, imageName: String) {
        try {
            val writer = FileWriter(staticFile, true)
            val row = buildRow(result, 0.0, cpuUsage, ramUsage, imageName)
            writer.write(row)
            writer.flush()
            writer.close()
            Log.d("CSVLogger", "Logged to static_logs.csv: $row")
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error writing static CSV: ${e.message}")
        }
    }

    private fun buildRow(result: ClassificationResult, fps: Double, cpuUsage: String, ramUsage: Long, imageName: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date(result.timestamp))
        // Menggunakan Locale.US agar nilai desimal menggunakan titik (.)
        val fpsStr = "%.1f".format(Locale.US, fps)
        return "$timestamp,$imageName,${result.label},${"%.2f".format(Locale.US, result.confidence)},${result.latencyMs},$fpsStr,$cpuUsage,$ramUsage\n"
    }

    /**
     * Flush dan tutup writer saat Activity dihancurkan.
     * Panggil dari onDestroy() di CameraActivity.
     */
    fun close() {
        try {
            realtimeWriter?.flush()
            realtimeWriter?.close()
            realtimeWriter = null
            Log.d("CSVLogger", "CSVLogger closed successfully")
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error closing CSVLogger: ${e.message}")
        }
    }
}