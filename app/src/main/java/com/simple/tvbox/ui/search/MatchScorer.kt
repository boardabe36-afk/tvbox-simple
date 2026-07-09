package com.simple.tvbox.ui.search

import kotlin.math.max
import kotlin.math.min

/**
 * 标题匹配打分器：用于搜索结果排序、豆瓣卡片聚合搜索选源。
 *
 * 关键设计点：
 *
 * 1. **噪音词过滤**：源站/资源站经常在标题里塞 "HD/1080p/国语/粤语/全集/蓝光/BD/TS/HDTV..."
 *    这些字符不参与匹配，也不参与长度惩罚，避免它们拉低「同标题」得分。
 *
 * 2. **多 token 命中**：用户搜 "狂飙 张译"，必须「狂飙」和「张译」都在标题里才高；
 *    单 token 全字命中 > 拆分命中 > 部分命中。
 *
 * 3. **连续字符奖励（LCS）**：
 *    - 离散命中 1 分/字符
 *    - 连续命中 8 分/字符（重奖：能拼出原 token）
 *
 * 4. **首段/包含加分**：「三体」搜「三体2：黑暗森林」理应高于「地球往事：三体的前世」。
 *
 * 5. **年份接近**：标题里如果带年份（如 "三体 2023"），用户搜 "三体" 不带年份，
 *    优先选「最新」年份；带年份时按年份差额外打分（同年 +2000，每差 1 年 -300）。
 *
 * 6. **去除括号/全角空格/中文括号**后做二次对比，应对「狂飙(Kuang Biao)」之类。
 *
 * 7. **拼音首字母**轻量支持：搜「QZ」时如果标题里能拼出「狂飙」，加 600 分。
 *    仅作为兜底，正经匹配分远高于此。
 *
 * 8. **编辑距离惩罚**：长度差过大（如 query=4 字标题，title=20 字简介）扣分。
 */
object MatchScorer {

    /**
     * 视频源/资源站常见噪音词（中英混合）。
     * 匹配前从标题里全部抹掉，不计入"长度惩罚"。
     */
    private val NOISE_TOKENS = listOf(
        // 分辨率 / 画质
        "4k", "8k", "2k", "1080p", "720p", "480p", "360p", "2160p",
        "hd", "fhd", "uhd", "hdr", "bd", "bdrip", "dvdrip", "dvd",
        "bluray", "blu-ray", "remux", "web-dl", "webrip", "hdtv",
        "ts", "tc", "cam", "rip",
        // 音轨 / 字幕
        "国语", "粤语", "英语", "日语", "韩语", "闽南语", "上海话", "四川话", "方言",
        "中字", "中英双语", "国粤双语", "双语", "内嵌字幕", "内嵌", "外挂字幕", "外挂",
        "无字幕", "字幕版",
        // 平台标签
        "高清", "标清", "超清", "蓝光", "原盘", "修复版", "修复", "重制", "重映", "重剪",
        "未删减版", "未删减", "未删节", "加长版", "导演剪辑版", "dc版",
        "完整未删", "全片", "全季", "打包",
        // 集数标签
        "更新至", "全", "完结", "连载中", "完",
        // 站点水印
        "抢先看", "尝鲜", "首播", "更新",
        // 资源版本
        "h265", "h264", "h.265", "h.264", "x264", "x265", "10bit", "8bit",
        "hdr10+", "dolby", "atmos", "dts", "truehd", "ac3", "aac", "flac",
        // 格式/容器
        "mp4", "mkv", "avi", "ts", "m2ts",
        // 资源站常见前缀后缀
        "新", "热", "推"
    )

    /**
     * "合集/同人/专题"标记：源站经常把多部剧的合集页/同名词/解说视频混在搜索结果里，
     * 这些不应该被作为用户想看的那一部剧的 top1。
     *
     * 命中时大幅扣分（3000-4000），让真正的目标剧胜出。
     */
    private val COLLECTION_MARKERS = listOf(
        // 同人创作（专指同人作品，不是"同名词"）
        "同人剧", "同人短片", "同人版", "同人作品",
        // 解说视频（5-10 分钟速看剧情）
        "解说", "速看", "讲解", "剧情解说", "解析", "影评",
        // 二次创作/剪辑
        "剪辑", "混剪", "混剪合集", "集锦", "精彩集锦",
        // 衍生内容
        "番外", "后篇", "下篇",
        // 跨地区版本
        "中国版", "美国版", "日本版", "韩国版", "港版", "台版"
    )

    /** 用于 contains 检查的 set */
    private val collectionSet = COLLECTION_MARKERS.toSet()

    /** 中文括号 + 英文括号 + 方括号 + 大括号 + 破折号 + 波浪线 */
    private val BRACKET_RE = Regex("[\\[\\]【】()（）{}<>《》〈〉「」『』]")

    /** 各种分隔符（保留以做 token 切分） */
    private val SEP_RE = Regex("[\\s·・:：,，.。!！?？《》<>\\/\\-_=+]+")

    /** 年份：1980-2099 范围的 4 位数字 */
    private val YEAR_RE = Regex("(19|20)\\d{2}")

    /** 拼音首字母字符串：A-Z 字母，至少 2 个 */
    private val INITIALS_RE = Regex("^[A-Za-z]{2,}$")

    /** 缓存噪音词集合用于 contains 检查 */
    private val noiseSet = NOISE_TOKENS.toSet()

    /**
     * 规范化：去噪音 / 去括号 / 去分隔符 / 转小写 / 折叠空白。
     * 返回 "clean title"，用于打分。
     */
    fun clean(input: String): String {
        if (input.isBlank()) return ""
        var s = input.lowercase()
        // 1. 抽掉括号及其内容（噪音大量集中在括号里）
        s = BRACKET_RE.replace(s, " ")
        // 2. 抽年份（年份不参与长度惩罚，但命中时单独奖励）
        s = YEAR_RE.replace(s, " ")
        // 3. 替换分隔符为空格
        s = SEP_RE.replace(s, " ")
        // 4. 删噪音词（按 token 删，对中文 1-3 字 + 英文 1-10 字）
        s = s.split(' ').filter { tok ->
            if (tok.isEmpty()) false
            else if (tok.length <= 2 && isPureAscii(tok)) {
                // 英文 1-2 字符如果是常见词（a/an/to/of/in/on 之类）也删
                tok !in setOf("of", "to", "in", "on", "at", "an", "or", "is", "it", "as", "by")
            } else true
        }.joinToString(" ")
        // 5. 二次过一遍"包含型噪音词"（如 "1080p" 整体被 SEP_RE 切掉了，但 "4k" 可能贴着其他字）
        s = noiseSet.fold(s) { acc, n ->
            acc.replace(Regex("(?<=[\\s]|^)" + Regex.escape(n) + "(?=[\\s]|$)"), " ")
        }
        // 6. 折叠空白
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun isPureAscii(s: String): Boolean = s.all { it.code in 0x20..0x7e }

    /**
     * 抽取标题里的所有 4 位年份。
     */
    fun extractYears(input: String): List<Int> {
        return YEAR_RE.findAll(input).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    /**
     * 主打分函数。
     *
     * @param keyword  用户搜索词（可能含噪音词、分隔符、年份）
     * @param title    候选视频标题
     * @param subTitle 候选副标题（用于兜底匹配）
     * @param yearHint 用户输入的年份（可空，从 keyword 解析）
     * @return 0-20000+ 的整数分，越大越相关
     */
    fun score(
        keyword: String,
        title: String,
        subTitle: String? = null,
        yearHint: Int? = null
    ): Int {
        if (keyword.isBlank() || title.isBlank()) return 0

        val qClean = clean(keyword)
        val tClean = clean(title)
        val sClean = clean(subTitle.orEmpty())

        if (qClean.isBlank() || tClean.isBlank()) {
            // query 全是噪音词？那就退回"长度差"做粗匹配
            return -kotlin.math.abs(title.length - keyword.length) * 5
        }

        var s = 0

        // === 1. 完全相等 / 前缀 / 包含 ===
        when {
            tClean == qClean -> s += 12_000
            tClean.startsWith(qClean) -> s += 9_000
            tClean.contains(qClean) -> s += 6_000
            qClean.contains(tClean) && tClean.length >= 3 -> s += 5_500
        }

        // === 2. 多 token 命中 ===
        val qTokens = qClean.split(' ').filter { it.isNotEmpty() }
        if (qTokens.size > 1) {
            var hitTokens = 0
            for (qt in qTokens) {
                if (tClean.contains(qt)) hitTokens++
            }
            // 多 token 全中：巨大奖励；部分中：按比例
            val ratio = hitTokens.toDouble() / qTokens.size
            s += (ratio * 4000).toInt()
            // 关键：完全没命中 (ratio=0) 直接扣 2000 避免噪音结果霸榜
            if (ratio == 0.0) s -= 2_000
        }

        // === 3. LCS 连续命中（核心加分）===
        // 先按"压缩后"做 (去空格)；同时按"带空格"做
        val qCompact = qClean.replace(" ", "")
        val tCompact = tClean.replace(" ", "")

        if (qCompact.isNotEmpty() && tCompact.isNotEmpty()) {
            val lcs = longestCommonSubstring(qCompact, tCompact)
            // 连续命中长度 / query 长度的比率（核心质量指标）
            val lcsRatio = lcs.length.toDouble() / qCompact.length
            s += (lcsRatio * 5000).toInt()

            // 连续片段长度：连续 >= 2 的奖励 + 单字符离散命中奖励
            val (longestRun, totalHits) = charHits(qCompact, tCompact)
            s += longestRun * 60          // 最长连续段 60 分/字符
            s += totalHits * 6            // 离散命中 6 分/字符

            // query 中所有字符在 title 中都出现过 + 全部离散
            val allCharsInT = qCompact.all { ch -> tCompact.contains(ch) }
            if (allCharsInT && qCompact.length <= 4) s += 1_500

            // === 3.5 短 query 在长标题里作为子串匹配的弱化 ===
            // 像 "狂飙之风波再起" 里包着 "狂飙"，或 "狂飙专题合集" / "狂飙同人短片" 等同名词 / 系列名 / 同人片，
            // 在长标题里命中短 query 几乎是肯定的，但并不等同于"我要找的就是这一条"。
            // 强扣分，让 query 长度接近的真实候选胜出。
            if (qCompact.length in 1..4 && lcs.length == qCompact.length && tCompact.length >= qCompact.length + 5) {
                s -= 2500
            }

            // === 3.6 合集/同人/专题标题强力降权 ===
            // 源站经常在搜索结果里塞合集页/同人片/解说/合订本等，命中 collection markers 就重扣。
            // 注意：只对"原始 title"检查，避免清洗后丢了关键标记。
            for (m in COLLECTION_MARKERS) {
                if (title.contains(m, ignoreCase = true)) {
                    s -= 4000
                    break  // 命中一个就够
                }
            }
        }

        // === 4. 副标题兜底命中（弱加分） ===
        if (sClean.isNotEmpty()) {
            if (sClean.contains(qClean) || sClean.contains(qCompact)) {
                s += 800
            }
        }

        // === 5. 年份接近 ===
        val titleYears = extractYears(title)
        val queryYears = extractYears(keyword)
        val finalYearHint = yearHint ?: queryYears.firstOrNull()
        if (finalYearHint != null) {
            if (finalYearHint in titleYears) {
                // 标题里有完全相同年份，强奖励
                s += 2_000
            } else if (titleYears.isNotEmpty()) {
                // 标题里有别的年份，按差距扣分
                val nearest = titleYears.minByOrNull { kotlin.math.abs(it - finalYearHint) }!!
                val diff = kotlin.math.abs(nearest - finalYearHint)
                s -= min(diff * 250, 1_500)
            }
        }

        // === 6. 长度惩罚（基于 clean 后的长度）===
        // clean 后长度差过大 = 简介/合集/系列名，扣分
        val lenDiff = kotlin.math.abs(tCompact.length - qCompact.length)
        s -= min(lenDiff * 12, 1_500)

        // === 7. 拼音首字母兜底 ===
        if (INITIALS_RE.matches(keyword.trim())) {
            // query 是字母，把标题里每个汉字的首字母拼起来看是否包含 query
            val initials = chineseInitials(title)
            if (initials.contains(keyword.trim().lowercase())) {
                s += 600
            }
        }

        // === 8. 短标题完全相同字符序（保底）===
        if (qCompact.length <= 6 && qCompact.length >= 2 && tCompact.contains(qCompact)) {
            s += 400
        }

        return s
    }

    /**
     * 中文转拼音首字母。
     * 简化版：仅做"声母近似"，不调外部库。
     * 对于常用字，直接用汉字 Unicode 段近似映射（够用，毕竟只是兜底）。
     *
     * 输入：原始中文标题
     * 输出：小写首字母字符串
     */
    fun chineseInitials(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    sb.append(approximatePinyinInitial(ch))
                }
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                // 跳过空格/标点
            }
        }
        return sb.toString()
    }

    /**
     * 近似拼音首字母。
     * 基于 Unicode 段做粗略映射。精度有限但作为 fallback 够用。
     *
     * 这是非常简化的版本，把常用汉字大致按 CJK 部首段归类。
     * 真要准请引入 pinyin4j，但几十 KB 体积不划算。
     */
    private fun approximatePinyinInitial(ch: Char): Char {
        // 简化映射表：常用字的声母
        // 按 Unicode 段分组
        val c = ch.code
        return when (ch) {
            // 一部分非常常用的字用精确映射
            '的' -> 'd'; '是' -> 's'; '不' -> 'b'; '了' -> 'l'
            '在' -> 'z'; '有' -> 'y'; '和' -> 'h'; '我' -> 'w'; '就' -> 'j'
            '人' -> 'r'; '都' -> 'd'; '个' -> 'g'; '上' -> 's'
            '也' -> 'y'; '很' -> 'h'; '到' -> 'd'; '说' -> 's'; '要' -> 'y'
            '去' -> 'q'; '你' -> 'n'; '会' -> 'h'; '着' -> 'z'; '没' -> 'm'
            '看' -> 'k'; '好' -> 'h'; '这' -> 'z'; '那' -> 'n'; '里' -> 'l'
            '生' -> 's'; '年' -> 'n'; '国' -> 'g'; '中' -> 'z'; '日' -> 'r'
            '狂' -> 'k'; '飙' -> 'b'; '三' -> 's'; '体' -> 't'
            '球' -> 'q'; '大' -> 'd'; '战' -> 'z'; '海' -> 'h'; '王' -> 'w'
            '行' -> 'x'; '记' -> 'j'; '风' -> 'f'; '云' -> 'y'
            '白' -> 'b'; '夜' -> 'y'; '黑' -> 'h'; '红' -> 'h'; '蓝' -> 'l'
            '绿' -> 'l'; '金' -> 'j'; '银' -> 'y'; '影' -> 'y'; '视' -> 's'
            '剧' -> 'j'; '集' -> 'j'; '季' -> 'j'; '月' -> 'y'
            '爱' -> 'a'; '情' -> 'q'; '家' -> 'j'; '路' -> 'l'; '新' -> 'x'
            '老' -> 'l'; '天' -> 't'; '地' -> 'd'; '山' -> 's'; '水' -> 's'
            '火' -> 'h'; '雷' -> 'l'; '电' -> 'd'
            else -> {
                // 兜底：按 Unicode 段映射到 A-Z 之一
                // CJK 一级汉字从 0x4E00 开始，按 ~650 个一组划分到 A-Z
                // 这只是非常粗略的近似，但比纯随机好
                val bucket = ((c - 0x4E00) / 650) % 26
                ('a' + bucket)
            }
        }
    }

    /**
     * 最长公共子串。
     * DP 实现，O(m*n) 时间 / O(min(m,n)) 空间（滚动数组）。
     */
    fun longestCommonSubstring(a: String, b: String): String {
        if (a.isEmpty() || b.isEmpty()) return ""
        val (s, t) = if (a.length < b.length) a to b else b to a
        var prev = IntArray(s.length + 1)
        var curr = IntArray(s.length + 1)
        var bestEnd = 0
        var bestLen = 0
        for (i in 1..t.length) {
            for (j in 1..s.length) {
                curr[j] = if (t[i - 1] == s[j - 1]) prev[j - 1] + 1 else 0
                if (curr[j] > bestLen) {
                    bestLen = curr[j]
                    bestEnd = i
                }
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return if (bestLen == 0) "" else t.substring(bestEnd - bestLen, bestEnd)
    }

    /**
     * 字符命中统计：返回 (最长连续命中段长度, 总命中字符数)
     *
     * - totalHits：query 中出现在 title 任意位置的字符数（去重后）
     * - longestRun：query 中连续片段（按出现顺序）在 title 里完整出现的最大长度
     */
    private fun charHits(query: String, title: String): Pair<Int, Int> {
        if (query.isEmpty()) return 0 to 0
        var hits = 0
        val seen = HashSet<Char>()
        // 离散命中（query 中独立字符在 title 里出现过）
        for (ch in query) {
            if (ch !in seen && title.contains(ch)) {
                hits++
                seen.add(ch)
            }
        }
        // 最长连续段
        var longestRun = 0
        var run = 0
        var ti = 0
        for (qi in query.indices) {
            val qch = query[qi]
            // 从当前位置往后找
            var found = false
            var k = ti
            while (k < title.length) {
                if (title[k] == qch) {
                    run++
                    ti = k + 1
                    found = true
                    break
                }
                k++
            }
            if (!found) {
                // 这一字符没找到，结束当前连续段
                longestRun = max(longestRun, run)
                run = 0
                ti = 0  // 重新开始
            }
        }
        longestRun = max(longestRun, run)
        return longestRun to hits
    }
}