package com.randy25.leafdiseasedetector

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CSVLogger(private val context: Context) {

    private var realtimeFile: File? = null
    private var staticFile: File? = null

    private val realtimeFileName = "realtime_logs.csv"
    private val staticFileName = "static_logs.csv"

    init {
        val dir = getDocumentsDir()
        realtimeFile = createFile(dir, realtimeFileName)
        staticFile = createFile(dir, staticFileName)
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
     * Data ditulis ke realtime_logs.csv.
     */
    fun logRealtime(result: ClassificationResult, fps: Double, cpuUsage: String, ramUsage: Long) {
        writeRow(realtimeFile, result, fps, cpuUsage, ramUsage, "Realtime_Camera")
    }

    /**
     * Log hasil inferensi dari capture atau galeri.
     * Data ditulis ke static_logs.csv.
     */
    fun logStatic(result: ClassificationResult, cpuUsage: String, ramUsage: Long, imageName: String) {
        writeRow(staticFile, result, 0.0, cpuUsage, ramUsage, imageName)
    }

    private fun writeRow(file: File?, result: ClassificationResult, fps: Double, cpuUsage: String, ramUsage: Long, imageName: String) {
        try {
            val writer = FileWriter(file, true)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = sdf.format(Date(result.timestamp))

            // Menggunakan Locale.US agar nilai desimal menggunakan titik (.)
            val fpsStr = "%.1f".format(Locale.US, fps)
            val row = "$timestamp,$imageName,${result.label},${"%.2f".format(Locale.US, result.confidence)},${result.latencyMs},$fpsStr,$cpuUsage,$ramUsage\n"
            writer.append(row)
            writer.flush()
            writer.close()
            Log.d("CSVLogger", "Logged to ${file?.name}: $row")
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error writing to CSV (${file?.name}): ${e.message}")
        }
    }
}