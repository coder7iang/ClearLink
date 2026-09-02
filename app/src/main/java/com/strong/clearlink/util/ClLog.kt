package com.strong.clearlink.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ClearLink 统一日志，过滤 tag：ClearLink；同时写入页面可复制缓冲 */
object ClLog {
    const val TAG = "ClearLink"
    private const val MAX_LINES = 80

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun clear() {
        _lines.value = emptyList()
    }

    fun dumpText(): String = _lines.value.joinToString("\n")

    fun i(step: String, msg: String) {
        val line = format("I", step, msg)
        // 用 E 级别，避免 Logcat 默认过滤掉 Debug/Info
        Log.e(TAG, line)
        append(line)
    }

    fun d(step: String, msg: String) {
        val line = format("D", step, msg)
        Log.e(TAG, line)
        append(line)
    }

    fun w(step: String, msg: String, t: Throwable? = null) {
        val line = format("W", step, msg)
        if (t == null) Log.e(TAG, line) else Log.e(TAG, line, t)
        append(line)
        t?.message?.let { append(format("W", step, "cause=$it")) }
    }

    fun e(step: String, msg: String, t: Throwable? = null) {
        val line = format("E", step, msg)
        if (t == null) Log.e(TAG, line) else Log.e(TAG, line, t)
        append(line)
        t?.cause?.let { append(format("E", step, "cause=${it.javaClass.simpleName}: ${it.message}")) }
    }

    private fun format(level: String, step: String, msg: String): String =
        "${timeFmt.format(Date())} $level [$step] $msg"

    private fun append(line: String) {
        _lines.update { old ->
            val next = old + line
            if (next.size <= MAX_LINES) next else next.takeLast(MAX_LINES)
        }
    }

    /** 缩短 URL，保留 host + path 关键段 + 关键 query */
    fun shortUrl(url: String?, max: Int = 180): String {
        if (url.isNullOrBlank()) return "(empty)"
        val noQuery = url.substringBefore('?')
        val query = url.substringAfter('?', missingDelimiterValue = "")
        val keepKeys = listOf("mime_type", "video_id", "media_type", "btag")
        val q = if (query.isEmpty()) {
            ""
        } else {
            query.split('&')
                .filter { part -> keepKeys.any { part.startsWith("$it=") } }
                .joinToString("&")
                .let { if (it.isEmpty()) "" else "?$it" }
        }
        val s = noQuery + q
        return if (s.length <= max) s else s.take(max) + "…"
    }

    fun urlKind(url: String?): String {
        if (url.isNullOrBlank()) return "empty"
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return when {
            u.startsWith("file:") -> "file"
            path.contains("/share/video/") ||
                path.contains("/share/note/") ||
                Regex("""douyin\.com/(?:video|note)/\d+""").containsMatchIn(path) -> "html-page"
            u.contains(".m3u8") -> "hls"
            u.contains(".m4a") || u.contains(".mp3") || u.contains(".aac") -> "audio"
            u.contains("mime_type=video_mp4") -> "mp4-query"
            u.contains(".mp4") -> "mp4"
            u.contains("/ufile/atlas/") &&
                (u.contains(".jpg") || u.contains(".jpeg") || u.contains(".png") || u.contains(".webp")) -> "atlas-img"
            u.contains("douyinvod") || u.contains("bytevod") || u.contains("vlabvod") -> "cdn"
            u.contains("/aweme/") || u.contains("/web/api/") -> "api"
            u.contains("playwm") -> "watermark"
            else -> "other"
        }
    }
}
