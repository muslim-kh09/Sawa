package com.btl.protocol.data.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageUtils {
    private const val MAX_DIMENSION = 448
    private const val TARGET_IMAGE_BYTES = 45_000

    fun processImage(context: Context, uri: Uri): ByteArray? {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close() ?: return null

            val width = originalBitmap.width
            val height = originalBitmap.height
            
            val scaledBitmap = if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                val ratio = minOf(MAX_DIMENSION.toFloat() / width, MAX_DIMENSION.toFloat() / height)
                Bitmap.createScaledBitmap(originalBitmap, (width * ratio).toInt(), (height * ratio).toInt(), true)
            } else {
                originalBitmap
            }
            
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
}
