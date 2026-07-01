package com.simple.tvbox.source

import com.simple.tvbox.model.PlayInfo
import com.simple.tvbox.model.VideoCategory
import com.simple.tvbox.model.VideoItem
import org.json.JSONObject

/**
 * 视频数据源的统一接口。
 *
 * 两种实现：
 * - [SpiderClient]：TVBox JSON 协议（type=1 站点）
 * - [GenericHtmlClient]：传统 PHP 模板影视站（启发式 HTML 抓取）
 *
 * 调用方用 [VideoClientFactory.create] 拿实例，传入的 [SpiderSite] 不论来源
 * 都能用同一套调用方式消费。
 */
interface VideoClient {

    /** 站点 key（来自 [SpiderSite.key]） */
    val key: String

    /** 当前 client 是否可用（false 时调用方应跳过并提示用户） */
    fun isSupported(): Boolean

    /** 首页分类（侧边栏）。空列表 = 没有侧边栏 */
    fun fetchHomeCategories(): List<VideoCategory>

    /** 某分类下页内容 */
    fun fetchCategory(categoryId: String, page: Int = 1): List<VideoItem>

    /** 搜索关键词 */
    fun search(keyword: String, page: Int = 1): List<VideoItem>

    /** 视频详情（剧集 + 元信息） */
    fun fetchEpisodes(videoId: String): List<Pair<String, String>>

    /** 视频元信息（标题/简介/海报）。null = 没拿到 */
    fun fetchDetailInfo(videoId: String): JSONObject?

    /** 拿到实际播放地址（m3u8/mp4 直链） */
    fun resolvePlayUrl(episodeUrl: String): PlayInfo
}
