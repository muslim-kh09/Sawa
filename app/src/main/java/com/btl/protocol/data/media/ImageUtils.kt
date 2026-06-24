package com.btl.protocol.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageUtils {
    private const val MAX_DIMENSION = 448
    private const val TARGET_IMAGE_BYTES = 45_000 // 45 KB

    fun processImage(context: Context, uri: Uri): ByteArray? {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close() ?: return null

            val scaledBitmap = scaleBitmap(originalBitmap, MAX_DIMENSION)
            
            var quality = 90
            var compressedData: ByteArray
            
            do {
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressedData = outputStream.toByteArray()
                quality -= 10
            } while (compressedData.size > TARGET_IMAGE_BYTES && quality > 10)

            return compressedData
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
