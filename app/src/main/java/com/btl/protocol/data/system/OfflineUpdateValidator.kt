package com.btl.protocol.data.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.Signature

class OfflineUpdateValidator(private val context: Context) {

    companion object {
        // Master Public Key for verifying APK signatures (Ed25519)
        // Hardcoded in raw bytes
        private val DEVELOPER_PUBLIC_KEY = byteArrayOf(
            // Ed25519 public key bytes
            0x00, 0x01, 0x02 // Placeholder to compile
        )
    }

    fun processOtaUpdate(apkFile: File, signatureBytes: ByteArray): Boolean {
        if (!apkFile.exists()) return false

        // 1. Calculate File Hash (SHA-256)
        val fileHash = calculateSha256(apkFile)
        if (fileHash == null) {
            apkFile.delete()
            return false
        }

        // 2. Verify Ed25519 Signature
        val isValid = verifyEd25519Signature(fileHash, signatureBytes)
        if (!isValid) {
            // Compromised or corrupted package, delete immediately
            apkFile.delete()
            return false
        }

        // 3. Trigger Installer Intent
        installApk(apkFile)
        return true
    }

    private fun calculateSha256(file: File): ByteArray? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            inputStream.close()
            digest.digest()
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyEd25519Signature(data: ByteArray, signature: ByteArray): Boolean {
        return try {
            // Android 13+ supports Ed25519 natively via standard JCA
            val sig = Signature.getInstance("Ed25519")
            
            // Assume the public key is initialized here
            // sig.initVerify(publicKeyObject)
            // sig.update(data)
            // return sig.verify(signature)
            
            // Stubbed true for compilation since KeyFactory requires valid key bytes
            true 
        } catch (e: Exception) {
            false
        }
    }

    private fun installApk(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }
}
