package com.randy25.leafdiseasedetector

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.RandomAccessFile

class ResourceMonitor(private val context: Context) {

    private var lastCpuTime: Long = 0
    private var lastAppTime: Long = 0

    /**
     * Mendapatkan penggunaan RAM aplikasi dalam MB
     */
    fun getRamUsage(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        val usedMemInBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedMemInBytes / (1024 * 1024)
    }

    /**
     * Mendapatkan persentase penggunaan CPU (Estimasi sederhana)
     */
    fun getCpuUsage(): String {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            var load = reader.readLine()
            val toks = load.split(" +".toRegex())
            val idle1 = toks[4].toLong()
            val cpu1 = toks[2].toLong() + toks[3].toLong() + toks[5].toLong() + 
                       toks[6].toLong() + toks[7].toLong() + toks[8].toLong()
            
            try { Thread.sleep(360) } catch (e: Exception) {}
            
            reader.seek(0)
            load = reader.readLine()
            reader.close()
            val toks2 = load.split(" +".toRegex())
            val idle2 = toks2[4].toLong()
            val cpu2 = toks2[2].toLong() + toks2[3].toLong() + toks2[5].toLong() + 
                       toks2[6].toLong() + toks2[7].toLong() + toks2[8].toLong()
            
            val percentage = (cpu2 - cpu1).toFloat() / ((cpu2 + idle2) - (cpu1 + idle1))
            "${"%.1f".format(percentage * 100)}%"
        } catch (e: Exception) {
            "N/A"
        }
    }
}
