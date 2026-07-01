package com.simple.tvbox.source

import com.simple.tvbox.model.PlayInfo
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoCategory
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.util.HttpUtil
import org.json.JSONObject

/**
 * TVBox Spider 协议客户端。
 *
 * 协议要点：
 * - 入口 URL 形如 `{api}?ac=home&t={type}`
 * - 站点 type=1 时返回标准 JSON：{ "list": [...], "class": [...] }
 *   - class 项：{ "type_id": "...", "type_name": "..." }
 *   - list 项：{ "vod_id": "...", "vod_name": "...", "vod_pic": "...", "vod_remarks": "..." }
 *
 * 简版只支持 type=1 (JSON)。type=0 (XML) / type=2 (Spider jar) 暂不实现。
 */
class SpiderClient(private val site: SpiderSite) : VideoClient {

    override val key: String get() = site.key

    private companion object {
        private const val DOLLAR = "\$"
    }

    override fun isSupported(): Boolean = site.type == 1

    override fun fetchHomeCategories(): List<VideoCategory> {
        if (!isSupported()) return emptyList()
        val body = call(buildUrl(ac = "home"))
        val arr = body.optJSONArray("class") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VideoCategory(
                id = o.optString("type_id"),
                name = o.optString("type_name", "未命名")
            )
        }
    }

    override fun fetchCategory(categoryId: String, page: Int): List<VideoItem> {
        if (!isSupported()) return emptyList()
        val body = call(
            buildUrl(ac = "list", extra = mapOf("t" to categoryId, "pg" to page.toString()))
        )
        return parseVideoList(body)
    }

    override fun search(keyword: String, page: Int): List<VideoItem> {
        if (!isSupported()) return emptyList()
        val body = call(
            buildUrl(ac = "list", extra = mapOf("wd" to keyword, "pg" to page.toString()))
        )
        return parseVideoList(body)
    }

    override fun fetchEpisodes(videoId: String): List<Pair<String, String>> {
        if (!isSupported()) return emptyList()
        val body = call(buildUrl(ac = "detail", extra = mapOf("ids" to videoId)))
        val list = body.optJSONArray("list") ?: return emptyList()
        if (list.length() == 0) return emptyList()
        val vod = list.getJSONObject(0)
        val playUrl = vod.optString("vod_play_url")
        if (playUrl.isBlank()) return emptyList()

        val episodes = mutableListOf<Pair<String, String>>()
        // 多源用 $$$ 分隔；单源内集数用 # 分隔；单集用 $ 分隔名称和URL
        val sourceSep = DOLLAR.repeat(3)
        val nameUrlSep = DOLLAR
        playUrl.split(sourceSep).forEach { source ->
            source.split("#").forEach { episode ->
                val parts = episode.split(nameUrlSep)
                if (parts.size == 2) {
                    episodes.add(parts[0] to parts[1])
                }
            }
        }
        return episodes
    }

    override fun fetchDetailInfo(videoId: String): JSONObject? {
        if (!isSupported()) return null
        val body = call(buildUrl(ac = "detail", extra = mapOf("ids" to videoId)))
        return body.optJSONArray("list")?.optJSONObject(0)
    }

    override fun resolvePlayUrl(episodeUrl: String): PlayInfo {
        // 简版：直接用源返回的 URL，假定是 m3u8/mp4 直链
        return PlayInfo(quality = "默认", url = episodeUrl)
    }

    // ---- internals ----

    private fun call(url: String): JSONObject {
        val text = HttpUtil.fetchText(url, referer = site.api)
        return JSONObject(text)
    }

    private fun buildUrl(ac: String, extra: Map<String, String> = emptyMap()): String {
        val sep = if (site.api.contains("?")) "&" else "?"
        val base = sep + "ac=" + ac
        val tail = extra.entries.joinToString("&") { (k, v) -> k + "=" + v }
        return site.api + base + (if (tail.isNotEmpty()) "&" + tail else "")
    }

    private fun parseVideoList(body: JSONObject): List<VideoItem> {
        val arr = body.optJSONArray("list") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VideoItem(
                id = o.optString("vod_id"),
                title = o.optString("vod_name", "未命名"),
                subTitle = o.optString("vod_remarks").ifBlank { null },
                poster = o.optString("vod_pic").ifBlank { null },
                sourceKey = site.key
            )
        }
    }
}
