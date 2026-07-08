package com.simple.tvbox.source

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 豆瓣数据服务。
 *
 * 重要：豆瓣**只提供 metadata**（标题、海报、评分、详情 URL），不提供播放源。
 * 因此本服务**不实现 VideoClient 接口**——那是真正的"视频源"接口。
 *
 * 本服务专注于：
 * 1. 拉取豆瓣热门 / 最新 / 各种标签的电影/电视剧列表
 * 2. 提供"豆瓣卡片"，由上层（HomeFragment/DoubanActivity）触发"用标题去全源搜"
 *
 * 数据来源（无 key，长期可用）：
 *  - https://movie.douban.com/j/search_subjects?type=movie&tag=热门&page_limit=50&page_start=0
 *  - https://movie.douban.com/j/search_subjects?type=tv&tag=热门&page_limit=50&page_start=0
 *  - https://movie.douban.com/j/search_subjects?type=movie&tag=最新&...
 *
 * 风险与缓解：
 *  - 豆瓣对移动端 UA + Referer 没要求，但 web UA + Referer 双保险最稳
 *  - 失败返回空列表，UI 层做空状态；不抛异常
 */
object DoubanService {

    /**
     * 豆瓣条目（搜索/列表通用）
     */
    data class DoubanItem(
        /** 豆瓣 ID（subject id） */
        val id: String,
        /** 标题（已去除前后空格） */
        val title: String,
        /** 评分（0-10，可能为空） */
        val rate: String,
        /** 海报 URL */
        val cover: String,
        /** 详情页 URL（https://movie.douban.com/subject/{id}/） */
        val detailUrl: String,
        /** 类型：movie / tv */
        val type: Type,
        /** 是否为新条目（is_new=true） */
        val isNew: Boolean,
        /** 是否可播放（playable=true，通常是豆瓣有片源） */
        val playable: Boolean
    ) {
        enum class Type { MOVIE, TV }

        /** 转 VideoItem（用作首页卡片显示） */
        fun toVideoItem(siteKey: String = SITE_KEY): VideoItem =
            VideoItem(
                id = id,
                title = title,
                subTitle = buildString {
                    if (rate.isNotBlank()) {
                        append("豆瓣 ").append(rate)
                    }
                    if (isNew) {
                        if (isNotEmpty()) append(" · ")
                        append("新上映")
                    }
                }.ifEmpty { null },
                poster = cover.ifBlank { null },
                sourceKey = siteKey
            )
    }

    private const val SITE_KEY = "douban://builtin"

    /** 豆瓣电影类型 */
    enum class DoubanMediaType(val apiValue: String) {
        MOVIE("movie"),
        TV("tv")
    }

    /** 预置标签（豆瓣原生支持的） */
    val MOVIE_TAGS = listOf(
        "热门", "最新", "经典", "豆瓣高分", "冷门佳片",
        "华语", "欧美", "韩国", "日本",
        "动作", "喜剧", "爱情", "科幻", "悬疑", "恐怖", "治愈"
    )
    val TV_TAGS = listOf(
        "热门", "最新", "经典", "豆瓣高分", "冷门佳片",
        "华语", "欧美", "韩国", "日本",
        "剧情", "喜剧", "悬疑", "动作", "爱情", "科幻", "犯罪", "动画"
    )

    private val gson = Gson()
    private val jsonParser = JSONObject()

    /**
     * 拉取豆瓣某个类型的某个标签下的视频列表。
     *
     * @param type  电影/电视剧
     * @param tag   中文标签（豆瓣原生：热门/最新/经典/...）
     * @param limit 拉取条数（page_limit），最多 50
     * @param page  页码（page_start = (page-1)*limit）
     */
    suspend fun fetch(
        type: DoubanMediaType,
        tag: String,
        limit: Int = 30,
        page: Int = 1
    ): List<DoubanItem> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://movie.douban.com/j/search_subjects" +
                "?type=${type.apiValue}" +
                "&tag=" + java.net.URLEncoder.encode(tag, "UTF-8") +
                "&page_limit=" + limit.coerceIn(1, 50) +
                "&page_start=" + ((page - 1).coerceAtLeast(0) * limit.coerceIn(1, 50))
            val text = HttpUtil.fetchDoubanJson(url)
            parseList(text, type)
        }.getOrDefault(emptyList())
    }

    /**
     * 并发拉取多个标签（首页豆瓣行常用：热门 + 最新 + Top250）
     */
    suspend fun fetchMultiple(
        type: DoubanMediaType,
        tags: List<String>,
        perTagLimit: Int = 20
    ): Map<String, List<DoubanItem>> = coroutineScope {
        tags.map { tag ->
            async {
                tag to fetch(type, tag, perTagLimit, 1)
            }
        }.map { it.await() }.toMap()
    }

    /**
     * 豆瓣 Top250（不走 j/search_subjects，走另一个接口）
     * https://movie.douban.com/top250?start=0&limit=50
     * 但这种 HTML 接口难解析；改用 j/search_subjects 兜底：tag=豆瓣高分 + limit 50
     */
    suspend fun fetchTop250(type: DoubanMediaType = DoubanMediaType.MOVIE, limit: Int = 50): List<DoubanItem> =
        fetch(type, "豆瓣高分", limit, 1)

    /**
     * 解析 j/search_subjects 返回的 JSON。
     */
    private fun parseList(text: String, type: DoubanMediaType): List<DoubanItem> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { jsonParser.getJSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("subjects") ?: return emptyList()
        val out = ArrayList<DoubanItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val cover = obj.optString("cover").trim()
            val url = obj.optString("url").trim().ifEmpty {
                "https://movie.douban.com/subject/$id/"
            }
            out.add(
                DoubanItem(
                    id = id,
                    title = title,
                    rate = obj.optString("rate", "").trim(),
                    cover = cover,
                    detailUrl = url,
                    type = if (type == DoubanMediaType.MOVIE)
                        DoubanItem.Type.MOVIE else DoubanItem.Type.TV,
                    isNew = obj.optBoolean("is_new", false),
                    playable = obj.optBoolean("playable", false)
                )
            )
        }
        return out
    }

    /**
     * 内部数据类（仅 Gson 反序列化用），目前没用到，留扩展位
     */
    @Suppress("unused")
    private data class RawSubjects(
        @SerializedName("subjects")
        val subjects: List<RawItem> = emptyList()
    )

    @Suppress("unused")
    private data class RawItem(
        @SerializedName("id") val id: String = "",
        @SerializedName("title") val title: String = "",
        @SerializedName("rate") val rate: String = "",
        @SerializedName("cover") val cover: String = "",
        @SerializedName("url") val url: String = "",
        @SerializedName("is_new") val isNew: Boolean = false,
        @SerializedName("playable") val playable: Boolean = false
    )
}