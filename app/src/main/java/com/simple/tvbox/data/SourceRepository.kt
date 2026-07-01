package com.simple.tvbox.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simple.tvbox.model.Source
import com.simple.tvbox.model.SpiderConfig
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户配置的源仓库。
 *
 * 职责：
 * 1. SharedPreferences 持久化 [Source] 列表（JSON 编码）
 * 2. 拉取源 URL -> base64 解码 -> [SpiderConfig]（JSON 源）
 *    或自动探测 HTML 模板（HTML 源）
 * 3. 缓存已解析的 [SpiderSite] 列表给 UI 层使用
 *
 * HTML 源在 [loadAllSites] 时会包装成一个虚拟 SpiderSite（api 前缀 `html://`），
 * 由 [com.simple.tvbox.source.VideoClientFactory] 决定走哪个 client。
 */
class SourceRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val sources: MutableList<Source>
        get() {
            val raw = prefs.getString(KEY_SOURCES, null) ?: return mutableListOf()
            val type = object : TypeToken<MutableList<Source>>() {}.type
            return runCatching { gson.fromJson<MutableList<Source>>(raw, type) }
                .getOrNull() ?: mutableListOf()
        }

    /** 已解析的 SpiderConfig（JSON 源）按源 URL 缓存 */
    private val configCache = mutableMapOf<String, SpiderConfig>()

    /** HTML 源包装出来的虚拟 SpiderSite 是否已验证可用 */
    private val htmlSiteCache = mutableMapOf<String, SpiderSite>()

    fun getAllSources(): List<Source> = sources.toList()

    /**
     * 清空所有缓存（已加载的 JSON 源配置 + 已探测的 HTML 源）。
     * 下次 [loadAllSites] 会重新拉取所有源。
     */
    fun invalidateCache() {
        configCache.clear()
        htmlSiteCache.clear()
    }

    fun addSource(source: Source): Boolean {
        val list = sources
        if (list.none { it.url == source.url }) {
            list.add(source)
            saveSources(list)
            return true
        }
        return false
    }

    fun removeSource(source: Source) {
        val list = sources
        if (list.removeAll { it.url == source.url }) {
            saveSources(list)
            configCache.remove(source.url)
            htmlSiteCache.remove(source.url)
        }
    }

    private fun saveSources(list: List<Source>) {
        prefs.edit()
            .putString(KEY_SOURCES, gson.toJson(list))
            .apply()
    }

    /**
     * 测试源 URL 是否可用。
     * - JSON 源：解析 base64-JSON
     * - HTML 源：探测首页是否含可识别模板
     */
    suspend fun testAndLoad(source: Source): SpiderSite = withContext(Dispatchers.IO) {
        when (source.kind) {
            Source.Kind.JSON -> {
                val cfg = loadConfigFromUrl(source.url).also {
                    configCache[source.url] = it
                }
                // 选第一个站点作为"代表"返回
                cfg.sites.firstOrNull() ?: throw IllegalStateException("源里没有站点")
            }
            Source.Kind.HTML -> {
                // 注意：HttpUtil 会自动检测 GBK / UTF-8，无需在这里关心编码
                val html = HttpUtil.fetchText(source.url, referer = source.url)
                val template = detectHtmlTemplate(html)
                if (template == HtmlTemplate.UNKNOWN) {
                    throw IllegalStateException("未识别的网站模板（仅支持传统 PHP 模板影视站）")
                }
                SpiderSite(
                    key = htmlSiteKey(source.url),
                    name = source.name,
                    type = 1,
                    api = "html://" + normalizeRoot(source.url),
                    searchable = 1
                ).also { htmlSiteCache[source.url] = it }
            }
        }
    }

    /**
     * 加载所有源的 SpiderSite 汇总（去重 key）。
     * 用于首页展示"所有可用的视频网站"。
     */
    suspend fun loadAllSites(): List<SpiderSite> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, SpiderSite>()
        for (src in sources) {
            runCatching { loadOneSite(src) }
                .getOrNull()
                ?.forEach { merged.putIfAbsent(it.key, it) }
        }
        merged.values.toList()
    }

    private fun loadOneSite(src: Source): List<SpiderSite> {
        return when (src.kind) {
            Source.Kind.JSON -> {
                val cfg = configCache[src.url] ?: runCatching { loadConfigFromUrl(src.url) }
                    .onSuccess { configCache[src.url] = it }
                    .getOrNull() ?: return emptyList()
                cfg.sites
            }
            Source.Kind.HTML -> {
                val cached = htmlSiteCache[src.url]
                if (cached != null) return listOf(cached)
                val built: SpiderSite? = runCatching {
                    val html = HttpUtil.fetchText(src.url, referer = src.url)
                    if (detectHtmlTemplate(html) == HtmlTemplate.UNKNOWN) null
                    else SpiderSite(
                        key = htmlSiteKey(src.url),
                        name = src.name,
                        type = 1,
                        api = "html://" + normalizeRoot(src.url),
                        searchable = 1
                    ).also { htmlSiteCache[src.url] = it }
                }.getOrNull()
                if (built != null) listOf(built) else emptyList()
            }
        }
    }

    /**
     * 根据源链接 + 站点 key 获取 SpiderSite。
     *
     * 修复历史：之前 `?: return null` 在 configCache 缺失时直接返回，
     * 导致 HTML 源（不进 configCache）查不到自己的 SpiderSite。
     * 现在改为：HTML 源用缓存（命中）或即时构造（未命中）。
     */
    fun findSite(sourceUrl: String, siteKey: String): SpiderSite? {
        // JSON 源
        configCache[sourceUrl]?.let { cfg ->
            cfg.sites.firstOrNull { it.key == siteKey }?.let { return it }
        }
        // HTML 源：先用缓存；缓存里没就即时构造（无需网络）
        htmlSiteCache[sourceUrl]?.takeIf { it.key == siteKey }?.let { return it }
        // 还没找到：如果 sourceUrl 是 HTML 源（缓存里以 html_ 开头或者直接走源 url），
        // 就地构造返回（不依赖 key 一致，因为 HTML 源只有 1 个虚拟站点）
        if (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
            return SpiderSite(
                key = siteKey,
                name = siteKey,
                type = 1,
                api = "html://" + normalizeRoot(sourceUrl),
                searchable = 1
            )
        }
        return null
    }

    private fun loadConfigFromUrl(url: String): SpiderConfig {
        val raw = HttpUtil.fetchText(url)
        val json = decodeConfig(raw)
        return gson.fromJson(json, SpiderConfig::class.java)
            ?: throw IllegalStateException("源 JSON 解析失败")
    }

    private fun decodeConfig(raw: String): String {
        val trimmed = raw.trim()
        val looksLikeBase64 = trimmed.none { it == '{' || it == '[' }
        return if (looksLikeBase64) {
            String(Base64.decode(trimmed, Base64.DEFAULT))
        } else {
            trimmed
        }
    }

    private fun detectHtmlTemplate(html: String): HtmlTemplate {
        val lower = html.lowercase()
        return when {
            // canghai 模板：路径里带 canghai 或 stui-pannel
            lower.contains("canghai") || html.contains("stui-pannel") -> HtmlTemplate.MACCMS_CANGHAI
            // stui 模板
            lower.contains("stui") || html.contains("stui-vodlist") -> HtmlTemplate.MACCMS_STUI
            // 通用 maccms
            lower.contains("/index.php/vod/") || lower.contains("mac.php") -> HtmlTemplate.MACCMS_DEFAULT
            // 退路：能找到列表链接就当通用
            html.contains("stui-vodlist") -> HtmlTemplate.GENERIC
            else -> HtmlTemplate.UNKNOWN
        }
    }

    private fun normalizeRoot(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    fun htmlSiteKey(raw: String): String = "html_" + extractHostKey(raw)

    private fun extractHostKey(raw: String): String =
        runCatching {
            val u = java.net.URL(if (raw.startsWith("http")) raw else "https://$raw")
            u.host.replace(".", "_")
        }.getOrDefault(raw.hashCode().toString())

    private enum class HtmlTemplate { MACCMS_CANGHAI, MACCMS_STUI, MACCMS_DEFAULT, GENERIC, UNKNOWN }

    companion object {
        private const val PREFS_NAME = "tvbox_simple_prefs"
        private const val KEY_SOURCES = "user_sources"
    }
}

