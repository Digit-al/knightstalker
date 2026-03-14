// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DownloadTask(
    val id: String,
    val name: String,
    val url: String,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val filePath: String? = null,
    val error: String? = null
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

class DownloadRepository(private val context: Context) {

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val downloadDir: File
        get() {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "KnightStalker"
            )
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun download(id: String, name: String, streamUrl: String): Result<String> {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        val extension = when {
            streamUrl.contains(".mp4") -> ".mp4"
            streamUrl.contains(".mkv") -> ".mkv"
            streamUrl.contains(".ts") -> ".ts"
            streamUrl.contains(".m3u8") -> ".ts"
            else -> ".mp4"
        }
        val file = File(downloadDir, "$sanitized$extension")

        val task = DownloadTask(id = id, name = name, url = streamUrl, status = DownloadStatus.DOWNLOADING)
        _downloads.update { it + task }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(streamUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    updateTask(id) { it.copy(status = DownloadStatus.FAILED, error = "HTTP ${response.code}") }
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val body = response.body ?: run {
                    updateTask(id) { it.copy(status = DownloadStatus.FAILED, error = "Empty response") }
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val contentLength = body.contentLength()
                var bytesRead = 0L

                FileOutputStream(file).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            // Check cancellation
                            val current = _downloads.value.find { it.id == id }
                            if (current?.status == DownloadStatus.CANCELLED) {
                                file.delete()
                                return@withContext Result.failure(Exception("Cancelled"))
                            }

                            output.write(buffer, 0, read)
                            bytesRead += read

                            if (contentLength > 0) {
                                val progress = bytesRead.toFloat() / contentLength.toFloat()
                                updateTask(id) { it.copy(progress = progress) }
                            }
                        }
                    }
                }

                updateTask(id) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 1f,
                        filePath = file.absolutePath
                    )
                }

                // Notify media scanner
                val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = android.net.Uri.fromFile(file)
                context.sendBroadcast(intent)

                Result.success(file.absolutePath)
            } catch (e: Exception) {
                updateTask(id) { it.copy(status = DownloadStatus.FAILED, error = e.message) }
                file.delete()
                Result.failure(e)
            }
        }
    }

    fun cancelDownload(id: String) {
        updateTask(id) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    fun removeDownload(id: String) {
        _downloads.update { list -> list.filter { it.id != id } }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _downloads.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
