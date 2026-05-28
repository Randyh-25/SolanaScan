package com.randy25.leafdiseasedetector

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CSVLogger(private val context: Context) {

    private var file: File? = null
    private val fileName = "leaf_detection_logs.csv"

    init {
        createFile()
    }

    private fun createFile() {
        try {
            // Simpan di folder Documents aplikasi (external files dir)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            file = File(dir, fileName)

            if (!file!!.exists()) {
                val writer = FileWriter(file, true)
                writer.append("Timestamp,Label,Confidence,Latency_ms,CPU_Usage,RAM_Usage_MB\n")
                writer.flush()
                writer.close()
                Log.d("CSVLogger", "CSV file created at: ${file!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error creating CSV file: ${e.message}")
        }
    }

    fun log(result: ClassificationResult, cpuUsage: String, ramUsage: Long) {
        try {
            val writer = FileWriter(file, true)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = sdf.format(Date(result.timestamp))

            val row = "$timestamp,${result.label},${"%.2f".format(result.confidence)},${result.latencyMs},$cpuUsage,$ramUsage\n"
            writer.append(row)
            writer.flush()
            writer.close()
            Log.d("CSVLogger", "Logged to CSV: $row")
        } catch (e: Exception) {
            Log.e("CSVLogger", "Error writing to CSV: ${e.message}")
        }
    }
}
