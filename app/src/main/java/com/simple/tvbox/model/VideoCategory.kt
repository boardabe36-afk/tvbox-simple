package com.simple.tvbox.model

/**
 * 视频分类（电视剧、电影、综艺等）。
 */
data class VideoCategory(
    /** 分类 ID，调用站点 API 时使用 */
    val id: String,
    /** 分类名（显示用） */
    val name: String
)

/**
 * 视频条目（首页/分类列表中的项）。
 */
data class VideoItem(
    /** 视频 ID */
    val id: String,
    /** 标题 */
    val title: String,
    /** 副标题/年份/季数 */
    val subTitle: String? = null,
    /** 海报 URL */
    val poster: String? = null,
    /** 站点 key（来自哪个视频网站） */
    val sourceKey: String
)

/**
 * 播放链接信息。
 */
data class PlayInfo(
    /** 视频清晰度描述（高清/标清） */
    val quality: String? = null,
    /** 播放地址（m3u8/mp4 等） */
    val url: String,
    /** 播放头（headers） */
    val headers: Map<String, String> = emptyMap()
)
