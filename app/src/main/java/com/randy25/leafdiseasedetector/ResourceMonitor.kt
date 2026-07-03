package com.randy25.leafdiseasedetector

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.util.Locale

class ResourceMonitor(private val context: Context) {

    private var lastAppCpuTime = 0L
    private var lastUptime = 0L

    /**
     * Mendapatkan penggunaan RAM aplikasi (PSS) dalam MB.
     * Sangat akurat karena mencakup Java Heap + Native Heap (tempat TFLite berjalan).
     */
    fun getRamUsage(): Long {
        val debugInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugInfo)
        return (debugInfo.totalPss / 1024).toLong()
    }

    /**
     * Mendapatkan persentase penggunaan CPU menggunakan API resmi Android.
     *
     * Process.getElapsedCpuTime() mengembalikan total waktu CPU yang dipakai
     * proses ini di semua core. Dibagi elapsed realtime menghasilkan persentase
     * penggunaan CPU relatif terhadap kapasitas 1 core (bisa >100% jika multi-thread).
     * Ini adalah cara standar pelaporan CPU per-proses (sama seperti `top` di Linux).
     */
    fun getCpuUsage(): String {
        val appCpuTime = Process.getElapsedCpuTime()
        val uptime = SystemClock.elapsedRealtime()

        if (lastUptime == 0L) {
            lastAppCpuTime = appCpuTime
            lastUptime = uptime
            return "Calculating..."
        }

        val appCpuDelta = appCpuTime - lastAppCpuTime
        val uptimeDelta = uptime - lastUptime

        lastAppCpuTime = appCpuTime
        lastUptime = uptime

        val cpuPercent = if (uptimeDelta > 0) {
            (appCpuDelta.toFloat() / uptimeDelta.toFloat()) * 100f
        } else {
            0f
        }

        return "%.1f".format(Locale.US, cpuPercent)
    }
}