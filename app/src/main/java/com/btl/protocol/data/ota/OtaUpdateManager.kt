package com.btl.protocol.data.ota

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles Over-The-Air (OTA) updates by checking the GitHub Releases API.
 */
object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/muslim-kh09/Sawa/releases/latest"

    data class UpdateInfo(val versionName: String, val downloadUrl: String, val releaseNotes: String)

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            // Optional: short timeout so we don't block forever if offline
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name").replace("v", "") // e.g. "1.0.1"
                val body = json.optString("body", "No release notes available.")
                val assets = json.getJSONArray("assets")
                
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (downloadUrl.isNotEmpty() && isNewerVersion(currentVersion, tagName)) {
                    return@withContext UpdateInfo(tagName, downloadUrl, body)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates (probably offline)", e)
        }
        return@withContext null
    }

    private fun isNewerVersion(current: String, remote: String): Boolean {
        // Simple semantic versioning check
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(currentParts.size, remoteParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (c > r) return false
        }
        return false
    }

    fun downloadAndInstall(context: Context, url: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
            .setTitle("Sawa Update")
            .setDescription("Downloading latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "sawa-update.apk")

        val downloadId = downloadManager.enqueue(request)

        // Listen for download completion
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(ctx)
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(context: Context) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "sawa-update.apk")
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        context.startActivity(intent)
    }
}
