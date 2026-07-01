package com.simple.tvbox.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 简单封装的 HTTP 工具，全应用共用一个 OkHttpClient。
 *
 * 关键点：
 * - 默认使用桌面 Chrome UA，兼容 maccms 模板对 PC 端返回完整内容
 * - 解码 body 时读取 bytes，再综合响应头 charset、HTML meta charset、UTF-8/GBK 候选打分
 * - icaiqi 实测页面是 UTF-8；其他国内 PHP 模板站仍保留 GBK/GB2312 fallback
 *
 * 历史踩坑：
 * - v1 用 resp.body!!.string()，OkHttp 默认按 UTF-8 解码，icaiqi 这种 GBK 站全乱码
 * - v2 改用 resp.body!!.bytes() + 手动 charset 解析
 */
object HttpUtil {

    /** 桌面浏览器 UA，兼容 maccms 模板对 PC 端返回完整内容。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 兜底 UA（部分站对 iPad 放行更宽松）。 */
    private const val USER_AGENT_IPAD =
        "Mozilla/5.0 (iPad; CPU OS 13_2_3 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 " +
            "Mobile/15E148 Safari/604.1"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 抓取文本。
     *
     * 解码规则（按顺序）：
     *  1. 响应头 Content-Type 里的 charset（如 GB2312、UTF-8、ISO-8859-1）
     *  2. HTML 里的 <meta http-equiv="Content-Type" content="text/html; charset=GBK">
     *  3. 默认 UTF-8
     *
     * 历史踩坑：
     * - 国内 PHP 模板影视站（maccms 衍生 canghai / stui / 默认）默认输出 GBK
     * - 用 UTF-8 解 GBK 字节流会得到乱码，导致模板识别正则全部失效
     */
    fun fetchText(url: String, referer: String? = null, charset: String? = null): String {
        val req = buildRequest(url, referer, UserAgentPreference.AUTO)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IllegalStateException("空响应: $url")
            val bytes = body.bytes()
            val headerCharset = charset ?: parseCharsetFromHeader(resp.header("Content-Type"))
            // 先用 ASCII 预览读取 meta charset，避免在未知编码下先把全文解坏。
            val metaCharset = parseCharsetFromMeta(asciiPreview(bytes))
            val candidates = linkedSetOf<String>().apply {
                charset?.let { add(it) }
                headerCharset?.let { add(it) }
                metaCharset?.let { add(it) }
                add("UTF-8")
                add("GBK")
                add("GB2312")
            }
            val decoded = candidates
                .map { it to tryDecode(bytes, it) }
                .minByOrNull { mojibakeScore(it.second) }
                ?: ("UTF-8" to String(bytes, Charsets.UTF_8))
            return decoded.second
        }
    }

    /**
     * 抓取字节流（用于 m3u8 子文件、ts 分片、Referer 跟随等场景）。
     */
    fun fetchBytes(url: String, referer: String? = null): ByteArray {
        val req = buildRequest(url, referer, UserAgentPreference.AUTO)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} for $url")
            }
            return resp.body?.bytes() ?: throw IllegalStateException("空响应: $url")
        }
    }

    /**
     * 跟随 HTTP 3xx 重定向并返回最终 URL（最多 5 跳，带 Referer）。
     *
     * ExoPlayer 自己跟 redirect 时不会保留原始 Referer（中间 CDN 经常 403），
     * 所以我们手动跟，referer 一直挂在源站域名。
     */
    fun followRedirects(url: String, referer: String, maxHops: Int = 5): String {
        var current = url
        repeat(maxHops) {
            val req = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code in 300..399) {
                    val next = resp.header("Location")
                    if (next.isNullOrBlank()) return current
                    current = if (next.startsWith("http")) next
                    else java.net.URL(java.net.URL(current), next).toString()
                } else {
                    return current
                }
            }
        }
        return current
    }

    private fun buildRequest(url: String, referer: String?, uaPref: UserAgentPreference): Request {
        val ua = when (uaPref) {
            UserAgentPreference.AUTO -> USER_AGENT  // 默认用 PC UA，源站对 PC 返回完整内容
            UserAgentPreference.IPAD -> USER_AGENT_IPAD
        }
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        return b.build()
    }

    private enum class UserAgentPreference { AUTO, IPAD }

    private fun tryDecode(bytes: ByteArray, charset: String): String {
        return runCatching {
            Charset.forName(charset).let { cs -> String(bytes, cs) }
        }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    private fun parseCharsetFromHeader(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val m = Regex("""charset\s*=\s*["']?([^"';\s]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)
        return m?.groupValues?.get(1)?.trim()
    }


    /**
     * Extract charset from <meta charset="..."> / http-equiv content.
     */
    private fun parseCharsetFromMeta(html: String): String? {
        Regex("""<meta[^>]+charset\s*=\s*["']?([^"';\s/>]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1].trim() }
        Regex("""<meta[^>]+content\s*=\s*["'][^"']*charset\s*=\s*([^"';\s]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun asciiPreview(bytes: ByteArray, max: Int = 8192): String {
        val n = minOf(bytes.size, max)
        val chars = CharArray(n)
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xff
            chars[i] = if (b in 0x20..0x7e || b == 0x0a || b == 0x0d || b == 0x09) b.toChar() else ' '
        }
        return String(chars)
    }

    /**
     * Mojibake score：分数越低越可信。
     * - UTF-8/GBK 解错时通常会出现 U+FFFD、控制字符，或 CJK 字符异常偏少。
     * - 保留日文假名轻微惩罚，避免把中文页面误判成 Shift-JIS 类乱码。
     */
    private fun mojibakeScore(text: String): Int {
        var score = 0
        score += text.count { it == '\uFFFD' } * 100
        val markers = listOf("?", "?", "?", "?", "?", "?", "?", "?", "?", "?", "?")
        for (marker in markers) score += Regex.escape(marker).toRegex().findAll(text).count() * 8
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val kana = text.count { it.code in 0x3040..0x30FF }
        val control = text.count { it.code < 32 && it !in "\n\r\t" }
        score += kana * 2 + control * 20
        if (cjk < 20 && text.length > 500) score += 200
        return score
    }

}
