package com.simple.tvbox.source

import com.simple.tvbox.model.PlayInfo
import com.simple.tvbox.model.VideoCategory
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.util.HttpUtil
import java.io.IOException

/**
 * 通用 HTML 影视站适配器。
 *
 * 支持多种 maccms 衍生模板：canghai / stui / 默认 maccms，以及 baiu.cc 这类
 * /vodtype/{id}.html + /voddetail/{id}.html + /vodplay/{id}-{line}-{ep}.html 的 mx 变体。
 */
class GenericHtmlClient(rawRoot: String) : VideoClient {

    override val key: String = "html_" + extractHost(rawRoot)

    private val baseUrl: String = normalizeRoot(rawRoot)
    private var template: Template = Template.UNKNOWN
    private var probed = false
    private var homeHtml: String = ""

    private fun ensureProbed() {
        if (probed) return
        probed = true
        val html = try {
            HttpUtil.fetchText(baseUrl, referer = baseUrl)
        } catch (t: Throwable) {
            throw IOException("站点探测失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        homeHtml = html
        template = detectTemplate(html)
    }

    override fun isSupported(): Boolean = true

    override fun fetchHomeCategories(): List<VideoCategory> {
        ensureProbed()
        if (template == Template.UNKNOWN) throw IOException("暂未识别该 HTML 站点模板")
        return parseCategories(homeHtml)
    }

    override fun fetchCategory(categoryId: String, page: Int): List<VideoItem> {
        ensureProbed()
        if (template == Template.UNKNOWN) return emptyList()
        val url = buildCategoryUrl(categoryId, page)
        val html = runCatching { HttpUtil.fetchText(url, referer = baseUrl) }.getOrNull() ?: return emptyList()
        return parseVideoList(html, baseUrl)
    }

    override fun search(keyword: String, page: Int): List<VideoItem> {
        ensureProbed()
        if (template == Template.UNKNOWN) return emptyList()
        val encoded = encodeQuery(keyword)
        val candidates = listOf(
            "$baseUrl/vodsearch/$encoded-------------.html",
            "$baseUrl/index.php/vod/search.html?wd=$encoded",
            "$baseUrl/search/$encoded.html",
            "$baseUrl/search.php?wd=$encoded",
            "$baseUrl/index.php?m=vod-search&wd=$encoded",
        )
        for (url in candidates) {
            val html = runCatching { HttpUtil.fetchText(url, referer = baseUrl) }.getOrNull() ?: continue
            val items = parseVideoList(html, baseUrl)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override fun fetchEpisodes(videoId: String): List<Pair<String, String>> {
        ensureProbed()
        if (template == Template.UNKNOWN) return emptyList()
        val detailUrl = buildDetailUrl(videoId)
        val html = runCatching { HttpUtil.fetchText(detailUrl, referer = baseUrl) }.getOrNull()
            ?: return listOf("正片" to "$baseUrl/vodplay/$videoId-1-1.html")
        val episodes = parseEpisodes(html, baseUrl)
        return episodes.ifEmpty { listOf("正片" to "$baseUrl/vodplay/$videoId-1-1.html") }
    }

    override fun fetchDetailInfo(videoId: String): JSONObject? {
        ensureProbed()
        if (template == Template.UNKNOWN) return null
        val detailUrl = buildDetailUrl(videoId)
        val html = runCatching { HttpUtil.fetchText(detailUrl, referer = baseUrl) }.getOrNull() ?: return null
        val title = extractDetailTitle(html, videoId)
        val desc = extractDetailDesc(html)
        val poster = extractDetailPoster(html, baseUrl)
        return JSONObject().apply {
            put("vod_name", title)
            put("vod_pic", poster)
            put("vod_content", desc)
        }
    }

    override fun resolvePlayUrl(episodeUrl: String): PlayInfo {
        val absolute = absolutize(episodeUrl, baseUrl)
        if (isDirectMediaUrl(absolute)) return PlayInfo(quality = "默认", url = absolute)
        val html = runCatching { HttpUtil.fetchText(absolute, referer = baseUrl) }
            .getOrElse { throw IOException("播放页请求失败：${it.message ?: it.javaClass.simpleName}", it) }
        val direct = extractM3u8FromPlayPage(html)
        if (!direct.isNullOrBlank()) {
            val followed = HttpUtil.followRedirects(direct, referer = baseUrl)
            val finalUrl = resolveMasterPlaylist(followed) ?: followed
            return PlayInfo(quality = "默认", url = finalUrl)
        }
        val iframeUrl = extractUrlFromIframe(html)
        if (!iframeUrl.isNullOrBlank()) {
            val followed = HttpUtil.followRedirects(iframeUrl, referer = baseUrl)
            val finalUrl = resolveMasterPlaylist(followed) ?: followed
            return PlayInfo(quality = "默认", url = finalUrl)
        }
        throw IOException("未找到真实播放地址（m3u8/mp4），该视频源可能已失效")
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun detectTemplate(html: String): Template {
        val lower = html.lowercase()
        return when {
            lower.contains("canghai") || html.contains("stui-pannel") -> Template.MACCMS_CANGHAI
            lower.contains("stui") || html.contains("stui-vodlist") -> Template.MACCMS_STUI
            lower.contains("/vodtype/") || lower.contains("/vodplay/") || lower.contains("/voddetail/") -> Template.MACCMS_MX
            lower.contains("/index.php/vod/") || lower.contains("mac.php") || lower.contains("maccms") -> Template.MACCMS_DEFAULT
            html.contains(".html") -> Template.GENERIC
            else -> Template.UNKNOWN
        }
    }

    private fun parseCategories(html: String): List<VideoCategory> {
        val cats = LinkedHashMap<String, String>()
        Regex("""href\s*=\s*["']([^"']*sortlist/(\d+)\.html)["'][^>]*>([^<]{1,20})</a>""")
            .findAll(html).forEach { m ->
                val id = m.groupValues[2]
                val name = m.groupValues[3].trim()
                if (isValidCategoryName(name) && id !in cats) cats[id] = name
            }
        Regex("""<a\b([^>]*href\s*=\s*["'][^"']*vodtype/(\d+)\.html["'][^>]*)>([\s\S]{0,240}?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val attrs = m.groupValues[1]
                val inner = m.groupValues[3]
                val id = m.groupValues[2]
                val titleName = Regex("""title\s*=\s*["']([^"']{1,20})["']""").find(attrs)?.groupValues?.get(1)?.trim()
                val spanName = Regex("""<span[^>]*>([^<]{1,20})</span>""").find(inner)?.groupValues?.get(1)?.trim()
                val plainName = stripHtml(inner).substringBefore(" ").trim()
                val name = listOf(titleName, spanName, plainName).firstOrNull { !it.isNullOrBlank() && isValidCategoryName(it) }
                if (!name.isNullOrBlank() && id !in cats) cats[id] = name
            }
        if (cats.isEmpty()) {
            Regex("""href\s*=\s*["']([^"']*(?:list|type|show|vodtype)/(\d+)\.html)["'][^>]*>([^<]{1,20})</a>""")
                .findAll(html).forEach { m ->
                    val id = m.groupValues[2]
                    val name = m.groupValues[3].trim()
                    if (isValidCategoryName(name) && id !in cats) cats[id] = name
                }
        }
        return cats.entries.take(20).map { VideoCategory(id = it.key, name = it.value) }
    }

    private fun parseVideoList(html: String, base: String): List<VideoItem> {
        val items = LinkedHashMap<String, VideoItem>()

        val aBlockPat = Regex(
            """<a\b([^>]*?href\s*=\s*["']([^"']*?(?:shipin|detail|show|vod)/(\d+)\.html)["'][^>]*?)>([\s\S]*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        aBlockPat.findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            val id = m.groupValues[3]
            val inner = m.groupValues[4]
            val posterMatch = Regex("""(?:data-original|data-src)\s*=\s*["']([^"']+)["']""").find(attrs)
            val title = Regex("""title\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)?.trim()
                ?: extractTitleFromInner(inner)
            if (title.isNotBlank() && id.isNotBlank()) {
                items.putIfAbsent(id, VideoItem(id, title, extractSubTitleFromInner(inner), posterMatch?.groupValues?.get(1)?.let { absolutize(it, base) }, key))
            }
        }

        if (items.isEmpty()) {
            val defaultPat = Regex(
                """<a\b([^>]*?href\s*=\s*["']([^"']*/vod/detail/id/(\d+)\.html)["'][^>]*?)>([\s\S]*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            defaultPat.findAll(html).forEach { m ->
                val attrs = m.groupValues[1]
                val id = m.groupValues[3]
                val inner = m.groupValues[4]
                val posterMatch = Regex("""(?:data-original|data-src)\s*=\s*["']([^"']+)["']""").find(attrs)
                val title = Regex("""title\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)?.trim()
                    ?: extractTitleFromInner(inner)
                if (title.isNotBlank() && id.isNotBlank()) {
                    items.putIfAbsent(id, VideoItem(id, title, null, posterMatch?.groupValues?.get(1)?.let { absolutize(it, base) }, key))
                }
            }
        }

        if (items.isEmpty()) {
            val vodPlayPat = Regex(
                """<a\b([^>]*?href\s*=\s*["']([^"']*vodplay/(\d+)-\d+-\d+\.html)["'][^>]*?)>([\s\S]*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            vodPlayPat.findAll(html).forEach { m ->
                val attrs = m.groupValues[1]
                val id = m.groupValues[3]
                val inner = m.groupValues[4]
                val posterMatch = Regex("""(?:data-original|data-src|src)\s*=\s*["']([^"']+)["']""").find(attrs + inner)
                val title = Regex("""title\s*=\s*["'](?:播放|立刻播放)?([^"']{1,100}?)(?:正片|第\d+集)?["']""")
                    .find(attrs)?.groupValues?.get(1)?.trim()
                    ?: Regex("""module-poster-item-title[^>]*>([^<]{1,100})<""").find(inner)?.groupValues?.get(1)?.trim()
                    ?: extractTitleFromInner(inner)
                val subTitle = Regex("""module-item-note[^>]*>([^<]{1,30})<""").find(inner)?.groupValues?.get(1)?.trim()
                    ?: extractSubTitleFromInner(inner)
                if (title.isNotBlank() && id.isNotBlank()) {
                    items.putIfAbsent(id, VideoItem(id, title, subTitle, posterMatch?.groupValues?.get(1)?.let { absolutize(it, base) }, key))
                }
            }
        }

        return items.values.toList()
    }

    private fun parseEpisodes(html: String, base: String): List<Pair<String, String>> {
        val eps = mutableListOf<Pair<String, String>>()
        val container = run {
            val patterns = listOf(
                """module-play-list[\s\S]{0,12000}</div>""",
                """stui-content__playlist[\s\S]*?</ul>""",
                """playlist[\s\S]{0,5000}?</ul>"""
            )
            patterns.asSequence().mapNotNull { Regex(it, RegexOption.DOT_MATCHES_ALL).find(html)?.value }.firstOrNull()
        }
        val scope = container ?: html

        Regex("""href\s*=\s*["']([^"']*movie/(\d+)/(\d+)\.html)["'][^>]*>([^<]{1,50})</a>""")
            .findAll(scope).forEach { m -> eps.add(m.groupValues[4].trim().ifBlank { "第${eps.size + 1}集" } to absolutize(m.groupValues[1], base)) }
        if (eps.isEmpty()) {
            Regex("""href\s*=\s*["']([^"']*vodplay/\d+-\d+-(\d+)\.html)["'][^>]*(?:title\s*=\s*["'](?:播放)?([^"']{0,50})["'])?[^>]*>([\s\S]{0,80}?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(scope).forEach { m ->
                    val visible = stripHtml(m.groupValues[4]).trim()
                    val title = m.groupValues[3].trim()
                    val name = visible.ifBlank { title.substringAfterLast(' ').ifBlank { "第${m.groupValues[2]}集" } }
                    eps.add(name to absolutize(m.groupValues[1], base))
                }
        }
        if (eps.isEmpty()) {
            Regex("""href\s*=\s*["']([^"']*(?:play|videoplay)/(\d+)(?:[-/](\d+))?\.html)["'][^>]*>([^<]{1,50})</a>""")
                .findAll(scope).forEach { m -> eps.add(m.groupValues[4].trim().ifBlank { "第${eps.size + 1}集" } to absolutize(m.groupValues[1], base)) }
        }
        if (eps.isEmpty()) {
            Regex("""href\s*=\s*["']([^"']*/vod/play/id/\d+(?:/n/\d+)?\.html)["'][^>]*>([^<]{1,50})</a>""")
                .findAll(scope).forEach { m -> eps.add(m.groupValues[2].trim().ifBlank { "第${eps.size + 1}集" } to absolutize(m.groupValues[1], base)) }
        }
        if (eps.isEmpty()) {
            Regex("""href\s*=\s*["']([^"']+\.html)["'][^>]*>([^<]{0,30}第\d+集[^<]{0,30})</a>""")
                .findAll(scope).forEach { m -> eps.add(m.groupValues[2].trim() to absolutize(m.groupValues[1], base)) }
        }
        return eps.distinctBy { it.second }
    }

    private fun extractM3u8FromPlayPage(html: String): String? {
        Regex("""thisUrl\s*=\s*["']([^"']+)["']""").find(html)?.let {
            val v = unescapeJsString(it.groupValues[1])
            if (v.isNotBlank() && v != "undefined") return v
        }
        Regex("""player_aaaa\s*=\s*\{[\s\S]*?["']?url["']?\s*:\s*["']([^"']+)["']""").find(html)?.let {
            val v = decodeMaybeEncodedUrl(unescapeJsString(it.groupValues[1]))
            if (v.isNotBlank() && v != "undefined") return v
        }
        Regex("""MacPlayer[\s\S]{0,2000}?["']?url["']?\s*:\s*["']([^"']+)["']""").find(html)?.let {
            val v = decodeMaybeEncodedUrl(unescapeJsString(it.groupValues[1]))
            if (v.isNotBlank()) return v
        }
        Regex("""["'](https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)["']""").find(html)?.let { return it.groupValues[1] }
        Regex("""["'](https?://[^\s"'<>]+\.mp4[^\s"'<>]*)["']""").find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractUrlFromIframe(html: String): String? {
        Regex("""<iframe[^>]+src\s*=\s*["']([^"']*(?:url|v)=([^&"']+))""").find(html)?.let {
            val raw = java.net.URLDecoder.decode(it.groupValues[2], "UTF-8")
            if (raw.startsWith("http") && (raw.contains(".m3u8") || raw.contains(".mp4"))) return raw
        }
        return null
    }

    private fun extractDetailTitle(html: String, fallback: String): String {
        Regex("""<h1[^>]*>([^<]{1,100})</h1>""").find(html)?.let { return it.groupValues[1].trim().ifBlank { fallback } }
        Regex("""<h3[^>]*class\s*=\s*["'][^"']*\btitle\b[^"']*["'][^>]*>([^<]{1,100})</h3>""").find(html)?.let { return it.groupValues[1].trim().ifBlank { fallback } }
        Regex("""<title>([^<]{1,100})</title>""").find(html)?.let {
            return it.groupValues[1].substringBefore('-').substringBefore('_').substringBefore('｜').substringBefore('|').trim().ifBlank { fallback }
        }
        return fallback
    }

    private fun extractDetailDesc(html: String): String {
        Regex("""<div[^>]+class\s*=\s*["'][^"']*module-info-introduction-content[^"']*["'][^>]*>([\s\S]{20,2000}?)</div>""")
            .find(html)?.let { return stripHtml(it.groupValues[1]).trim() }
        Regex("""<div[^>]+class\s*=\s*["'][^"']*detail-content[^"']*["'][^>]*>([\s\S]{20,2000}?)</div>""")
            .find(html)?.let { return stripHtml(it.groupValues[1]).trim() }
        Regex("""<div[^>]+class\s*=\s*["'][^"']*stui-content__desc[^"']*["'][^>]*>([\s\S]{20,2000}?)</div>""")
            .find(html)?.let { return stripHtml(it.groupValues[1]).trim() }
        Regex("""<meta\s+name\s*=\s*["']description["']\s+content\s*=\s*["']([^"']{10,500})["']""")
            .find(html)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun extractDetailPoster(html: String, base: String): String? {
        Regex("""class\s*=\s*["'][^"']*module-info-poster[^"']*["'][\s\S]{0,2000}?data-original\s*=\s*["']([^"']+)""")
            .find(html)?.let { return absolutize(it.groupValues[1], base) }
        Regex("""class\s*=\s*["']stui-vodlist__thumb["'][\s\S]{0,2000}?data-original\s*=\s*["']([^"']+)""")
            .find(html)?.let { return absolutize(it.groupValues[1], base) }
        Regex("""class\s*=\s*["']detail-pic["'][\s\S]{0,500}?src\s*=\s*["']([^"']+)""")
            .find(html)?.let { return absolutize(it.groupValues[1], base) }
        Regex("""<img[^>]+(?:data-original|data-src|src)\s*=\s*["']([^"']+)""")
            .find(html)?.let { return absolutize(it.groupValues[1], base) }
        return null
    }

    private fun buildCategoryUrl(categoryId: String, page: Int): String {
        return when (template) {
            Template.MACCMS_MX -> if (page <= 1) "$baseUrl/vodtype/$categoryId.html" else "$baseUrl/vodtype/$categoryId-$page.html"
            else -> if (page <= 1) "$baseUrl/sortlist/$categoryId.html" else "$baseUrl/sortlist/$categoryId/last-$page.html"
        }
    }

    private fun buildDetailUrl(videoId: String): String {
        return when (template) {
            Template.MACCMS_MX -> "$baseUrl/voddetail/$videoId.html"
            else -> "$baseUrl/shipin/$videoId.html"
        }
    }

    private fun resolveMasterPlaylist(m3u8Url: String): String? {
        return runCatching {
            val content = HttpUtil.fetchText(m3u8Url, referer = baseUrl)
            if (!content.contains("#EXT-X-STREAM-INF")) return@runCatching null
            val sub = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@runCatching null
            resolveUrlAgainst(m3u8Url, sub)
        }.getOrNull()
    }

    private fun normalizeRoot(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url
    }

    private fun extractHost(raw: String): String {
        return runCatching { java.net.URL(if (raw.startsWith("http")) raw else "https://$raw").host }
            .getOrDefault(raw).replace(".", "_")
    }

    private fun absolutize(href: String, base: String): String {
        if (href.isBlank()) return base
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("/")) return base + href
        return base + "/" + href
    }

    private fun resolveUrlAgainst(base: String, href: String): String {
        if (href.isBlank()) return base
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("//")) return "https:$href"
        return runCatching { java.net.URL(java.net.URL(base), href).toString() }.getOrElse { absolutize(href, baseUrl) }
    }

    private fun isValidCategoryName(name: String): Boolean {
        if (name.isBlank() || name.length < 2 || name.length > 20) return false
        if (name.all { it.isDigit() }) return false
        if (name.contains('<')) return false
        val nav = setOf("更多", "首页", "上一页", "下一页", "尾页", "more")
        if (name in nav) return false
        return true
    }

    private fun extractTitleFromInner(inner: String): String {
        Regex("""<a[^>]+title\s*=\s*["']([^"']{1,100})["'][^>]*>([^<]{1,100})</a>""").find(inner)
            ?.let { return it.groupValues[1].trim().ifBlank { it.groupValues[2].trim() } }
        Regex("""module-poster-item-title[^>]*>([^<]{1,100})<""").find(inner)?.let { return it.groupValues[1].trim() }
        Regex("""pic-text[^>]*>([^<]{1,100})<""").find(inner)?.let { return it.groupValues[1].trim() }
        Regex(""">([^<]{1,100})<""").find(inner)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun extractSubTitleFromInner(inner: String): String? {
        val m = Regex("""pic-text[^>]*>([^<]{1,30})<""").find(inner) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private fun encodeQuery(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun decodeMaybeEncodedUrl(s: String): String {
        val v = s.trim()
        return if (v.contains("%")) runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v) else v
    }

    private fun unescapeJsString(s: String): String = s.replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")

    private fun stripHtml(s: String): String = s.replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()

    private enum class Template { MACCMS_CANGHAI, MACCMS_STUI, MACCMS_DEFAULT, MACCMS_MX, GENERIC, UNKNOWN }
}

private typealias JSONObject = org.json.JSONObject
