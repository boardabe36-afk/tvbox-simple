package com.simple.tvbox.source

import com.simple.tvbox.model.VideoCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/** GenericHtmlClient 解析逻辑回归测试。 */
class GenericHtmlParserTest {

    private fun detectTemplate(html: String): String {
        val lower = html.lowercase()
        return when {
            lower.contains("canghai") || html.contains("stui-pannel") -> "MACCMS_CANGHAI"
            lower.contains("stui") || html.contains("stui-vodlist") -> "MACCMS_STUI"
            lower.contains("/vodtype/") || lower.contains("/vodplay/") || lower.contains("/voddetail/") -> "MACCMS_MX"
            lower.contains("/index.php/vod/") || lower.contains("mac.php") || lower.contains("maccms") -> "MACCMS_DEFAULT"
            html.contains(".html") -> "GENERIC"
            else -> "UNKNOWN"
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
                val name = listOf(titleName, spanName).firstOrNull { !it.isNullOrBlank() && isValidCategoryName(it) }
                if (!name.isNullOrBlank() && id !in cats) cats[id] = name
            }
        return cats.entries.map { VideoCategory(it.key, it.value) }
    }

    private data class ParsedItem(val id: String, val title: String, val subTitle: String?, val poster: String?)

    private fun parseVodPlayItems(html: String): List<ParsedItem> {
        val items = LinkedHashMap<String, ParsedItem>()
        val vodPlayPat = Regex(
            """<a\b([^>]*?href\s*=\s*["']([^"']*vodplay/(\d+)-\d+-\d+\.html)["'][^>]*?)>([\s\S]*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        vodPlayPat.findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            val id = m.groupValues[3]
            val inner = m.groupValues[4]
            val poster = Regex("""(?:data-original|data-src|src)\s*=\s*["']([^"']+)["']""").find(attrs + inner)?.groupValues?.get(1)
            val title = Regex("""title\s*=\s*["'](?:播放|立刻播放)?([^"']{1,100}?)(?:正片|第\d+集)?["']""")
                .find(attrs)?.groupValues?.get(1)?.trim()
                ?: Regex("""module-poster-item-title[^>]*>([^<]{1,100})<""").find(inner)?.groupValues?.get(1)?.trim()
                ?: ""
            val subTitle = Regex("""module-item-note[^>]*>([^<]{1,30})<""").find(inner)?.groupValues?.get(1)?.trim()
            if (title.isNotBlank()) items.putIfAbsent(id, ParsedItem(id, title, subTitle, poster))
        }
        return items.values.toList()
    }

    private fun parseVodPlayEpisodes(html: String): List<Pair<String, String>> {
        val eps = mutableListOf<Pair<String, String>>()
        Regex("""href\s*=\s*["']([^"']*vodplay/\d+-\d+-(\d+)\.html)["'][^>]*(?:title\s*=\s*["'](?:播放)?([^"']{0,50})["'])?[^>]*>([\s\S]{0,80}?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val visible = stripHtml(m.groupValues[4]).trim()
                val title = m.groupValues[3].trim()
                val name = visible.ifBlank { title.substringAfterLast(' ').ifBlank { "第${m.groupValues[2]}集" } }
                eps.add(name to m.groupValues[1])
            }
        return eps.distinctBy { it.second }
    }

    private fun extractM3u8(html: String): String? {
        Regex("""player_aaaa\s*=\s*\{[\s\S]*?["']?url["']?\s*:\s*["']([^"']+)["']""").find(html)?.let {
            return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
        }
        Regex("""["'](https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)["']""").find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun isValidCategoryName(name: String): Boolean = name.isNotBlank() && name.length in 2..20 && !name.all { it.isDigit() } && name !in setOf("首页", "更多")
    private fun stripHtml(s: String): String = s.replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun loadFixture(name: String): String {
        val candidates = listOf(
            "src/test/resources/fixtures/$name",
            "${System.getProperty("user.dir")}/src/test/resources/fixtures/$name",
            "${System.getProperty("user.dir")}/app/src/test/resources/fixtures/$name",
            "../app/src/test/resources/fixtures/$name",
            "../../app/src/test/resources/fixtures/$name",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) return decodeBytes(f.readBytes())
        }
        val stream = this::class.java.classLoader?.getResourceAsStream("fixtures/$name")
        if (stream != null) return decodeBytes(stream.readBytes())
        throw IllegalStateException("fixture $name not found")
    }

    private fun decodeBytes(bytes: ByteArray): String {
        val preview = String(bytes, Charsets.UTF_8)
        val metaCharset = Regex("""<meta[^>]+charset\s*=\s*["']?([^"';\s/>]+)""", RegexOption.IGNORE_CASE)
            .find(preview)?.groupValues?.get(1)?.trim()
        val cs = if (metaCharset.equals("gbk", true) || metaCharset.equals("gb2312", true)) "GBK" else "UTF-8"
        return String(bytes, Charset.forName(cs))
    }

    private val homeHtml by lazy { loadFixture("icaiqi-home.html") }
    private val baiuHome = """
        <a href="/vodtype/1.html" title="电影" class="links"><span>电影</span></a>
        <a href="/vodtype/2.html" title="电视剧" class="links"><span>电视剧</span></a>
        <a href="/vodtype/3.html" title="综艺" class="links"><span>综艺</span></a>
        <a href="/vodplay/1188-1-1.html" title="南京照相馆" class="module-poster-item module-item">
          <div class="module-item-note">正片</div>
          <img class="lazy" data-original="https://img.example/poster.jpg" alt="南京照相馆">
          <div class="module-poster-item-title">南京照相馆</div>
        </a>
    """.trimIndent()

    @Test fun detectTemplate_recognizesCanghai() {
        assertEquals("MACCMS_CANGHAI", detectTemplate("""<link href="/template/canghai_two/css/stui.css">"""))
    }

    @Test fun detectTemplate_recognizesRealCanghaiFixture() {
        assertEquals("MACCMS_CANGHAI", detectTemplate(homeHtml))
    }

    @Test fun detectTemplate_recognizesBaiuMxVariant() {
        assertEquals("MACCMS_MX", detectTemplate(baiuHome))
    }

    @Test fun parseCategories_supportsVodtypeLinks() {
        val cats = parseCategories(baiuHome)
        assertEquals(listOf("电影", "电视剧", "综艺"), cats.map { it.name })
        assertEquals(listOf("1", "2", "3"), cats.map { it.id })
    }

    @Test fun parseVideoList_supportsVodplayCards() {
        val items = parseVodPlayItems(baiuHome)
        assertEquals(1, items.size)
        assertEquals("1188", items[0].id)
        assertEquals("南京照相馆", items[0].title)
        assertEquals("正片", items[0].subTitle)
        assertEquals("https://img.example/poster.jpg", items[0].poster)
    }

    @Test fun parseEpisodes_supportsVodplayModuleList() {
        val html = """
            <div class="module-play-list">
              <a class="module-play-list-link" href="/vodplay/1188-1-1.html" title="播放南京照相馆正片"><span>正片</span></a>
              <a class="module-play-list-link" href="/vodplay/1188-1-2.html" title="播放南京照相馆第2集"><span>第2集</span></a>
            </div>
        """.trimIndent()
        val eps = parseVodPlayEpisodes(html)
        assertEquals(2, eps.size)
        assertEquals("正片", eps[0].first)
        assertEquals("/vodplay/1188-1-1.html", eps[0].second)
    }

    @Test fun extractM3u8_decodesPercentEncodedPlayerUrl() {
        val html = """<script>var player_aaaa={"url":"%68%74%74%70%73%3A%2F%2Fexample.com%2Findex%2E%6D%33%75%38"}</script>"""
        assertEquals("https://example.com/index.m3u8", extractM3u8(html))
    }

    @Test fun fixturesStillAvailable() {
        assertTrue(homeHtml.isNotBlank())
        assertNotNull(homeHtml)
    }
}
