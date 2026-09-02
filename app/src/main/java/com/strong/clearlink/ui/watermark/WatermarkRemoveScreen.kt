package com.strong.clearlink.ui.watermark

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.strong.clearlink.resolve.DouyinResolveResult
import com.strong.clearlink.resolve.ShortVideoPlatform
import com.strong.clearlink.resolve.ShortVideoResolveService
import com.strong.clearlink.util.ClLog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF1C1917)
private val Mist = Color(0xFFF5F0E8)
private val Teal = Color(0xFF0F766E)
private val TealDark = Color(0xFF115E59)
private val Line = Color(0xFFD6D3D1)

@Composable
fun WatermarkRemoveScreen(
    sharedText: String? = null,
    vm: WatermarkRemoveViewModel = viewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showViewer by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) vm.setSharedText(sharedText)
    }

    LaunchedEffect(state.result) {
        if (state.result == null) showViewer = false
    }

    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        vm.consumeToast()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFE7F0EE), Mist, Color(0xFFF8F5F0)),
                ),
            ),
    ) {
        val result = state.result
        if (showViewer && result != null) {
            MediaViewerPage(
                result = result,
                onBack = { showViewer = false },
                saving = state.saving,
                progress = state.downloadProgress,
                onCopy = {
                    val url = vm.copyVideoUrl()
                    if (url != null) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("video", url))
                    }
                },
                onSave = vm::saveResult,
                onSaveSingle = vm::saveSingleImage,
            )
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Text(
                text = "ClearLink",
                style = TextStyle(
                    color = Ink,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "粘贴抖音 / 快手分享链接，提取无水印直链",
                color = Ink.copy(alpha = 0.65f),
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(28.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .padding(16.dp),
            ) {
                Column {
                    BasicTextField(
                        value = state.input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        textStyle = TextStyle(color = Ink, fontSize = 15.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(Teal),
                        decorationBox = { inner ->
                            Box {
                                if (state.input.isEmpty()) {
                                    Text(
                                        "粘贴分享文案或短链（支持图文）",
                                        color = Ink.copy(alpha = 0.35f),
                                        fontSize = 15.sp,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                vm.pasteClipboard(text)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TealDark),
                        ) {
                            Text("粘贴")
                        }
                        Button(
                            onClick = vm::resolve,
                            enabled = !state.resolving,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Teal,
                                contentColor = Color.White,
                            ),
                        ) {
                            if (state.resolving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (state.resolving) "解析中" else "去水印")
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !state.statusHint.isNullOrBlank()) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = state.statusHint.orEmpty(),
                        color = TealDark,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            if (result != null) {
                Spacer(Modifier.height(22.dp))
                ResultCard(
                    title = result.title.ifBlank { "已解析内容" },
                    videoUrl = result.videoUrl,
                    coverUrl = result.coverUrl,
                    imageUrls = result.imageUrls,
                    platform = result.platform,
                    source = result.source,
                    isImages = result.isImageNote,
                    saving = state.saving,
                    progress = state.downloadProgress,
                    onOpenViewer = { showViewer = true },
                    onCopy = {
                        val url = vm.copyVideoUrl()
                        if (url != null) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("video", url))
                        }
                    },
                    onSave = vm::saveResult,
                    showCopy = !result.isImageNote,
                )
            }

            Spacer(Modifier.height(18.dp))
            DebugLogPanel(
                expanded = state.showDebugLog,
                onToggle = vm::toggleDebugLog,
                onCopy = {
                    val text = vm.copyDebugLog()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("clearlink-log", text))
                },
            )

            Spacer(Modifier.height(40.dp))
            Text(
                text = "直链有时效，解析后请尽快保存。仅供个人学习使用。",
                color = Ink.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
        }
        }

        // 始终挂载同一 WebView；验证时全屏，避免底部窄条导致滑块划不动
        Column(
            modifier = Modifier
                .align(if (state.webVisible) Alignment.Center else Alignment.BottomStart)
                .then(
                    if (state.webVisible) {
                        Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .statusBarsPadding()
                    } else {
                        Modifier.size(1.dp)
                    },
                ),
        ) {
            if (state.webVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("请完成人机验证", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
                        Text(
                            "按提示拖动滑块，完成后会自动继续解析",
                            color = Ink.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = vm::dismissWebOverlay) {
                        Text("收起", color = TealDark)
                    }
                }
            }
            ResolveWebView(
                visible = state.webVisible,
                onReady = vm::attachWebView,
                onPageEvent = vm::onWebPageEvent,
                onNetworkUrl = vm::onNetworkUrl,
                modifier = if (state.webVisible) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier.size(1.dp)
                },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ResolveWebView(
    visible: Boolean,
    onReady: (WebView) -> Unit,
    onPageEvent: (String?) -> Unit,
    onNetworkUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = ShortVideoResolveService.MOBILE_USER_AGENT
                // 避免外层 Compose 抢走横向拖动手势，滑块才能拖动
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onPageEvent(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageEvent(url)
                    }

                    override fun onLoadResource(view: WebView?, url: String?) {
                        if (!url.isNullOrBlank()) {
                            try {
                                onNetworkUrl(url)
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        // 此回调在后台线程，禁止调用 WebView API
                        try {
                            val u = request?.url?.toString()
                            if (!u.isNullOrBlank()) onNetworkUrl(u)
                        } catch (_: Throwable) {
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                onReady(this)
            }
        },
        update = { web ->
            web.visibility = if (visible) {
                android.view.View.VISIBLE
            } else {
                android.view.View.INVISIBLE
            }
        },
    )
}

@Composable
private fun ResultCard(
    title: String,
    videoUrl: String,
    coverUrl: String?,
    imageUrls: List<String>,
    platform: ShortVideoPlatform,
    source: String,
    isImages: Boolean,
    saving: Boolean,
    progress: Float?,
    onOpenViewer: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    showCopy: Boolean,
) {
    val thumbs = when {
        isImages && imageUrls.isNotEmpty() -> imageUrls
        !coverUrl.isNullOrBlank() -> listOfNotNull(coverUrl)
        else -> emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.9f))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isImages) {
                "${platformLabel(platform)} · 图文 ${imageUrls.size} 张 · $source"
            } else {
                "${platformLabel(platform)} · $source"
            },
            color = Ink.copy(alpha = 0.45f),
            fontSize = 12.sp,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clickable(onClick = onOpenViewer),
            contentAlignment = Alignment.Center,
        ) {
            if (isImages && thumbs.size > 1) {
                StackedImagePreview(
                    urls = thumbs,
                    platform = platform,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb = thumbs.firstOrNull()
                    if (!thumb.isNullOrBlank()) {
                        AsyncImage(
                            model = previewImageRequest(LocalContext.current, thumb, platform),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = if (isImages) "暂无封面" else "暂无预览",
                            color = Ink.copy(alpha = 0.4f),
                            fontSize = 13.sp,
                        )
                    }
                    if (!isImages) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "▶",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            if (isImages && imageUrls.isNotEmpty()) {
                Text(
                    text = "${imageUrls.size} 张",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showCopy) {
                TextButton(onClick = onCopy) { Text("复制直链", color = TealDark) }
            }
            Button(
                onClick = onSave,
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = TealDark),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (saving) "保存中…"
                    else if (isImages) "全部保存"
                    else "保存视频",
                )
            }
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Teal,
                trackColor = Line,
            )
        }
    }
}

/** 多图整齐叠放：仅轻微下移缩小，无旋转 */
@Composable
private fun StackedImagePreview(
    urls: List<String>,
    platform: ShortVideoPlatform,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val stack = urls.take(3)
    Box(
        modifier = modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // 从后往前画：底层更小、略下移，形成整齐层叠
        stack.asReversed().forEachIndexed { revIndex, url ->
            val depth = stack.lastIndex - revIndex // 0=顶层
            val scale = 1f - depth * 0.06f
            val y = (depth * 10).dp
            AsyncImage(
                model = previewImageRequest(context, url, platform),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .offset(y = y)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(Line),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaViewerPage(
    result: DouyinResolveResult,
    onBack: () -> Unit,
    saving: Boolean,
    progress: Float?,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onSaveSingle: (String) -> Unit,
) {
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSaveUrl by remember { mutableStateOf<String?>(null) }
    val isImages = result.isImageNote
    val view = LocalView.current
    val imageUrls = result.imageUrls
    BackHandler {
        when {
            pendingSaveUrl != null -> pendingSaveUrl = null
            fullscreenIndex != null -> fullscreenIndex = null
            else -> onBack()
        }
    }

    // 沉浸式：深色内容区 + 浅色状态栏图标
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prev = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (prev != null) controller?.isAppearanceLightStatusBars = prev
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0A09)),
    ) {
        if (isImages && imageUrls.isNotEmpty()) {
            ImageGridPreview(
                urls = imageUrls,
                platform = result.platform,
                onOpen = { index -> fullscreenIndex = index },
                modifier = Modifier.fillMaxSize(),
                topInset = true,
                bottomInset = true,
            )
        } else if (result.videoUrl.isNotBlank()) {
            VideoPreviewPlayer(
                url = result.videoUrl,
                coverUrl = result.coverUrl,
                platform = result.platform,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 40.dp, bottom = 72.dp)
                    .padding(horizontal = 4.dp),
            )
        } else {
            Text(
                "暂无可播放内容",
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 浮动返回：圆形半透明底，不挡内容
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 10.dp, top = 6.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
            ) {
                Canvas(modifier = Modifier.size(20.dp)) {
                    val stroke = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.62f, h * 0.18f)
                        lineTo(w * 0.32f, h * 0.5f)
                        lineTo(w * 0.62f, h * 0.82f)
                    }
                    drawPath(path, color = Color.White, style = stroke)
                }
            }
        }

        // 底部渐变 + 全部保存
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    color = Teal,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isImages) {
                    TextButton(onClick = onCopy) { Text("复制直链", color = Color(0xFF99F6E4)) }
                }
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (saving) "保存中…"
                        else if (isImages) "全部保存"
                        else "保存视频",
                    )
                }
            }
        }
    }

    fullscreenIndex?.let { startIndex ->
        val safeStart = startIndex.coerceIn(0, (imageUrls.size - 1).coerceAtLeast(0))
        Dialog(
            onDismissRequest = { fullscreenIndex = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val pagerState = rememberPagerState(
                initialPage = safeStart,
                pageCount = { imageUrls.size.coerceAtLeast(1) },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val url = imageUrls.getOrNull(page).orEmpty()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = { fullscreenIndex = null },
                                onLongClick = {
                                    if (url.isNotBlank()) pendingSaveUrl = url
                                },
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (url.isNotBlank()) {
                            // 贴顶 Fit，避免垂直居中导致上方大块留白
                            AsyncImage(
                                model = previewImageRequest(LocalContext.current, url, result.platform),
                                contentDescription = "图片 ${page + 1}",
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(top = 52.dp)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${imageUrls.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }

    pendingSaveUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingSaveUrl = null },
            title = { Text("保存图片") },
            text = { Text("将当前这张图片保存到相册？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSaveUrl = null
                        onSaveSingle(url)
                    },
                ) {
                    Text("保存", color = TealDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSaveUrl = null }) {
                    Text("取消", color = Ink.copy(alpha = 0.55f))
                }
            },
            containerColor = Color.White,
            titleContentColor = Ink,
            textContentColor = Ink.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun ImageGridPreview(
    urls: List<String>,
    platform: ShortVideoPlatform,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topInset: Boolean = false,
    bottomInset: Boolean = false,
) {
    val context = LocalContext.current
    val topPad = if (topInset) 56.dp else 0.dp
    val bottomPad = if (bottomInset) 96.dp else 8.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topPad,
            bottom = bottomPad,
        ),
    ) {
        itemsIndexed(urls) { index, url ->
            Box {
                AsyncImage(
                    model = previewImageRequest(context, url, platform),
                    contentDescription = "图片 ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onOpen(index) },
                )
                Text(
                    text = "${index + 1}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreviewPlayer(
    url: String,
    coverUrl: String?,
    platform: ShortVideoPlatform,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var playUri by remember(url) { mutableStateOf(url) }
    var fallbackTried by remember(url) { mutableStateOf(false) }
    var previewFailed by remember(url) { mutableStateOf(false) }
    var statusText by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url, platform) {
        ClLog.i(
            "preview",
            "开始预览 platform=$platform kind=${ClLog.urlKind(url)} ${ClLog.shortUrl(url)}",
        )
        withContext(Dispatchers.IO) {
            probeMediaUrl(url, platform)
        }
    }

    val player = remember(playUri, platform) {
        ClLog.i("preview", "创建播放器 uriKind=${ClLog.urlKind(playUri)} ${ClLog.shortUrl(playUri)}")
        buildPreviewPlayer(context, playUri, platform)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                ClLog.d("preview", "state=$name playWhenReady=${player.playWhenReady}")
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                ClLog.e(
                    "preview",
                    "播放错误 code=${error.errorCode} name=${error.errorCodeName} " +
                        "msg=${error.message} cause=${cause?.javaClass?.simpleName}: ${cause?.message} " +
                        "uri=${ClLog.shortUrl(playUri)}",
                    error,
                )
                mainHandler.post {
                    if (!fallbackTried) {
                        fallbackTried = true
                        statusText = "直链无法在线播，正在缓存后预览…"
                        ClLog.i("preview", "进入缓存回退")
                    } else {
                        previewFailed = true
                        statusText = "预览失败，可直接保存到相册观看"
                        ClLog.e("preview", "缓存回退后仍失败")
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            ClLog.d("preview", "释放播放器 ${ClLog.shortUrl(playUri)}")
        }
    }

    LaunchedEffect(url, fallbackTried, previewFailed) {
        if (!fallbackTried || previewFailed) return@LaunchedEffect
        val cached = runCatching {
            withContext(Dispatchers.IO) {
                cacheVideoForPreview(context, url, platform)
            }
        }.getOrElse {
            ClLog.e("preview", "缓存预览失败: ${it.message}", it)
            null
        }
        if (cached != null) {
            ClLog.i(
                "preview",
                "缓存成功 size=${cached.length()} path=${cached.name} -> 切本地播放",
            )
            playUri = Uri.fromFile(cached).toString()
            statusText = null
        } else {
            previewFailed = true
            statusText = "预览失败，可直接保存到相册观看"
        }
    }

    Box(modifier = modifier) {
        if (!previewFailed) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = previewImageRequest(context, coverUrl, platform),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        statusText?.let { tip ->
            Text(
                text = tip,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun buildPreviewPlayer(
    context: Context,
    mediaUrl: String,
    platform: ShortVideoPlatform,
): ExoPlayer {
    val ua = if (platform == ShortVideoPlatform.KUAISHOU) {
        ShortVideoResolveService.MOBILE_USER_AGENT
    } else {
        ShortVideoResolveService.USER_AGENT
    }
    val headers = mutableMapOf(
        "Referer" to ShortVideoResolveService.refererFor(platform),
        "Accept" to "*/*",
    )
    val urlCookie = CookieManager.getInstance().getCookie(mediaUrl).orEmpty()
    val siteCookie = CookieManager.getInstance()
        .getCookie(ShortVideoResolveService.refererFor(platform))
        .orEmpty()
    if (urlCookie.isNotBlank()) headers["Cookie"] = urlCookie
    if (siteCookie.isNotBlank()) {
        val old = headers["Cookie"]
        headers["Cookie"] = if (old.isNullOrBlank()) siteCookie else "$old; $siteCookie"
    }
    ClLog.d(
        "preview",
        "请求头 Referer=${headers["Referer"]} " +
            "cookieLen=${headers["Cookie"]?.length ?: 0} " +
            "ua=${ua.take(40)}…",
    )

    val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(ua)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(mediaUrl))
            prepare()
            playWhenReady = false
            volume = 1f
        }
}

private val previewHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()
}

/** HEAD/GET 探测直链真实类型，便于对照 ExoPlayer 报错 */
private fun probeMediaUrl(url: String, platform: ShortVideoPlatform) {
    if (url.startsWith("file:")) {
        ClLog.d("probe", "跳过本地 file")
        return
    }
    val ua = if (platform == ShortVideoPlatform.KUAISHOU) {
        ShortVideoResolveService.MOBILE_USER_AGENT
    } else {
        ShortVideoResolveService.USER_AGENT
    }
    fun build(method: String): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", ShortVideoResolveService.refererFor(platform))
            .header("Accept", "*/*")
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
            b.header("Cookie", it)
        }
        return if (method == "HEAD") b.head().build() else b.get().build()
    }
    try {
        previewHttpClient.newCall(build("HEAD")).execute().use { res ->
            ClLog.i(
                "probe",
                "HEAD code=${res.code} final=${ClLog.shortUrl(res.request.url.toString())} " +
                    "ct=${res.header("Content-Type")} len=${res.header("Content-Length")} " +
                    "acceptRanges=${res.header("Accept-Ranges")}",
            )
            if (res.isSuccessful) return
        }
    } catch (t: Throwable) {
        ClLog.w("probe", "HEAD 失败: ${t.message}")
    }
    try {
        previewHttpClient.newCall(build("GET")).execute().use { res ->
            val body = res.body ?: run {
                ClLog.w("probe", "GET 空 body code=${res.code}")
                return
            }
            val peek = ByteArray(32)
            val n = body.byteStream().use { input -> input.read(peek) }.coerceAtLeast(0)
            val slice = peek.copyOf(n)
            val magic = slice.joinToString(" ") { b -> "%02x".format(b) }
            val ascii = slice.map { c ->
                val ch = c.toInt().toChar()
                if (ch.isLetterOrDigit() || ch in "[]{}<>/.-_:") ch else '.'
            }.joinToString("")
            ClLog.i(
                "probe",
                "GET code=${res.code} final=${ClLog.shortUrl(res.request.url.toString())} " +
                    "ct=${res.header("Content-Type")} len=${res.header("Content-Length")} " +
                    "magic=[$magic] ascii=[$ascii]",
            )
        }
    } catch (t: Throwable) {
        ClLog.e("probe", "GET 探测失败: ${t.message}", t)
    }
}

private fun cacheVideoForPreview(
    context: Context,
    url: String,
    platform: ShortVideoPlatform,
): File {
    ClLog.i("cache", "开始下载 ${ClLog.shortUrl(url)}")
    val ua = if (platform == ShortVideoPlatform.KUAISHOU) {
        ShortVideoResolveService.MOBILE_USER_AGENT
    } else {
        ShortVideoResolveService.USER_AGENT
    }
    val reqBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", ua)
        .header("Referer", ShortVideoResolveService.refererFor(platform))
        .header("Accept", "*/*")
        .get()
    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
        reqBuilder.header("Cookie", it)
    }
    CookieManager.getInstance().getCookie(ShortVideoResolveService.refererFor(platform))
        ?.takeIf { it.isNotBlank() }
        ?.let { reqBuilder.header("Cookie", it) }

    val file = File(context.cacheDir, "preview_${url.hashCode().toUInt()}_${System.currentTimeMillis()}.mp4")
    previewHttpClient.newCall(reqBuilder.build()).execute().use { response ->
        val type = response.header("Content-Type").orEmpty()
        ClLog.i(
            "cache",
            "响应 code=${response.code} ct=$type len=${response.header("Content-Length")} " +
                "final=${ClLog.shortUrl(response.request.url.toString())}",
        )
        if (!response.isSuccessful) error("HTTP ${response.code}")
        val body = response.body ?: error("空响应")
        val typeLow = type.lowercase()
        if (typeLow.contains("text/html") || typeLow.contains("application/json")) {
            val snip = body.string().take(120).replace('\n', ' ')
            ClLog.e("cache", "非视频内容 snip=$snip")
            error("返回了非视频内容: $type")
        }
        file.outputStream().use { out ->
            body.byteStream().use { input -> input.copyTo(out) }
        }
    }
    if (file.length() < 8 * 1024) {
        ClLog.e("cache", "文件过小 size=${file.length()}")
        file.delete()
        error("文件过小")
    }
    // 打印文件头，确认是否 ftyp / #EXTM3U / JSON
    val head = ByteArray(24)
    val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
    val slice = head.copyOf(n)
    val magic = slice.joinToString(" ") { "%02x".format(it) }
    val ascii = slice.map {
        val ch = it.toInt().toChar()
        if (ch.isLetterOrDigit() || ch in "[]{}<>/.-_:#") ch else '.'
    }.joinToString("")
    ClLog.i("cache", "落盘 size=${file.length()} magic=[$magic] ascii=[$ascii]")
    return file
}

private fun previewImageRequest(
    context: Context,
    url: String,
    platform: ShortVideoPlatform,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .addHeader(
            "User-Agent",
            if (platform == ShortVideoPlatform.KUAISHOU) {
                ShortVideoResolveService.MOBILE_USER_AGENT
            } else {
                ShortVideoResolveService.USER_AGENT
            },
        )
        .addHeader("Referer", ShortVideoResolveService.refererFor(platform))
        .crossfade(true)
        .build()
}

private fun platformLabel(platform: ShortVideoPlatform): String =
    when (platform) {
        ShortVideoPlatform.DOUYIN -> "抖音"
        ShortVideoPlatform.KUAISHOU -> "快手"
    }

@Composable
private fun DebugLogPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val logLines by ClLog.lines.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1917).copy(alpha = 0.92f))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggle) {
                Text(
                    text = if (expanded) "收起调试日志 (${logLines.size})" else "展开调试日志 (${logLines.size})",
                    color = Color(0xFF99F6E4),
                    fontSize = 13.sp,
                )
            }
            TextButton(onClick = onCopy, enabled = logLines.isNotEmpty()) {
                Text("复制", color = Color.White, fontSize = 13.sp)
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (logLines.isEmpty()) {
                    "暂无日志。点「去水印」后这里会显示 [net]/[pick]/[probe]/[preview]。"
                } else {
                    logLines.takeLast(40).joinToString("\n")
                },
                color = Color(0xFFD6D3D1),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
