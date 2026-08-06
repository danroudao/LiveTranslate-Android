package com.example.livetranslate.model

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 模型下载器 —— 对应原项目 model_manager.py 的下载逻辑（ModelDownloadDialog 进度）。
 *
 * 特性：
 *  - 断点续传（Range 头 + 本地已有字节数）
 *  - 多源回退（URL 列表按顺序尝试）
 *  - 大小校验（下载完成后比对预期字节数）
 *  - 下载到内部存储 filesDir（scoped storage 安全，install -r 保留）
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val TIMEOUT_SEC = 300L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** 目标文件路径 */
    fun targetFile(file: ModelFile): File =
        File(context.filesDir, "${file.relDir}/${file.fileName}")

    /** 已下载状态 */
    fun status(file: ModelFile): DownloadStatus {
        val f = targetFile(file)
        return if (f.exists()) {
            if (f.length() >= file.sizeBytes) DownloadStatus.DONE else DownloadStatus.PARTIAL(f.length())
        } else {
            DownloadStatus.NONE
        }
    }

    /**
     * 阻塞下载（调用方放后台线程）。
     * @param onProgress 进度回调 0~1（基于字节）
     * @return 成功与否
     */
    fun download(file: ModelFile, onProgress: (Float) -> Unit): Boolean {
        val target = targetFile(file)
        target.parentFile?.mkdirs()

        var lastError: String? = null
        for (url in file.urls) {
            try {
                if (downloadFrom(url, target, file.sizeBytes, onProgress)) {
                    Log.i(TAG, "下载完成: ${file.fileName} (${target.length()} bytes) ← $url")
                    return true
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "下载失败 $url: ${e.message}")
            }
        }
        Log.e(TAG, "全部下载源失败: ${file.fileName}: $lastError")
        return false
    }

    private fun downloadFrom(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val existing = if (target.exists()) target.length() else 0L
        // 已有完整文件直接成功（校验）
        if (existing >= expectedSize) {
            onProgress(1f)
            return true
        }

        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$existing-")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                // 服务器不支持 Range 时从头下载（覆盖）
                if (resp.code == 200 && existing > 0) {
                    target.delete()
                } else {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
            }
            val body = resp.body ?: throw IllegalStateException("空响应")
            val total = expectedSize
            val raf = RandomAccessFile(target, "rw")
            raf.seek(existing)
            try {
                val buf = ByteArray(64 * 1024)
                var downloaded = existing
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            } finally {
                raf.close()
            }
            if (target.length() < expectedSize) {
                throw IllegalStateException("文件不完整: ${target.length()}/$expectedSize")
            }
            return true
        }
    }

    /** 删除已下载文件 */
    fun delete(file: ModelFile) {
        targetFile(file).delete()
    }

    sealed class DownloadStatus {
        object NONE : DownloadStatus()
        data class PARTIAL(val bytes: Long) : DownloadStatus()
        object DONE : DownloadStatus()
    }
}
