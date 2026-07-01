package com.simple.tvbox.model

/**
 * 本地观看历史 / 断点续看记录。
 *
 * 以站点 + 源 + 剧集 URL 作为唯一键，保存最后播放位置。
 * resolvedUrl 只是缓存最近一次解析到的真实播放地址，下一次播放仍优先用 episodeUrl 重新解析。
 */
data class WatchHistoryItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val siteKey: String = "",
    val sourceUrl: String = "",
    val videoId: String? = null,
    val episodeUrl: String,
    val resolvedUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)
