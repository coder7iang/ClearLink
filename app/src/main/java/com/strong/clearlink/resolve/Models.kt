package com.strong.clearlink.resolve

enum class ShortVideoPlatform {
    DOUYIN,
    KUAISHOU,
}

enum class DouyinMediaType {
    VIDEO,
    IMAGES,
}

data class DouyinResolveResult(
    val mediaId: String,
    val videoUrl: String = "",
    val title: String = "",
    val coverUrl: String? = null,
    val source: String = "",
    val platform: ShortVideoPlatform = ShortVideoPlatform.DOUYIN,
    val mediaType: DouyinMediaType = DouyinMediaType.VIDEO,
    val imageUrls: List<String> = emptyList(),
) {
    val isImageNote: Boolean
        get() = mediaType == DouyinMediaType.IMAGES && imageUrls.isNotEmpty()
}

data class KuaishouResolveMeta(
    val photoId: String,
    val finalUrl: String,
    val isAtlas: Boolean,
)
