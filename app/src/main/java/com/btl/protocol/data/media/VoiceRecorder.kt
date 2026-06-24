package com.btl.protocol.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File? {
        try {
            outputFile = File.createTempFile("voice_", ".amr", context.cacheDir)
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // AMR_NB provides excellent voice compression for low bandwidth BLE
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                // 8000 Hz, 12.2 kbps = extremely small file
                setAudioSamplingRate(8000)
                setAudioEncodingBitRate(12200) 
                
                setOutputFile(outputFile?.absolutePath)
                
                // Limit to 10 seconds exactly as requested to prevent large files
                setMaxDuration(10000)
                
                prepare()
                start()
            }
            return outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            return null
        }
    }

    fun stopRecording(): ByteArray? {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            // Can happen if stopped immediately after starting
            e.printStackTrace()
            release()
            return null
        }
        
        release()
        
        // Read file bytes
        val file = outputFile ?: return null
        if (!file.exists() || file.length() == 0L) return null
        
        val bytes = ByteArray(file.length().toInt())
        try {
            FileInputStream(file).use { it.read(bytes) }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        
        return bytes
    }
    
    fun release() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {}
        mediaRecorder = null
    }
}
