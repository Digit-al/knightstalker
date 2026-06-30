// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val filePath: String? = null,
    val error: String? = null,
    // Live transfer rate while DOWNLOADING (bytes/sec); not persisted.
    val speedBytesPerSec: Long = 0L,
    // Stalker portal cmd + portal base URL. Kept so a fresh play link can be
    // re-minted on resume/retry — portal play tokens are single-use and expire,
    // so the original `url` is dead by the time a download is resumed.
    val cmd: String? = null,
    val portalUrl: String? = null
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

@Serializable
private data class PersistedDownload(
    val id: String,
    val name: String,
    val url: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = "PENDING",
    val filePath: String? = null,
    val error: String? = null,
    val cmd: String? = null,
    val portalUrl: String? = null
)

class DownloadRepository(private val context: Context) {

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Re-resolves a fresh, playable stream URL from (portalUrl, cmd). Wired up in
     * the DI module to the Stalker create_link call. Used to refresh the expiring
     * play token before every download attempt so resume/retry actually work.
     */
    var linkResolver: (suspend (portalUrl: String, cmd: String) -> Result<String>)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
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

    private val persistFile: File
        get() = File(context.filesDir, "downloads.json")

    init {
        loadPersistedDownloads()
    }

    fun startDownload(
        id: String,
        name: String,
        streamUrl: String,
        cmd: String? = null,
        portalUrl: String? = null
    ) {
        // Avoid duplicate
        if (_downloads.value.any { it.id == id && it.status == DownloadStatus.DOWNLOADING }) return

        val task = DownloadTask(
            id = id, name = name, url = streamUrl,
            status = DownloadStatus.DOWNLOADING,
            cmd = cmd, portalUrl = portalUrl
        )
        _downloads.update { list ->
            list.filter { it.id != id } + task
        }
        persist()
        launchDownload(task)
    }

    fun resumeDownload(id: String) {
        val task = _downloads.value.find { it.id == id } ?: return
        if (task.status == DownloadStatus.DOWNLOADING) return
        updateTask(id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
        persist()
        launchDownload(task.copy(status = DownloadStatus.DOWNLOADING, error = null))
    }

    fun pauseDownload(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        updateTask(id) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
        persist()
    }

    fun cancelDownload(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        val task = _downloads.value.find { it.id == id }
        task?.filePath?.let { File(it).delete() }
        updateTask(id) { it.copy(status = DownloadStatus.CANCELLED) }
        persist()
    }

    fun removeDownload(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        _downloads.update { list -> list.filter { it.id != id } }
        persist()
    }

    fun retryDownload(id: String) {
        val task = _downloads.value.find { it.id == id } ?: return
        // Delete partial file and restart from scratch
        task.filePath?.let { File(it).delete() }
        updateTask(id) {
            it.copy(status = DownloadStatus.DOWNLOADING, progress = 0f,
                bytesDownloaded = 0L, totalBytes = 0L, error = null)
        }
        persist()
        val updated = _downloads.value.find { it.id == id } ?: return
        launchDownload(updated)
    }

    private fun launchDownload(task: DownloadTask) {
        val job = scope.launch {
            // IPTV CDNs routinely drop long transfers mid-stream ("unexpected end
            // of stream", socket timeouts). Auto-retry such transient failures,
            // resuming from the partial file each time (with a fresh play link
            // when a resolver is available), so large downloads complete
            // unattended instead of stopping at the first hiccup.
            val maxAttempts = 8
            var attempt = 0
            while (true) {
                try {
                    doDownload(task)
                    return@launch
                } catch (e: CancellationException) {
                    // Paused or cancelled — don't mark as failed
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    Log.e("Download", "Download error (attempt $attempt): ${task.name}", e)
                    if (attempt >= maxAttempts) {
                        updateTask(task.id) {
                            it.copy(status = DownloadStatus.FAILED, error = e.message, speedBytesPerSec = 0L)
                        }
                        persist()
                        return@launch
                    }
                    // Surface the retry in the UI, back off, then resume.
                    updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.DOWNLOADING,
                            error = "Connection dropped — retrying ($attempt/$maxAttempts)…",
                            speedBytesPerSec = 0L
                        )
                    }
                    persist()
                    delay(3000L * attempt)
                }
            }
        }
        jobs[task.id] = job
    }

    private suspend fun doDownload(task: DownloadTask) {
        val sanitized = task.name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        val extension = when {
            task.url.contains(".mp4") -> ".mp4"
            task.url.contains(".mkv") -> ".mkv"
            task.url.contains(".ts") -> ".ts"
            task.url.contains(".m3u8") -> ".ts"
            else -> ".mp4"
        }
        val file = File(downloadDir, "$sanitized$extension")
        updateTask(task.id) { it.copy(filePath = file.absolutePath) }

        // Check for existing partial file to resume
        val existingBytes = if (file.exists()) file.length() else 0L

        // Re-mint a fresh stream URL. Stalker play tokens are single-use and
        // expire, so the stored url is typically dead on resume/retry (the
        // original HTTP 401 failures). Falls back to the stored url when no
        // cmd/portal is available (e.g. legacy tasks).
        val resolver = linkResolver
        val cmd = task.cmd
        val portalUrl = task.portalUrl
        val effectiveUrl = if (resolver != null && cmd != null && portalUrl != null) {
            resolver(portalUrl, cmd).getOrElse { e ->
                updateTask(task.id) {
                    it.copy(status = DownloadStatus.FAILED, error = "Could not refresh link: ${e.message}")
                }
                persist()
                return
            }
        } else {
            task.url
        }
        updateTask(task.id) { it.copy(url = effectiveUrl) }

        val requestBuilder = Request.Builder().url(effectiveUrl)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful && response.code != 206) {
            updateTask(task.id) {
                it.copy(status = DownloadStatus.FAILED, error = "HTTP ${response.code}")
            }
            persist()
            return
        }

        val body = response.body ?: run {
            updateTask(task.id) {
                it.copy(status = DownloadStatus.FAILED, error = "Empty response")
            }
            persist()
            return
        }

        val isResume = response.code == 206
        val contentLength = body.contentLength()
        val totalBytes = if (isResume) existingBytes + contentLength else contentLength
        var bytesRead = if (isResume) existingBytes else 0L

        // Successful (re)connect — clear any prior retry/error message.
        updateTask(task.id) {
            it.copy(
                status = DownloadStatus.DOWNLOADING,
                totalBytes = totalBytes,
                bytesDownloaded = bytesRead,
                error = null
            )
        }

        FileOutputStream(file, isResume).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var lastTickMs = System.currentTimeMillis()
                var lastTickBytes = bytesRead
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()

                    output.write(buffer, 0, read)
                    bytesRead += read

                    // Throttle UI emissions to a few per second and derive the
                    // instantaneous speed from bytes transferred since last tick.
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTickMs
                    if (elapsed >= 350) {
                        val speed = (bytesRead - lastTickBytes) * 1000 / elapsed
                        val progress = if (totalBytes > 0) {
                            bytesRead.toFloat() / totalBytes.toFloat()
                        } else 0f
                        updateTask(task.id) {
                            it.copy(progress = progress, bytesDownloaded = bytesRead, speedBytesPerSec = speed)
                        }
                        lastTickMs = now
                        lastTickBytes = bytesRead
                    }
                }
            }
        }

        updateTask(task.id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                speedBytesPerSec = 0L,
                bytesDownloaded = bytesRead,
                totalBytes = if (totalBytes > 0) totalBytes else bytesRead
            )
        }
        persist()

        // Notify media scanner
        context.sendBroadcast(
            Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
        )
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _downloads.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun persist() {
        try {
            val data = _downloads.value.map { task ->
                PersistedDownload(
                    id = task.id,
                    name = task.name,
                    url = task.url,
                    bytesDownloaded = task.bytesDownloaded,
                    totalBytes = task.totalBytes,
                    status = task.status.name,
                    filePath = task.filePath,
                    error = task.error,
                    cmd = task.cmd,
                    portalUrl = task.portalUrl
                )
            }
            persistFile.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            Log.e("Download", "Failed to persist downloads", e)
        }
    }

    private fun loadPersistedDownloads() {
        try {
            if (!persistFile.exists()) return
            val data = json.decodeFromString<List<PersistedDownload>>(persistFile.readText())
            val tasks = data.mapNotNull { p ->
                val status = try { DownloadStatus.valueOf(p.status) } catch (_: Exception) { return@mapNotNull null }
                // Don't restore cancelled/completed without a file
                if (status == DownloadStatus.CANCELLED) return@mapNotNull null
                val progress = if (p.totalBytes > 0) p.bytesDownloaded.toFloat() / p.totalBytes else 0f
                DownloadTask(
                    id = p.id,
                    name = p.name,
                    url = p.url,
                    progress = progress,
                    bytesDownloaded = p.bytesDownloaded,
                    totalBytes = p.totalBytes,
                    // Mark in-progress downloads as paused on restart
                    status = if (status == DownloadStatus.DOWNLOADING) DownloadStatus.PAUSED else status,
                    filePath = p.filePath,
                    error = p.error,
                    cmd = p.cmd,
                    portalUrl = p.portalUrl
                )
            }
            _downloads.value = tasks
        } catch (e: Exception) {
            Log.e("Download", "Failed to load persisted downloads", e)
        }
    }
}
