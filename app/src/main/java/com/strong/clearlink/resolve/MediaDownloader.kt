package com.strong.clearlink.resolve

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object MediaDownloader {
    private const val TAG = "ClearLink"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    suspend fun downloadVideoToGallery(
        context: Context,
        url: String,
        displayName: String,
        platform: ShortVideoPlatform,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        if (!ShortVideoResolveService.isDownloadableVideoUrl(url)) {
            error("无效的下载地址")
        }
        val tmp = File(context.cacheDir, "dl_${System.currentTimeMillis()}.mp4")
        try {
            downloadToFile(url, tmp, platform, onProgress)
            assertValidMp4(tmp)
            saveVideoToMediaStore(context, tmp, displayName)
        } finally {
            tmp.delete()
        }
    }

    suspend fun downloadImagesToGallery(
        context: Context,
        urls: List<String>,
        baseName: String,
        platform: ShortVideoPlatform,
    ): Int = withContext(Dispatchers.IO) {
        var saved = 0
        urls.forEachIndexed { index, url ->
            if (!ShortVideoResolveService.isDownloadableImageUrl(url) && !looksLikeImage(url)) {
                return@forEachIndexed
            }
            val ext = when {
                url.contains(".png", true) -> "png"
                url.contains(".webp", true) -> "webp"
                else -> "jpg"
            }
            val mime = when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val tmp = File(context.cacheDir, "img_${System.currentTimeMillis()}_$index.$ext")
            try {
                downloadToFile(url, tmp, platform, null)
                if (tmp.length() < 512) return@forEachIndexed
                saveImageToMediaStore(context, tmp, "${baseName}_${index + 1}.$ext", mime)
                saved++
            } finally {
                tmp.delete()
            }
        }
        saved
    }

    private fun downloadToFile(
        url: String,
        file: File,
        platform: ShortVideoPlatform,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val isKs = url.contains("kwaicdn") ||
            url.contains("yximgs") ||
            url.contains("kwimgs") ||
            url.contains("ndcimgs")
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                if (isKs) ShortVideoResolveService.MOBILE_USER_AGENT
                else ShortVideoResolveService.USER_AGENT,
            )
            .header("Referer", ShortVideoResolveService.refererFor(platform))
            .header("Accept", "*/*")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败，HTTP ${response.code}")
            val body = response.body ?: error("下载失败，空响应")
            val total = body.contentLength()
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(16 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        onProgress?.invoke(received, total)
                    }
                }
            }
        }
    }

    private fun assertValidMp4(file: File) {
        if (!file.exists()) error("文件不存在")
        if (file.length() < 8 * 1024) error("文件过小（${file.length()}B），不是有效视频")
        val head = ByteArray(64)
        val read = file.inputStream().use { input ->
            var offset = 0
            while (offset < head.size) {
                val n = input.read(head, offset, head.size - offset)
                if (n <= 0) break
                offset += n
            }
            offset
        }
        val isMp4 = read >= 8 && String(head, 4, 4) == "ftyp"
        if (!isMp4) {
            val text = String(head, 0, minOf(40, read))
            error("下载结果不是 MP4（文件头: $text…），请重新解析")
        }
    }

    private fun saveVideoToMediaStore(context: Context, file: File, displayName: String): String {
        val name = if (displayName.endsWith(".mp4", true)) displayName else "$displayName.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClearLink")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入相册")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("无法写入相册")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        Log.d(TAG, "saved video $uri")
        return uri.toString()
    }

    private fun saveImageToMediaStore(context: Context, file: File, displayName: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ClearLink")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入相册")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("无法写入相册")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun looksLikeImage(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("douyinpic.com") ||
            u.contains("yximgs.com") ||
            u.contains("kwimgs.com") ||
            u.contains(".jpeg") ||
            u.contains(".jpg") ||
            u.contains(".png") ||
            u.contains("tplv-")
    }
}
