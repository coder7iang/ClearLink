package com.strong.clearlink.resolve

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 抖音 / 快手分享链接解析（逻辑对齐 AdbPlayer DouyinResolveService）。
 * 抖音主路径走 WebView Hook；快手优先 HTTP，失败再 WebView。
 */
object ShortVideoResolveService {
    private const val TAG = "ClearLink"

    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
            "Version/16.6 Mobile/15E148 Safari/604.1"

    private val douyinShareUrlRe = Pattern.compile(
        """https?://(?:""" +
            """v\.douyin\.com/[A-Za-z0-9_-]+/?""" +
            """|(?:www\.)?douyin\.com/(?:video|note)/\d+[^\s]*""" +
            """|(?:www\.)?iesdouyin\.com/share/(?:note|video)/\d+[^\s]*""" +
            """)""",
        Pattern.CASE_INSENSITIVE,
    )

    private val kuaishouShareUrlRe = Pattern.compile(
        """https?://(?:""" +
            """v\.kuaishou\.com/[A-Za-z0-9_-]+/?""" +
            """|v\.m\.chenzhongtech\.com/[^\s]*""" +
            """|(?:www\.)?kuaishou\.com/[^\s]*""" +
            """|m\.gifshow\.com/[^\s]*""" +
            """)""",
        Pattern.CASE_INSENSITIVE,
    )

    private val awemeIdRe = Pattern.compile("""/video/(\d+)""")
    private val noteIdRe = Pattern.compile("""/(?:share/)?note/(\d+)""")
    private val photoIdRe = Pattern.compile("""(?:/photo/|photoId=|/short-video/)([A-Za-z0-9_-]+)""")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 不跟随重定向，用于短链解析 Location */
    private val noRedirectClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun detectPlatform(text: String): ShortVideoPlatform? {
        val hasKs = kuaishouShareUrlRe.matcher(text).find() ||
            text.contains("kuaishou.com") ||
            text.contains("chenzhongtech.com") ||
            text.contains("gifshow.com") ||
            text.contains("快手")
        if (hasKs) {
            val ks = extractKuaishouShareUrl(text)
            val dy = extractDouyinShareUrl(text)
            if (ks != null && dy == null) return ShortVideoPlatform.KUAISHOU
            if (dy != null && ks == null) return ShortVideoPlatform.DOUYIN
            if (ks != null) return ShortVideoPlatform.KUAISHOU
            if (text.contains("快手") && !text.contains("抖音")) {
                return ShortVideoPlatform.KUAISHOU
            }
        }
        if (douyinShareUrlRe.matcher(text).find() ||
            text.contains("douyin.com") ||
            text.contains("iesdouyin.com") ||
            text.contains("抖音") ||
            text.contains("Dou音")
        ) {
            return ShortVideoPlatform.DOUYIN
        }
        return null
    }

    fun extractDouyinShareUrl(text: String): String? {
        val m = douyinShareUrlRe.matcher(text)
        return if (m.find()) m.group() else null
    }

    fun extractKuaishouShareUrl(text: String): String? {
        val m = kuaishouShareUrlRe.matcher(text)
        return if (m.find()) m.group() else null
    }

    /** PC 详情页，易触发滑块；优先用下方移动端分享页 */
    fun videoPageUrl(awemeId: String) = "https://www.douyin.com/video/$awemeId"
    fun notePageUrl(noteId: String) = "https://www.douyin.com/note/$noteId"

    /** 移动端分享页，通常比 PC 页少弹人机验证 */
    fun videoSharePageUrl(awemeId: String) = "https://www.iesdouyin.com/share/video/$awemeId"
    fun noteSharePageUrl(noteId: String) = "https://www.iesdouyin.com/share/note/$noteId"
    fun kuaishouVideoPageUrl(photoId: String) = "https://www.kuaishou.com/short-video/$photoId"
    fun kuaishouPhotoPageUrl(photoId: String) = "https://v.m.chenzhongtech.com/fw/photo/$photoId"

    fun isDouyinNote(text: String): Boolean {
        if (noteIdRe.matcher(text).find()) return true
        if (text.contains("/share/note/")) return true
        if (text.contains("schema_type=37")) return true
        // 分享文案常写「图文作品」，短链本身不一定带 /note/
        if (text.contains("图文作品") || text.contains("图文")) return true
        return false
    }

    fun isKuaishouAtlasUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("subbiz=browse_slide_photo") ||
            u.contains("shareresourcetype=photo_other") ||
            u.contains("shareresourcetype=photo") ||
            u.contains("browse_slide_photo")
    }

    /** 快手长视频 / 短视频页，不是图集 */
    fun isKuaishouVideoPageUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/long-video/") ||
            u.contains("/short-video/") ||
            u.contains("subbiz=longvideo") ||
            u.contains("sharetype=longvideo")
    }

    fun isKuaishouPhotoAtlasPageUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/fw/photo/") || isKuaishouAtlasUrl(u)
    }

    fun refererFor(platform: ShortVideoPlatform): String =
        if (platform == ShortVideoPlatform.KUAISHOU) {
            "https://www.kuaishou.com/"
        } else {
            "https://www.douyin.com/"
        }

    fun isDownloadableVideoUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("blob:")) return false
        if (u.contains("playwm")) return false
        if (!u.startsWith("http")) return false
        // 分享页 / 详情页 HTML，绝不能当媒体直链
        if (looksLikeHtmlPageUrl(u)) return false
        if (looksLikeApiJsonUrl(u)) return false

        val hostOk = u.contains("douyinvod.com") ||
            u.contains("bytevod") ||
            u.contains("vlabvod") ||
            u.contains("365yg.com") ||
            u.contains("kwaicdn.com") ||
            (u.contains("yximgs.com") && u.contains(".mp4")) ||
            (u.contains("kwimgs.com") && u.contains(".mp4")) ||
            u.contains("ndcimgs.com") ||
            (u.contains("chenzhongtech.com") && (u.contains(".mp4") || u.contains("mime_type=video"))) ||
            (u.contains("mime_type=video_mp4") && u.contains("/video/")) ||
            (u.contains("media-video") && u.contains("http")) ||
            u.contains(".m3u8") ||
            (u.contains(".mp4") && (u.contains("photo-video") || u.contains("/upic/") || u.contains("tos-"))) ||
            // 播放跳转接口（最终 302 到 CDN），不是 /share/video 页面
            (u.contains("douyin.com") && u.contains("/aweme/") && u.contains("/play")) ||
            (u.contains("snssdk.com") && u.contains("/play")) ||
            (u.contains("iesdouyin.com") && u.contains("/play"))
        if (!hostOk) return false
        if (u.contains("media-audio")) return false
        if (u.contains(".js") && !u.contains("mime_type=video_mp4")) return false
        return true
    }

    /** 网页壳子地址（分享页/作品页），内容是 HTML 不是视频流 */
    fun looksLikeHtmlPageUrl(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        if (u.contains(".mp4") || u.contains(".m3u8")) return false
        return u.contains("/share/video/") ||
            u.contains("/share/note/") ||
            u.contains("iesdouyin.com/share/") ||
            Regex("""douyin\.com/(?:video|note)/\d+""").containsMatchIn(u) ||
            u.endsWith("douyin.com") ||
            u.endsWith("douyin.com/") ||
            u.endsWith("iesdouyin.com") ||
            u.endsWith("iesdouyin.com/")
    }

    /** 详情/列表等 JSON API，不是可解码的视频字节流 */
    fun looksLikeApiJsonUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains(".m3u8") || u.contains(".mp4") || u.contains("mime_type=video")) return false
        if (u.contains("/play")) return false
        return u.contains("/aweme/v1/web/") ||
            u.contains("/aweme/detail") ||
            u.contains("iteminfo") ||
            u.contains("/web/api/") ||
            u.contains("aweme_detail") ||
            u.contains("/aweme/v1/")
    }

    /** 快手图集目录下也会有 .m4a 配乐，不能一律当图片 */
    fun isLikelyRasterImageUrl(url: String): Boolean {
        val path = url.trim().substringBefore('?').lowercase()
        if (path.isEmpty()) return false
        if (path.endsWith(".m4a") ||
            path.endsWith(".mp3") ||
            path.endsWith(".aac") ||
            path.endsWith(".mp4") ||
            path.endsWith(".mov") ||
            path.endsWith(".wav") ||
            path.endsWith(".m3u8")
        ) {
            return false
        }
        return path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".bmp") ||
            path.endsWith(".heic")
    }

    fun isDownloadableImageUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty() || !u.startsWith("http")) return false
        if (u.contains("avatar") ||
            u.contains("100x100") ||
            u.contains("aweme_comment") ||
            u.contains("sticker") ||
            u.contains("pcweb_cover") ||
            u.contains("noop.jpeg") ||
            u.contains("image-cut-tos") ||
            u.contains("uhead") ||
            u.contains("emotion")
        ) {
            return false
        }
        // 必须是栅格图；atlas 下的 m4a 配乐直接排除
        if (!isLikelyRasterImageUrl(u)) return false
        if (u.contains("/ufile/atlas/")) return true
        if ((u.contains("yximgs.com") || u.contains("kwimgs.com")) && u.contains("/ufile/")) {
            return true
        }
        if (!u.contains("douyinpic.com")) return false
        return u.contains("biz_tag=aweme_images") ||
            u.contains("tplv-dy-aweme-images") ||
            u.contains("aweme-images") ||
            u.contains("tos-cn-i-") ||
            u.contains("origin_cover") ||
            u.contains("obj/") ||
            u.contains("~tplv")
    }

    /**
     * 抖音图文选链：优先无「抖音号」水印的原图。
     * 经验：url_list 末项通常干净；download_url_list / 带 watermark 的常有水印。
     */
    fun scoreDouyinImageUrl(url: String): Int {
        val u = url.lowercase()
        var s = 0
        if (u.contains("douyinpic.com")) s += 10
        if (u.contains("biz_tag=aweme_images") || u.contains("aweme-images")) s += 20
        if (u.contains("obj/")) s += 15
        if (u.contains("origin")) s += 10
        // 展示缩略 / 带水印下载链
        if (u.contains("download")) s -= 25
        if (u.contains("watermark") || u.contains("wm_")) s -= 80
        if (u.contains("360p") || u.contains("480p") || u.contains(":360:") || u.contains(":480:")) s -= 15
        if (u.contains("100x100") || u.contains("avatar")) s -= 100
        // 大图模板略加分
        if (u.contains("tplv") && u.contains("jpeg")) s += 5
        return s
    }

    fun preferCleanerImageUrl(a: String, b: String): Boolean =
        scoreDouyinImageUrl(a) > scoreDouyinImageUrl(b)

    /** 同图不同 CDN/模板时保留更高分的一条 */
    fun dedupePreferCleanImages(urls: Iterable<String>): List<String> {
        fun keyOf(u: String): String {
            val path = u.substringBefore('?')
            // 尽量按对象 id 归并，避免同图多版本并存
            val m = Regex("""/(tos-cn-[^/]+/[^/~]+)""").find(path)
                ?: Regex("""/obj/([^\s/?]+)""").find(path)
            return m?.groupValues?.getOrNull(1) ?: path
        }
        val best = linkedMapOf<String, String>()
        for (u in urls) {
            if (!isDownloadableImageUrl(u)) continue
            val key = keyOf(u)
            val old = best[key]
            if (old == null || preferCleanerImageUrl(u, old)) {
                best[key] = u
            }
        }
        return best.values.toList()
    }

    fun sortKuaishouAtlasUrls(urls: Iterable<String>): List<String> {
        val indexRe = Pattern.compile("""_(\d+)\.(?:jpg|jpeg|png|webp)""", Pattern.CASE_INSENSITIVE)
        fun indexOf(u: String): Int {
            val path = u.substringBefore('?')
            val m = indexRe.matcher(path)
            return if (m.find()) m.group(1)?.toIntOrNull() ?: 9999 else 9999
        }
        return urls.distinctBy { it.substringBefore('?') }.sortedBy { indexOf(it) }
    }

    /**
     * 快手图集常按 `xxx_0.jpg`、`xxx_1.jpg` 递增。
     * 隐藏 WebView 往往只懒加载首张，用 HEAD 探测补全后续帧。
     */
    suspend fun expandKuaishouAtlasSequence(
        seedUrls: Collection<String>,
        maxProbe: Int = 40,
    ): List<String> = withContext(Dispatchers.IO) {
        val seeds = sortKuaishouAtlasUrls(seedUrls.filter { isDownloadableImageUrl(it) })
        if (seeds.isEmpty()) return@withContext emptyList()

        val seqRe = Pattern.compile(
            """^(https://.+/ufile/atlas/.+_)(\d+)(\.(?:jpe?g|png|webp))(\?.*)?$""",
            Pattern.CASE_INSENSITIVE,
        )
        val found = linkedSetOf<String>()
        found.addAll(seeds)

        val template = seeds.firstOrNull { seqRe.matcher(it.substringBefore('?')).matches() || seqRe.matcher(it).matches() }
            ?: seeds.first()
        val m = seqRe.matcher(template)
        if (!m.matches()) return@withContext seeds

        val prefix = m.group(1)!!
        val ext = m.group(3)!!
        val query = m.group(4).orEmpty()
        var miss = 0
        for (i in 0 until maxProbe) {
            val candidate = "$prefix$i$ext$query"
            if (found.any { it.substringBefore('?') == candidate.substringBefore('?') }) {
                miss = 0
                continue
            }
            if (probeUrlExists(candidate)) {
                found.add(candidate)
                miss = 0
            } else {
                miss++
                // 连续 miss 几次认为已到末尾（允许个别缺号）
                if (miss >= 2 && i > 0) break
            }
        }
        sortKuaishouAtlasUrls(found)
    }

    private fun probeUrlExists(url: String): Boolean {
        return try {
            httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("Referer", "https://www.kuaishou.com/")
                    .build(),
            ).execute().use { res ->
                if (res.isSuccessful) {
                    val ct = res.header("Content-Type").orEmpty().lowercase()
                    !ct.contains("text/html") && !ct.contains("application/json")
                } else {
                    // 部分 CDN 禁 HEAD，再试 Range GET
                    httpClient.newCall(
                        Request.Builder()
                            .url(url)
                            .get()
                            .header("User-Agent", MOBILE_USER_AGENT)
                            .header("Referer", "https://www.kuaishou.com/")
                            .header("Range", "bytes=0-0")
                            .build(),
                    ).execute().use { getRes ->
                        getRes.isSuccessful || getRes.code == 206
                    }
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun resolveAwemeId(input: String): String = withContext(Dispatchers.IO) {
        val shareUrl = extractDouyinShareUrl(input) ?: input.trim()
        if (shareUrl.isEmpty()) error("未找到抖音分享链接")

        awemeIdRe.matcher(shareUrl).let { if (it.find()) return@withContext it.group(1)!! }

        var current = shareUrl
        repeat(8) {
            awemeIdRe.matcher(current).let { if (it.find()) return@withContext it.group(1)!! }
            val response = noRedirectClient.newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .get()
                    .build(),
            ).execute()
            response.use { res ->
                val location = res.header("Location")
                if (location.isNullOrEmpty()) {
                    val real = res.request.url.toString()
                    awemeIdRe.matcher(real).let { m ->
                        if (m.find()) return@withContext m.group(1)!!
                    }
                    return@repeat
                }
                current = java.net.URI(current).resolve(location).toString()
            }
        }
        error("无法从链接解析视频 ID，请换一条分享链接重试")
    }

    suspend fun resolveNoteId(input: String): String = withContext(Dispatchers.IO) {
        val noteUrlRe = Pattern.compile(
            """https?://(?:www\.)?(?:iesdouyin|douyin)\.com/(?:share/)?note/\d+[^\s]*""",
            Pattern.CASE_INSENSITIVE,
        )
        var shareUrl = noteUrlRe.matcher(input).let { if (it.find()) it.group() else null }
            ?: extractDouyinShareUrl(input)
            ?: input.trim()
        if (shareUrl.isEmpty()) error("未找到抖音图文链接")

        noteIdRe.matcher(shareUrl).let { if (it.find()) return@withContext it.group(1)!! }

        var current = shareUrl
        repeat(8) {
            noteIdRe.matcher(current).let { if (it.find()) return@withContext it.group(1)!! }
            val response = noRedirectClient.newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .get()
                    .build(),
            ).execute()
            response.use { res ->
                val location = res.header("Location")
                if (location.isNullOrEmpty()) {
                    val real = res.request.url.toString()
                    noteIdRe.matcher(real).let { m ->
                        if (m.find()) return@withContext m.group(1)!!
                    }
                    return@repeat
                }
                current = java.net.URI(current).resolve(location).toString()
            }
        }
        error("无法从链接解析图文 ID，请换一条分享链接重试")
    }

    /**
     * 图文短链常跳到 /share/video/{id}，note 路径解析失败时回退 awemeId。
     */
    suspend fun resolveDouyinMediaIdForNote(input: String): String = withContext(Dispatchers.IO) {
        runCatching { resolveNoteId(input) }.getOrElse {
            resolveAwemeId(input)
        }
    }

    suspend fun resolveKuaishouMeta(input: String): KuaishouResolveMeta = withContext(Dispatchers.IO) {
        val shareUrl = extractKuaishouShareUrl(input) ?: error("未找到快手分享链接")
        val response = httpClient.newCall(
            Request.Builder()
                .url(shareUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Referer", "https://www.kuaishou.com/")
                .get()
                .build(),
        ).execute()
        response.use { res ->
            val html = res.body?.string().orEmpty()
            val finalUrl = res.request.url.toString()
            val photoId = photoIdRe.matcher(finalUrl).let { if (it.find()) it.group(1) else null }
                ?: photoIdRe.matcher(shareUrl).let { if (it.find()) it.group(1) else null }
                ?: photoIdRe.matcher(html).let { if (it.find()) it.group(1) else null }
            if (photoId.isNullOrEmpty()) {
                error("无法从链接解析快手作品 ID，请换一条分享链接重试")
            }
            val atlasRe = Pattern.compile(
                """https://[^"\\\s<>]+/ufile/atlas/[^"\\\s<>]+\.(?:jpe?g|png|webp|bmp)[^"\\\s<>]*""",
                Pattern.CASE_INSENSITIVE,
            )
            val hasAtlasImagesInHtml = atlasRe.matcher(html).find()
            val isVideoPage = isKuaishouVideoPageUrl(finalUrl)
            val isAtlas = !isVideoPage && (
                isKuaishouPhotoAtlasPageUrl(finalUrl) ||
                    hasAtlasImagesInHtml ||
                    html.lowercase().contains("browse_slide_photo") ||
                    (input.contains("图文") && !html.contains(".mp4"))
                )
            Log.d(TAG, "kuaishou meta id=$photoId atlas=$isAtlas videoPage=$isVideoPage")
            KuaishouResolveMeta(photoId = photoId, finalUrl = finalUrl, isAtlas = isAtlas)
        }
    }

    suspend fun tryResolveKuaishouHttp(input: String): DouyinResolveResult? = withContext(Dispatchers.IO) {
        val shareUrl = extractKuaishouShareUrl(input) ?: return@withContext null
        Log.d(TAG, "kuaishou http shareUrl=$shareUrl")
        val response = httpClient.newCall(
            Request.Builder()
                .url(shareUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Referer", "https://www.kuaishou.com/")
                .get()
                .build(),
        ).execute()
        response.use { res ->
            val html = res.body?.string().orEmpty()
            val finalUrl = res.request.url.toString()
            val photoId = photoIdRe.matcher(finalUrl).let { if (it.find()) it.group(1) else null }
                ?: photoIdRe.matcher(html).let { if (it.find()) it.group(1) else null }
                ?: ""

            val atlasRe = Pattern.compile(
                """https://[^"\\\s<>]+/ufile/atlas/[^"\\\s<>]+\.(?:jpe?g|png|webp|bmp)[^"\\\s<>]*""",
                Pattern.CASE_INSENSITIVE,
            )
            val atlasFromHtml = mutableListOf<String>()
            val am = atlasRe.matcher(html)
            while (am.find()) {
                val u = am.group()
                if (isDownloadableImageUrl(u)) atlasFromHtml.add(u)
            }
            val atlasUrls = sortKuaishouAtlasUrls(atlasFromHtml.toSet())
            val isVideoPage = isKuaishouVideoPageUrl(finalUrl)
            if (!isVideoPage && (atlasUrls.isNotEmpty() || isKuaishouPhotoAtlasPageUrl(finalUrl))) {
                if (atlasUrls.isNotEmpty()) {
                    val title = pickJsonString(html, listOf("caption", "shareTitle", "photoDescription", "title"))
                        .orEmpty()
                    return@withContext DouyinResolveResult(
                        mediaId = photoId.ifEmpty { "kuaishou" },
                        title = title,
                        coverUrl = atlasUrls.first(),
                        source = "kuaishou/http",
                        platform = ShortVideoPlatform.KUAISHOU,
                        mediaType = DouyinMediaType.IMAGES,
                        imageUrls = atlasUrls,
                    )
                }
                return@withContext null
            }

            val initMatch = Pattern.compile(
                """window\.INIT_STATE\s*=\s*(\{[\s\S]*?\})\s*;?\s*</script>""",
            ).matcher(html)
            if (!initMatch.find()) return@withContext null
            val blob = try {
                val json = JSONObject(initMatch.group(1)!!)
                json.toString()
            } catch (_: Exception) {
                return@withContext null
            }

            val title = pickJsonString(blob, listOf("caption", "shareTitle", "photoDescription", "title"))
                .orEmpty()
            val mp4Re = Pattern.compile("""https://[^"\\]+\.mp4[^"\\]*""")
            val mp4Urls = mutableListOf<String>()
            val mm = mp4Re.matcher(blob)
            while (mm.find()) mp4Urls.add(mm.group())
            if (mp4Urls.isEmpty()) return@withContext null
            val videoUrl = pickBestKuaishouUrl(mp4Urls.distinct()) ?: return@withContext null
            if (!isDownloadableVideoUrl(videoUrl)) return@withContext null

            var cover: String? = null
            val coverRe = Pattern.compile(
                """https://[^"\\]+\.(?:jpg|jpeg|png|webp)[^"\\]*""",
                Pattern.CASE_INSENSITIVE,
            )
            val cm = coverRe.matcher(blob)
            while (cm.find()) {
                val c = cm.group()
                if (c.contains("emotion") || c.contains("uhead")) continue
                if (c.contains("upic") || c.contains("clientCacheKey") || c.contains("heif") || c.contains("jpg")) {
                    cover = c
                    break
                }
            }

            DouyinResolveResult(
                mediaId = photoId.ifEmpty { "kuaishou" },
                videoUrl = videoUrl,
                title = title,
                coverUrl = cover,
                source = "kuaishou/http",
                platform = ShortVideoPlatform.KUAISHOU,
            )
        }
    }

    fun decodeJsJson(raw: String?): Map<String, Any?>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        var text = raw.trim()
        // WebView evaluateJavascript 常返回带引号的 JSON 字符串
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = try {
                org.json.JSONTokener(text).nextValue() as? String ?: text
            } catch (_: Exception) {
                text.removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", "\n")
            }
        }
        text = text.replace("\\\"", "\"")
        return try {
            val obj = JSONObject(text)
            obj.keys().asSequence().associateWith { key ->
                when (val v = obj.get(key)) {
                    JSONObject.NULL -> null
                    is org.json.JSONArray -> (0 until v.length()).map { i -> v.optString(i) }
                    else -> v
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pickJsonString(blob: String, keys: List<String>): String? {
        for (key in keys) {
            val m = Pattern.compile(""""$key"\s*:\s*"([^"]{1,300})"""").matcher(blob)
            if (m.find()) {
                val v = m.group(1)?.trim().orEmpty()
                if (v.isNotEmpty() && v != "去快手享超清画质") return v
            }
        }
        return null
    }

    private fun pickBestKuaishouUrl(urls: List<String>): String? {
        fun score(u: String): Int {
            var s = 0
            if (u.contains("photo-video-mz")) s += 50
            if (u.contains("hd15") || u.contains("tt=hd")) s += 20
            if (u.contains("_b_") || u.contains("tt=b") || u.contains("_b.mp4")) s -= 40
            if (u.contains("kwaicdn.com")) s += 5
            if (u.contains("watermark")) s -= 100
            return s
        }
        return urls.maxByOrNull { score(it) }
    }

    /**
     * 无 WebView：从分享页 HTML / iteminfo 里抠 play_addr。
     * 失败返回 null，由调用方回退 WebView。
     */
    suspend fun tryResolveDouyinHttp(awemeId: String): DouyinResolveResult? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "https://www.iesdouyin.com/share/video/$awemeId",
            "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$awemeId",
            "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$awemeId&reflow_source=reflow_page",
        )
        for (url in candidates) {
            try {
                Log.d(TAG, "douyin http try $url")
                val response = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", MOBILE_USER_AGENT)
                        .header("Accept", "text/html,application/json,*/*")
                        .header("Referer", "https://www.douyin.com/")
                        .get()
                        .build(),
                ).execute()
                response.use { res ->
                    val body = res.body?.string().orEmpty()
                    if (body.isEmpty()) return@use
                    Log.d(TAG, "douyin http code=${res.code} len=${body.length} final=${res.request.url}")
                    val parsed = extractDouyinMediaFromBlob(body, awemeId) ?: return@use
                    return@withContext parsed
                }
            } catch (t: Throwable) {
                Log.w(TAG, "douyin http fail: ${t.message}")
            }
        }
        null
    }

    private fun extractDouyinMediaFromBlob(blob: String, awemeId: String): DouyinResolveResult? {
        // 解码可能存在的 RENDER_DATA
        var text = blob
        try {
            val m = Pattern.compile(
                """id=["']RENDER_DATA["'][^>]*>([^<]+)<""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(blob)
            if (m.find()) {
                text = try {
                    java.net.URLDecoder.decode(m.group(1), "UTF-8")
                } catch (_: Exception) {
                    m.group(1)!!
                } + "\n" + blob
            }
        } catch (_: Exception) {
        }

        val videoRe = Pattern.compile(
            """https://[^"'\\\s<>]*(?:douyinvod|bytevod|vlabvod|365yg)[^"'\\\s<>]*""",
            Pattern.CASE_INSENSITIVE,
        )
        val videos = linkedSetOf<String>()
        val vm = videoRe.matcher(text.replace("\\/", "/").replace("\\u002F", "/"))
        while (vm.find()) {
            val u = vm.group().replace("\\u002F", "/").replace("\\/", "/")
            if (isDownloadableVideoUrl(u)) videos.add(u)
        }
        // play_addr.url_list 里的直链
        val playListRe = Pattern.compile(
            """"(?:play_addr|download_addr|play_addr_h264)"\s*:\s*\{[^}]*?"url_list"\s*:\s*\[\s*"([^"]+)"""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        )
        val pm = playListRe.matcher(text)
        while (pm.find()) {
            val u = pm.group(1)!!.replace("\\u002F", "/").replace("\\/", "/")
            if (isDownloadableVideoUrl(u)) videos.add(u)
        }
        if (videos.isEmpty()) return null

        fun score(u: String): Int {
            var s = 0
            if (u.contains("douyinvod")) s += 30
            if (u.contains("bytevod") || u.contains("vlabvod")) s += 25
            if (u.contains("mime_type=video_mp4")) s += 20
            if (u.contains(".mp4")) s += 10
            if (u.contains("download")) s += 25
            if (u.contains("tos-cn-ve")) s += 15
            if (u.contains("/logo/") || u.contains("/mps/logo")) s -= 80
            if (u.contains("playwm")) s -= 100
            return s
        }
        val videoUrl = videos.maxByOrNull { score(it) } ?: return null
        val title = pickJsonString(text, listOf("desc", "share_title", "title")).orEmpty()
        val coverRe = Pattern.compile(
            """https://[^"'\\\s<>]*douyinpic[^"'\\\s<>]*""",
            Pattern.CASE_INSENSITIVE,
        )
        var cover: String? = null
        val cm = coverRe.matcher(text.replace("\\/", "/"))
        while (cm.find()) {
            val c = cm.group()
            if (c.contains("avatar") || c.contains("100x100")) continue
            cover = c
            break
        }
        Log.d(TAG, "douyin http hit video=${videoUrl.take(120)}")
        return DouyinResolveResult(
            mediaId = awemeId,
            videoUrl = videoUrl,
            title = title,
            coverUrl = cover,
            source = "douyin/http",
            platform = ShortVideoPlatform.DOUYIN,
        )
    }
}
