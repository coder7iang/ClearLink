package com.strong.clearlink.ui.watermark

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.strong.clearlink.resolve.CaptureHooks
import com.strong.clearlink.resolve.DouyinMediaType
import com.strong.clearlink.resolve.DouyinResolveResult
import com.strong.clearlink.resolve.MediaDownloader
import com.strong.clearlink.resolve.ShortVideoPlatform
import com.strong.clearlink.resolve.ShortVideoResolveService
import com.strong.clearlink.util.ClLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WatermarkUiState(
    val input: String = "",
    val resolving: Boolean = false,
    val saving: Boolean = false,
    val statusHint: String? = null,
    val result: DouyinResolveResult? = null,
    val toast: String? = null,
    val downloadProgress: Float? = null,
    /** 需要过人机验证时展示 WebView */
    val webVisible: Boolean = false,
    val showDebugLog: Boolean = true,
)

class WatermarkRemoveViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "ClearLink"
        private const val EXTRACT_TIMEOUT_MS = 90_000L
    }

    private val _ui = MutableStateFlow(WatermarkUiState())
    val ui: StateFlow<WatermarkUiState> = _ui.asStateFlow()

    @Volatile
    private var webView: WebView? = null

    private var pollJob: Job? = null
    private var extractContinuation: ((Result<DouyinResolveResult>) -> Unit)? = null

    private var pendingMediaId: String? = null
    private var pendingPlatform: ShortVideoPlatform? = null
    private var pendingIsNote: Boolean = false
    private var pollCount = 0
    private var lastImageCount = 0
    private var imageStablePolls = 0
    private var switchedToPcPage = false

    /** 从 WebView 网络层拦截到的最佳视频 URL */
    private val networkVideoUrl = AtomicReference<String?>(null)
    private val networkCoverUrl = AtomicReference<String?>(null)
    private val networkImageUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var atlasExpandStarted = false
    @Volatile private var atlasExpandDone = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        CaptureHooks.ensureLoaded(app)
    }

    fun attachWebView(view: WebView) {
        webView = view
    }

    fun onInputChange(text: String) {
        _ui.update { it.copy(input = text) }
    }

    fun setSharedText(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(input = text.trim()) }
    }

    fun consumeToast() {
        _ui.update { it.copy(toast = null) }
    }

    fun toggleDebugLog() {
        _ui.update { it.copy(showDebugLog = !it.showDebugLog) }
    }

    fun copyDebugLog(): String {
        val text = ClLog.dumpText().ifBlank { "(暂无日志)" }
        toast("调试日志已复制")
        return text
    }

    fun dismissWebOverlay() {
        _ui.update { it.copy(webVisible = false) }
    }

    fun pasteClipboard(text: String) {
        if (text.isBlank()) {
            toast("剪贴板没有文本")
            return
        }
        _ui.update { it.copy(input = text.trim()) }
    }

    fun resolve() {
        if (_ui.value.resolving) return
        val input = _ui.value.input.trim()
        if (input.isEmpty()) {
            toast("请先粘贴抖音 / 快手分享链接")
            return
        }

        viewModelScope.launch {
            ClLog.clear()
            ClLog.i("resolve", "开始解析 input=${input.take(80)}")
            _ui.update {
                it.copy(
                    resolving = true,
                    result = null,
                    statusHint = "正在解析…",
                    downloadProgress = null,
                    webVisible = false,
                    showDebugLog = true,
                )
            }
            try {
                if (ShortVideoResolveService.isWeixinChannelsLink(input)) {
                    ClLog.w("resolve", "识别为微信视频号，当前不支持")
                    error("暂不支持微信视频号（加密流，无法像抖音/快手一样直取）")
                }
                val platform = ShortVideoResolveService.detectPlatform(input)
                    ?: error("未识别到抖音或快手链接")

                when (platform) {
                    ShortVideoPlatform.KUAISHOU -> resolveKuaishou(input)
                    ShortVideoPlatform.DOUYIN -> resolveDouyin(input)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "解析超时", e)
                _ui.update {
                    it.copy(
                        statusHint = if (it.webVisible) {
                            "仍在等待验证完成。完成滑块后会自动继续，或点「取消」。"
                        } else {
                            "提取超时。若弹出验证码，请完成后再试。"
                        },
                    )
                }
                toast("解析超时：${if (_ui.value.webVisible) "请完成页面验证" else "请重试"}")
            } catch (e: Exception) {
                Log.e(TAG, "解析失败", e)
                _ui.update { it.copy(statusHint = null, webVisible = false) }
                toast("解析失败：${e.message}")
            } finally {
                _ui.update { it.copy(resolving = false) }
                // 成功后收起 WebView；超时且仍在验证则保留
                if (_ui.value.result != null) {
                    _ui.update { it.copy(webVisible = false) }
                }
            }
        }
    }

    private suspend fun resolveKuaishou(input: String) {
        ClLog.i("resolve", "快手：先尝试 HTTP")
        _ui.update { it.copy(statusHint = "尝试快速解析快手…") }
        val http = ShortVideoResolveService.tryResolveKuaishouHttp(input)
        if (http != null) {
            ClLog.i(
                "resolve",
                "快手 HTTP 成功 type=${http.mediaType} images=${http.imageUrls.size} " +
                    "video=${ClLog.shortUrl(http.videoUrl)}",
            )
            finishSuccess(http)
            return
        }
        val meta = ShortVideoResolveService.resolveKuaishouMeta(input)
        ClLog.i(
            "resolve",
            "快手 meta id=${meta.photoId} atlas=${meta.isAtlas} final=${ClLog.shortUrl(meta.finalUrl)}",
        )
        if (meta.isAtlas) {
            _ui.update { it.copy(statusHint = "App 内打开快手图文页提取图片…") }
            val web = extractViaWebView(
                mediaId = meta.photoId,
                platform = ShortVideoPlatform.KUAISHOU,
                isNote = true,
                pageUrlOverride = meta.finalUrl.ifEmpty {
                    ShortVideoResolveService.kuaishouPhotoPageUrl(meta.photoId)
                },
            )
            finishSuccess(web)
            return
        }
        _ui.update { it.copy(statusHint = "App 内打开快手页提取直链…") }
        val web = extractViaWebView(
            mediaId = meta.photoId,
            platform = ShortVideoPlatform.KUAISHOU,
            pageUrlOverride = meta.finalUrl.takeIf { it.isNotBlank() },
        )
        finishSuccess(web)
    }

    private suspend fun resolveDouyin(input: String) {
        val looksLikeNote = ShortVideoResolveService.isDouyinNote(input)
        if (looksLikeNote) {
            ClLog.i("resolve", "识别为抖音图文，提取 ID…")
            _ui.update { it.copy(statusHint = "App 内打开图文页提取图片…") }
            val mediaId = ShortVideoResolveService.resolveDouyinMediaIdForNote(input)
            ClLog.i("resolve", "图文 mediaId=$mediaId")
            // 短链多半落到 share/video，图文详情也在该页的 aweme_detail.images
            val web = extractViaWebView(
                mediaId = mediaId,
                platform = ShortVideoPlatform.DOUYIN,
                isNote = true,
                pageUrlOverride = ShortVideoResolveService.videoSharePageUrl(mediaId),
            )
            finishSuccess(web)
            return
        }
        val awemeId = ShortVideoResolveService.resolveAwemeId(input)
        ClLog.i("resolve", "awemeId=$awemeId，先尝试 HTTP 直取")
        _ui.update { it.copy(statusHint = "正在直取视频地址…") }
        val http = ShortVideoResolveService.tryResolveDouyinHttp(awemeId)
        if (http != null) {
            ClLog.i("resolve", "HTTP 直取成功 ${ClLog.shortUrl(http.videoUrl)}")
            finishSuccess(http)
            return
        }
        ClLog.i("resolve", "HTTP 未拿到，改走 WebView 分享页")
        _ui.update { it.copy(statusHint = "App 内打开抖音页提取直链…") }
        val web = extractViaWebView(
            mediaId = awemeId,
            platform = ShortVideoPlatform.DOUYIN,
        )
        finishSuccess(web)
    }

    private fun finishSuccess(result: DouyinResolveResult) {
        ClLog.i(
            "done",
            "解析完成 platform=${result.platform} source=${result.source} " +
                "type=${result.mediaType} id=${result.mediaId} " +
                "kind=${ClLog.urlKind(result.videoUrl)} video=${ClLog.shortUrl(result.videoUrl)} " +
                "images=${result.imageUrls.size} cover=${ClLog.shortUrl(result.coverUrl)}",
        )
        val hint = if (result.isImageNote) {
            "解析成功（${result.source}），共 ${result.imageUrls.size} 张图。直链有时效，请尽快保存。"
        } else {
            "解析成功（${result.source}）。直链有时效，请尽快复制或保存。"
        }
        _ui.update { it.copy(result = result, statusHint = hint, webVisible = false) }
        toast("解析成功")
    }

    private suspend fun extractViaWebView(
        mediaId: String,
        platform: ShortVideoPlatform,
        isNote: Boolean = false,
        pageUrlOverride: String? = null,
    ): DouyinResolveResult {
        pollJob?.cancel()
        extractContinuation?.invoke(Result.failure(Exception("已取消")))
        extractContinuation = null

        pendingMediaId = mediaId
        pendingPlatform = platform
        pendingIsNote = isNote
        pollCount = 0
        lastImageCount = 0
        imageStablePolls = 0
        switchedToPcPage = false
        atlasExpandStarted = false
        atlasExpandDone = false
        networkVideoUrl.set(null)
        networkCoverUrl.set(null)
        networkImageUrls.clear()

        val web = webView ?: error("WebView 未就绪")
        // 抖音也用移动 UA + 分享页，减少 PC 端滑块验证
        web.settings.userAgentString = ShortVideoResolveService.MOBILE_USER_AGENT

        val pageUrl = pageUrlOverride ?: when {
            isNote && platform == ShortVideoPlatform.KUAISHOU ->
                ShortVideoResolveService.kuaishouPhotoPageUrl(mediaId)
            isNote && platform == ShortVideoPlatform.DOUYIN ->
                ShortVideoResolveService.noteSharePageUrl(mediaId)
            isNote -> ShortVideoResolveService.notePageUrl(mediaId)
            platform == ShortVideoPlatform.KUAISHOU ->
                ShortVideoResolveService.kuaishouVideoPageUrl(mediaId)
            else -> ShortVideoResolveService.videoSharePageUrl(mediaId)
        }
        // 先清掉上一页残留 capture，再导航；避免轮询读到旧视频
        resetCaptureState(web, mediaId)
        ClLog.i("resolve", "打开页面 id=$mediaId ${ClLog.shortUrl(pageUrl)}")
        web.loadUrl(pageUrl)

        return withTimeout(EXTRACT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                extractContinuation = { result ->
                    if (cont.isActive) {
                        result.fold(
                            onSuccess = { cont.resume(it) },
                            onFailure = { cont.resumeWithException(it) },
                        )
                    }
                }
                pollJob = viewModelScope.launch {
                    while (true) {
                        delay(600)
                        try {
                            tryReadMedia(mediaId, platform, isNote)
                        } catch (t: Throwable) {
                            ClLog.w("pick", "轮询异常 poll=$pollCount: ${t.message}", t)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    pollJob?.cancel()
                    extractContinuation = null
                }
            }
        }
    }

    fun onWebPageEvent(url: String? = null) {
        val web = webView ?: return
        val platform = pendingPlatform ?: return
        if (looksLikeCaptcha(url) || looksLikeCaptcha(web.url)) {
            showCaptchaUi()
        }
        injectHook(web, platform)
        viewModelScope.launch {
            try {
                tryReadMedia(
                    mediaId = pendingMediaId ?: return@launch,
                    platform = platform,
                    isNote = pendingIsNote,
                )
            } catch (t: Throwable) {
                ClLog.w("pick", "pageEvent 收工异常: ${t.message}", t)
            }
        }
    }

    /**
     * WebView 网络层回调（可能在后台线程，如 shouldInterceptRequest）。
     * 禁止在此处直接调用 WebView API。
     */
    fun onNetworkUrl(url: String) {
        if (url.isBlank()) return
        try {
            if (looksLikeCaptcha(url)) {
                runOnMain { showCaptchaUi() }
                return
            }
            if (ShortVideoResolveService.isDownloadableVideoUrl(url)) {
                val old = networkVideoUrl.get()
                // 同一条直链重复拦截时不要反复比分刷日志
                if (old != null && sameMediaUrl(old, url)) return
                if (old == null || preferVideoUrl(url, old)) {
                    networkVideoUrl.set(url)
                    ClLog.i(
                        "net",
                        "命中视频 kind=${ClLog.urlKind(url)} " +
                            "old=${ClLog.shortUrl(old)} -> ${ClLog.shortUrl(url)}",
                    )
                    runOnMain {
                        injectNetworkVideoToPage(url)
                        // 已有可播直链时立刻尝试收工，不等下一轮 600ms
                        val id = pendingMediaId
                        val platform = pendingPlatform
                        if (id != null && platform != null && extractContinuation != null) {
                            viewModelScope.launch {
                                try {
                                    tryReadMedia(id, platform, pendingIsNote)
                                } catch (t: Throwable) {
                                    ClLog.w("pick", "命中后收工异常: ${t.message}", t)
                                }
                            }
                        }
                    }
                }
            } else if (
                url.contains("douyinvod", true) ||
                url.contains("bytevod", true) ||
                url.contains(".mp4", true) ||
                url.contains("mime_type=video", true) ||
                url.contains("/aweme/detail", true) ||
                url.contains("/share/video/", true) ||
                url.contains("iesdouyin.com/share", true)
            ) {
                // 仅记录有价值的丢弃，避免弹幕/热搜刷屏
                ClLog.d(
                    "net",
                    "丢弃非直链 kind=${ClLog.urlKind(url)} " +
                        "html=${ShortVideoResolveService.looksLikeHtmlPageUrl(url)} " +
                        "api=${ShortVideoResolveService.looksLikeApiJsonUrl(url)} " +
                        "${ClLog.shortUrl(url)}",
                )
            } else if (
                ShortVideoResolveService.isDownloadableImageUrl(url) ||
                (
                    (url.contains("douyinpic") || url.contains("origin_cover")) &&
                        ShortVideoResolveService.isLikelyRasterImageUrl(url)
                    )
            ) {
                val isAtlasImg = url.contains("/ufile/atlas/", true)
                val isDyNoteImg = url.contains("douyinpic", true) &&
                    (
                        url.contains("biz_tag=aweme_images", true) ||
                            url.contains("tplv-dy-aweme-images", true) ||
                            url.contains("aweme-images", true) ||
                            url.contains("obj/", true)
                        )
                // 页面展示链常带「抖音号」水印，低分的不进图集列表
                val dyOk = !url.contains("douyinpic", true) ||
                    ShortVideoResolveService.scoreDouyinImageUrl(url) >= 10
                if ((isAtlasImg || isDyNoteImg || pendingIsNote) && dyOk) {
                    if (networkImageUrls.add(url)) {
                        ClLog.d(
                            "net",
                            "图集图 +1 total=${networkImageUrls.size} score=${ShortVideoResolveService.scoreDouyinImageUrl(url)} ${ClLog.shortUrl(url)}",
                        )
                        runOnMain { injectNetworkImageToPage(url) }
                        maybeExpandAtlasSequence()
                    }
                }
                if (networkCoverUrl.compareAndSet(null, url)) {
                    ClLog.d("net", "封面 ${ClLog.shortUrl(url)}")
                }
            } else if (
                url.contains("/ufile/atlas/", true) &&
                !ShortVideoResolveService.isLikelyRasterImageUrl(url)
            ) {
                ClLog.d("net", "丢弃图集非图片 ${ClLog.urlKind(url)} ${ClLog.shortUrl(url)}")
            }
        } catch (t: Throwable) {
            ClLog.w("net", "onNetworkUrl 异常: ${t.message}", t)
        }
    }

    private fun injectNetworkVideoToPage(url: String) {
        val web = webView ?: return
        web.evaluateJavascript(
            "(function(){try{if(!window.__dyCapture)window.__dyCapture={video:'',cover:'',title:'',source:'',images:[]};" +
                "window.__dyCapture.video=${JSONObjectQuote(url)};" +
                "window.__dyCapture.source=window.__dyCapture.source||'network';}catch(e){}})();",
            null,
        )
    }

    private fun injectNetworkImageToPage(url: String) {
        val web = webView ?: return
        web.evaluateJavascript(
            "(function(){try{" +
                "if(!window.__dyCapture)window.__dyCapture={video:'',cover:'',title:'',source:'',images:[]};" +
                "if(!window.__dyCapture.images)window.__dyCapture.images=[];" +
                "var u=${JSONObjectQuote(url)};" +
                "var key=u.split('?')[0];" +
                "var list=window.__dyCapture.images;" +
                "for(var i=0;i<list.length;i++){if(list[i].split('?')[0]===key)return;}" +
                "list.push(u);" +
                "if(!window.__dyCapture.cover)window.__dyCapture.cover=u;" +
                "window.__dyCapture.source=window.__dyCapture.source||'network-atlas';" +
                "}catch(e){}})();",
            null,
        )
    }

    /** 首张 atlas 图出现后，按序号 HEAD 探测补全；完成前不要收工 */
    private fun maybeExpandAtlasSequence() {
        if (atlasExpandStarted) return
        if (!pendingIsNote || pendingPlatform != ShortVideoPlatform.KUAISHOU) return
        val seeds = networkImageUrls.toList()
        if (seeds.isEmpty()) return
        atlasExpandStarted = true
        atlasExpandDone = false
        viewModelScope.launch {
            try {
                ClLog.i("atlas", "开始序号探测 seed=${seeds.size}")
                val expanded = ShortVideoResolveService.expandKuaishouAtlasSequence(seeds)
                var added = 0
                for (u in expanded) {
                    val key = u.substringBefore('?')
                    if (networkImageUrls.none { it.substringBefore('?') == key }) {
                        networkImageUrls.add(u)
                        added++
                        runOnMain { injectNetworkImageToPage(u) }
                    }
                }
                ClLog.i("atlas", "序号探测完成 total=${networkImageUrls.size} newly=$added")
            } catch (t: Throwable) {
                ClLog.w("atlas", "序号探测失败: ${t.message}", t)
            } finally {
                atlasExpandDone = true
                val id = pendingMediaId
                val platform = pendingPlatform
                if (id != null && platform != null && extractContinuation != null) {
                    try {
                        tryReadMedia(id, platform, pendingIsNote)
                    } catch (t: Throwable) {
                        ClLog.w("atlas", "探测后收工异常: ${t.message}", t)
                    }
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun showCaptchaUi() {
        if (_ui.value.webVisible) return
        Log.d(TAG, "检测到验证码，展示 WebView")
        _ui.update {
            it.copy(
                webVisible = true,
                statusHint = "抖音要求人机验证，请在全屏页面完成滑块后等待自动解析",
            )
        }
    }

    private fun looksLikeCaptcha(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        // 登录/搜索页不算人机验证
        if (u.contains("/login") || u.contains("passport") || u.contains("/search")) return false
        return u.contains("captcha") ||
            u.contains("verifycenter") ||
            u.contains("rc-verify") ||
            u.contains("nocaptcha") ||
            u.contains("bdturing") ||
            (u.contains("bytedance://") && u.contains("verify"))
    }

    private fun preferVideoUrl(a: String, b: String): Boolean {
        fun score(u: String): Int {
            var s = 0
            if (u.contains("photo-video-mz")) s += 50
            if (u.contains("photo-video")) s += 20
            if (u.contains("kwaicdn.com")) s += 15
            if (u.contains("douyinvod")) s += 30
            if (u.contains("bytevod") || u.contains("vlabvod")) s += 25
            if (u.contains("mime_type=video_mp4")) s += 20
            if (u.contains(".mp4")) s += 15
            if (u.contains(".m3u8")) s += 8
            if (u.contains("/play")) s += 5
            if (u.contains("download")) s += 25
            if (u.contains("tos-cn-ve")) s += 15
            // /logo/、playwm 基本是带水印流
            if (u.contains("/logo/") || u.contains("/mps/logo")) s -= 80
            if (u.contains("playwm")) s -= 100
            if (u.contains("watermark")) s -= 60
            if (u.contains("_b_") || u.contains("tt=b")) s -= 40
            if (ShortVideoResolveService.looksLikeApiJsonUrl(u)) s -= 100
            if (ShortVideoResolveService.looksLikeHtmlPageUrl(u)) s -= 100
            return s
        }
        val sa = score(a)
        val sb = score(b)
        if (sa != sb) {
            ClLog.d("pick", "比分 a=$sa ${ClLog.shortUrl(a)} | b=$sb ${ClLog.shortUrl(b)}")
        }
        return sa > sb
    }

    private fun sameMediaUrl(a: String, b: String): Boolean {
        if (a == b) return true
        return a.substringBefore('?') == b.substringBefore('?')
    }

    private fun injectHook(web: WebView, platform: ShortVideoPlatform) {
        val ctx = getApplication<Application>()
        val id = pendingMediaId.orEmpty()
        // 先写入目标 ID，再注入/复用 hook，保证只采当前作品
        web.evaluateJavascript(
            "window.__dyTargetId=${JSONObjectQuote(id)};",
            null,
        )
        web.evaluateJavascript(CaptureHooks.captureHook(ctx), null)
        if (platform == ShortVideoPlatform.KUAISHOU) {
            web.evaluateJavascript(CaptureHooks.kuaishouCaptureHook(ctx), null)
        }
    }

    /** 清空 JS 侧旧结果，并绑定本次作品 ID */
    private fun resetCaptureState(web: WebView, mediaId: String) {
        web.evaluateJavascript(
            """
            (function(){
              window.__dyTargetId=${JSONObjectQuote(mediaId)};
              window.__dyHooked=false;
              window.__dyCaptureTarget='';
              window.__dyCapture={video:'',cover:'',title:'',source:'',images:[],awemeId:''};
            })();
            """.trimIndent().replace('\n', ' '),
            null,
        )
    }

    /** 页面 URL 是否已落到当前作品（避免导航完成前读到上一页） */
    private fun pageMatchesPending(web: WebView, mediaId: String): Boolean {
        val u = web.url.orEmpty()
        if (u.isBlank() || u == "about:blank") return false
        return u.contains(mediaId)
    }

    private suspend fun tryReadMedia(
        mediaId: String,
        platform: ShortVideoPlatform,
        isNote: Boolean,
    ) {
        if (extractContinuation == null) return
        // 验证中延长轮询次数
        val maxPoll = if (_ui.value.webVisible) 140 else 90
        if (pollCount++ > maxPoll) return
        val web = webView ?: return

        // 仍在上一页 / 空白页时绝不能收工
        if (!pageMatchesPending(web, mediaId)) {
            if (pollCount <= 3 || pollCount % 10 == 0) {
                ClLog.d(
                    "pick",
                    "等待目标页… poll=$pollCount expect=$mediaId url=${ClLog.shortUrl(web.url)}",
                )
            }
            return
        }

        // 检测真正的滑块/安全验证；排除「验证码登录」登录弹窗误报
        val captchaHit = evaluateJs(
            web,
            """
            (function(){try{
              var t=(document.body&&document.body.innerText)||'';
              var loginOnly=/验证码登录|登录后即可|请输入手机号|\+86/.test(t)
                && !/请完成安全验证|拖动下方滑块|向右拖动|滑动验证|人机验证|拖动滑块完成/.test(t);
              if(loginOnly) return false;
              var hasSlider=/请完成安全验证|拖动下方滑块|向右拖动滑块|滑动验证|人机验证|拖动滑块完成|按住左边按钮拖动/.test(t);
              var hasDom=!!document.querySelector(
                '[class*="captcha_verify"],[class*="captcha-verify"],[id*="captcha-verify"],' +
                'iframe[src*="captcha"],iframe[src*="verifycenter"],iframe[src*="bdturing"],iframe[src*="rc-verify"]'
              );
              return !!(hasSlider||hasDom);
            }catch(e){return false;}})();
            """.trimIndent().replace('\n', ' '),
        )
        if (captchaHit == "true") {
            ClLog.d("captcha", "命中滑块/安全验证 DOM")
            showCaptchaUi()
        }

        injectHook(web, platform)
        val ctx = getApplication<Application>()
        if (isNote) {
            web.evaluateJavascript(CaptureHooks.noteNudge(ctx), null)
        }

        // 分享页长时间采不到直链时，切 PC 详情页（可能出验证码，但 aweme_detail 更全）
        if (!isNote &&
            platform == ShortVideoPlatform.DOUYIN &&
            !switchedToPcPage &&
            pollCount == 35 &&
            networkVideoUrl.get().isNullOrBlank()
        ) {
            switchedToPcPage = true
            ClLog.i("resolve", "分享页未采到直链，切换 PC 详情页（可能需要验证）")
            _ui.update { it.copy(statusHint = "切换详情页继续提取（如弹出验证请完成滑块）…") }
            web.settings.userAgentString = ShortVideoResolveService.USER_AGENT
            injectHook(web, platform)
            web.loadUrl(ShortVideoResolveService.videoPageUrl(mediaId))
        }

        val raw = evaluateJs(web, CaptureHooks.readCapture(ctx))
        val map = ShortVideoResolveService.decodeJsJson(raw)

        // JS 采到了其它作品 ID：丢掉 video，继续等当前作品
        val capturedAwemeId = (map?.get("awemeId") as? String)?.trim().orEmpty()
        if (capturedAwemeId.isNotEmpty() && capturedAwemeId != mediaId) {
            ClLog.w(
                "pick",
                "丢弃错位作品 captured=$capturedAwemeId expect=$mediaId " +
                    "title=${(map?.get("title") as? String)?.take(40)}",
            )
            web.evaluateJavascript(
                "(function(){try{window.__dyCapture={video:'',cover:'',title:'',source:'',images:[],awemeId:''};}catch(e){}})();",
                null,
            )
            return
        }

        // 图文：未拿到 detail 原图时切 PC video 页（note 页常打不开）
        if (isNote &&
            platform == ShortVideoPlatform.DOUYIN &&
            !switchedToPcPage &&
            pollCount == 20
        ) {
            val sourceNow = (map?.get("source") as? String).orEmpty()
            val jsImgs = parseImageUrls(map?.get("images"))
            val hasDetail = sourceNow.contains("detail") && jsImgs.size >= 2
            if (!hasDetail) {
                switchedToPcPage = true
                ClLog.i("resolve", "图文未拿到 detail 原图，切换 PC 作品页继续提取")
                _ui.update { it.copy(statusHint = "切换详情页提取无水印原图…") }
                web.settings.userAgentString = ShortVideoResolveService.USER_AGENT
                injectHook(web, platform)
                web.loadUrl(ShortVideoResolveService.videoPageUrl(mediaId))
                return
            }
        }

        var title = (map?.get("title") as? String)?.trim().orEmpty()
        title = title.replace(Regex("""\s*-\s*抖音\s*$"""), "").trim()
        if (platform == ShortVideoPlatform.KUAISHOU) {
            title = title.replace(Regex("""\s*-\s*快手\s*$"""), "").trim()
        }

        if (isNote) {
            val sourceRaw = (map?.get("source") as? String)?.trim().orEmpty()
            val fromDetail = sourceRaw.contains("detail")
            var images = parseImageUrls(map?.get("images")).toMutableList()

            // 网络层多为页面展示的带水印图；仅在没有 detail 原图时兜底
            if (!fromDetail || images.isEmpty()) {
                for (u in networkImageUrls) {
                    if (ShortVideoResolveService.isDownloadableImageUrl(u) &&
                        images.none { it.substringBefore('?') == u.substringBefore('?') }
                    ) {
                        images.add(u)
                    }
                }
                networkCoverUrl.get()?.let { cover ->
                    if (ShortVideoResolveService.isDownloadableImageUrl(cover) &&
                        images.none { it.substringBefore('?') == cover.substringBefore('?') }
                    ) {
                        images.add(cover)
                    }
                }
            }

            if (images.isEmpty()) {
                val videoResult = buildVideoResult(
                    mediaId = mediaId,
                    platform = platform,
                    title = title,
                    map = map,
                )
                if (videoResult != null) {
                    ClLog.i("pick", "图集模式命中视频，改按视频收工 ${ClLog.shortUrl(videoResult.videoUrl)}")
                    completeExtract(videoResult)
                } else if (pollCount % 10 == 0) {
                    ClLog.d("pick", "图集尚未采到图片 poll=$pollCount netImgs=${networkImageUrls.size}")
                }
                return
            }

            if (platform == ShortVideoPlatform.KUAISHOU) {
                images = ShortVideoResolveService.sortKuaishouAtlasUrls(images).toMutableList()
            } else if (platform == ShortVideoPlatform.DOUYIN) {
                images = ShortVideoResolveService.dedupePreferCleanImages(images).toMutableList()
            }

            val source = sourceRaw.ifEmpty {
                if (networkImageUrls.isNotEmpty()) "network-atlas" else "webview"
            }
            // 序号探测未完成前绝不收工（否则常只拿到 _0.jpg）
            val waitingExpand = platform == ShortVideoPlatform.KUAISHOU &&
                atlasExpandStarted &&
                !atlasExpandDone
            if (waitingExpand) {
                if (pollCount % 5 == 0) {
                    ClLog.d("pick", "等待序号探测… 当前=${images.size} 张 poll=$pollCount")
                }
                return
            }
            // 抖音：尽量等到 detail-images（无水印），避免过早用带水印预览图收工
            if (platform == ShortVideoPlatform.DOUYIN && !fromDetail && pollCount < 40) {
                if (pollCount % 8 == 0) {
                    ClLog.d(
                        "pick",
                        "等待无水印原图 detail… 当前=${images.size} source=$source poll=$pollCount",
                    )
                }
                return
            }
            if (images.size == lastImageCount) {
                imageStablePolls++
            } else {
                lastImageCount = images.size
                imageStablePolls = 0
                ClLog.d("pick", "图文收集中… ${images.size} 张 (source=$source poll=$pollCount)")
            }
            val stableEnough = imageStablePolls >= 2 && pollCount >= 4
            val forceFinish = pollCount >= 55 && images.isNotEmpty()
            if (!fromDetail && !stableEnough && !forceFinish) return

            ClLog.i(
                "pick",
                "选定图集 count=${images.size} source=$source " +
                    "first=${ClLog.shortUrl(images.firstOrNull())}",
            )
            completeExtract(
                DouyinResolveResult(
                    mediaId = mediaId,
                    title = title,
                    coverUrl = images.firstOrNull() ?: (map?.get("cover") as? String),
                    source = source.ifEmpty { "webview" },
                    platform = platform,
                    mediaType = DouyinMediaType.IMAGES,
                    imageUrls = images,
                ),
            )
            return
        }

        val jsVideo = (map?.get("video") as? String)?.trim().orEmpty()
        val netVideo = networkVideoUrl.get().orEmpty()
        val jsOk = ShortVideoResolveService.isDownloadableVideoUrl(jsVideo)
        val netOk = ShortVideoResolveService.isDownloadableVideoUrl(netVideo)
        val video = when {
            jsOk && netOk -> if (preferVideoUrl(netVideo, jsVideo)) netVideo else jsVideo
            netOk -> netVideo
            jsOk -> jsVideo
            else -> ""
        }
        if (video.isEmpty()) {
            // 视频模式却采到图文列表：按图集收工（短链图文常被当成视频打开）
            var images = parseImageUrls(map?.get("images")).toMutableList()
            for (u in networkImageUrls) {
                if (ShortVideoResolveService.isDownloadableImageUrl(u) &&
                    images.none { it.substringBefore('?') == u.substringBefore('?') }
                ) {
                    images.add(u)
                }
            }
            if (images.size >= 2 || (images.isNotEmpty() && pollCount >= 20)) {
                if (platform == ShortVideoPlatform.KUAISHOU) {
                    images = ShortVideoResolveService.sortKuaishouAtlasUrls(images).toMutableList()
                }
                ClLog.i("pick", "视频模式改收图集 count=${images.size}")
                completeExtract(
                    DouyinResolveResult(
                        mediaId = mediaId,
                        title = title,
                        coverUrl = images.firstOrNull(),
                        source = "webview-images",
                        platform = platform,
                        mediaType = DouyinMediaType.IMAGES,
                        imageUrls = images,
                    ),
                )
                return
            }
            if (pollCount % 10 == 0) {
                ClLog.d(
                    "pick",
                    "尚未采到可播地址 poll=$pollCount " +
                        "jsOk=$jsOk kind=${ClLog.urlKind(jsVideo)} ${ClLog.shortUrl(jsVideo)} | " +
                        "netOk=$netOk kind=${ClLog.urlKind(netVideo)} ${ClLog.shortUrl(netVideo)} | " +
                        "imgs=${images.size}",
                )
            }
            return
        }

        val videoResult = buildVideoResult(
            mediaId = mediaId,
            platform = platform,
            title = title,
            map = map,
            videoOverride = video,
        ) ?: return
        ClLog.i(
            "pick",
            "选定视频 source=${videoResult.source} kind=${ClLog.urlKind(videoResult.videoUrl)} " +
                "id=$mediaId title=${title.take(40)} " +
                "js=${ClLog.shortUrl(jsVideo)} net=${ClLog.shortUrl(netVideo)} " +
                "final=${ClLog.shortUrl(videoResult.videoUrl)}",
        )
        completeExtract(videoResult)
    }

    private fun buildVideoResult(
        mediaId: String,
        platform: ShortVideoPlatform,
        title: String,
        map: Map<String, Any?>?,
        videoOverride: String? = null,
    ): DouyinResolveResult? {
        val jsVideo = (map?.get("video") as? String)?.trim().orEmpty()
        val netVideo = networkVideoUrl.get().orEmpty()
        val jsOk = ShortVideoResolveService.isDownloadableVideoUrl(jsVideo)
        val netOk = ShortVideoResolveService.isDownloadableVideoUrl(netVideo)
        val video = videoOverride?.takeIf { ShortVideoResolveService.isDownloadableVideoUrl(it) }
            ?: when {
                jsOk && netOk -> if (preferVideoUrl(netVideo, jsVideo)) netVideo else jsVideo
                netOk -> netVideo
                jsOk -> jsVideo
                else -> ""
            }
        if (video.isEmpty()) return null

        val cover = (map?.get("cover") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: networkCoverUrl.get()
        val source = when {
            netVideo.isNotEmpty() && video == netVideo -> "network"
            else -> (map?.get("source") as? String)?.trim().orEmpty().ifEmpty { "webview" }
        }
        return DouyinResolveResult(
            mediaId = mediaId,
            videoUrl = video,
            title = title,
            coverUrl = cover,
            source = source,
            platform = platform,
        )
    }

    private fun completeExtract(result: DouyinResolveResult) {
        // 并发轮询可能同时命中，只收工一次
        val active = synchronized(this) {
            val c = extractContinuation ?: return
            extractContinuation = null
            c
        }
        pollJob?.cancel()
        active(Result.success(result))
        ClLog.d("pick", "completeExtract source=${result.source}")
    }

    private fun parseImageUrls(raw: Any?): List<String> {
        val list = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> listOf(raw)
            else -> emptyList()
        }
        return list.filter { ShortVideoResolveService.isDownloadableImageUrl(it) }.distinct()
    }

    private suspend fun evaluateJs(web: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            web.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value)
            }
        }

    fun copyVideoUrl(): String? {
        val url = _ui.value.result?.videoUrl?.takeIf {
            ShortVideoResolveService.isDownloadableVideoUrl(it)
        }
        if (url == null) {
            toast("暂无可复制的视频直链")
            return null
        }
        toast("已复制直链")
        return url
    }

    fun saveResult() {
        val result = _ui.value.result ?: return
        if (_ui.value.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, downloadProgress = 0f) }
            try {
                val ctx = getApplication<Application>()
                val base = safeFileBase(result)
                if (result.isImageNote) {
                    val n = MediaDownloader.downloadImagesToGallery(
                        context = ctx,
                        urls = result.imageUrls,
                        baseName = base,
                        platform = result.platform,
                    )
                    if (n <= 0) error("没有可保存的图片")
                    toast("已保存 $n 张图片到相册")
                } else {
                    if (!ShortVideoResolveService.isDownloadableVideoUrl(result.videoUrl)) {
                        error("无效的视频地址")
                    }
                    MediaDownloader.downloadVideoToGallery(
                        context = ctx,
                        url = result.videoUrl,
                        displayName = base,
                        platform = result.platform,
                    ) { received, total ->
                        if (total > 0) {
                            _ui.update { it.copy(downloadProgress = received.toFloat() / total) }
                        }
                    }
                    toast("已保存到相册 Movies/ClearLink")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存失败", e)
                toast("保存失败：${e.message}")
            } finally {
                _ui.update { it.copy(saving = false, downloadProgress = null) }
            }
        }
    }

    /** 长按单张图保存 */
    fun saveSingleImage(url: String) {
        val result = _ui.value.result ?: return
        if (_ui.value.saving) return
        if (url.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, downloadProgress = null) }
            try {
                val ctx = getApplication<Application>()
                val base = safeFileBase(result) + "_one"
                val n = MediaDownloader.downloadImagesToGallery(
                    context = ctx,
                    urls = listOf(url),
                    baseName = base,
                    platform = result.platform,
                )
                if (n <= 0) error("保存失败")
                toast("已保存当前图片到相册")
            } catch (e: Exception) {
                Log.e(TAG, "单张保存失败", e)
                toast("保存失败：${e.message}")
            } finally {
                _ui.update { it.copy(saving = false, downloadProgress = null) }
            }
        }
    }

    private fun safeFileBase(result: DouyinResolveResult): String {
        val prefix = if (result.platform == ShortVideoPlatform.KUAISHOU) "kuaishou" else "douyin"
        val title = result.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(40)
            .ifBlank { result.mediaId }
        return "${prefix}_$title"
    }

    private fun toast(msg: String) {
        _ui.update { it.copy(toast = msg) }
    }

    private fun JSONObjectQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    override fun onCleared() {
        pollJob?.cancel()
        extractContinuation?.invoke(Result.failure(Exception("已关闭")))
        extractContinuation = null
        webView = null
        super.onCleared()
    }
}
