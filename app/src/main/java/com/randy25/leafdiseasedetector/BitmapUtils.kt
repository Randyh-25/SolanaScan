package com.randy25.leafdiseasedetector

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object BitmapUtils {

    /**
     * Mengkonversi ImageProxy menjadi Bitmap menggunakan fungsi bawaan CameraX.
     * Secara otomatis mendukung ImageAnalysis (YUV_420_888) dan ImageCapture (JPEG).
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        return image.toBitmap()
    }

    /**
     * Mengubah ukuran Bitmap ke dimensi target (contoh: 224x224 untuk model klasifikasi).
     */
    fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Memutar Bitmap sesuai dengan derajat rotasi sensor kamera perangkat.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}