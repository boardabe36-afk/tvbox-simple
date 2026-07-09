package com.simple.tvbox.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.Source
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoCategory
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.source.DoubanService
import com.simple.tvbox.source.VideoClientFactory
import com.simple.tvbox.ui.douban.DoubanActivity
import com.simple.tvbox.ui.history.HistoryActivity
import com.simple.tvbox.ui.remote.QrActivity
import com.simple.tvbox.ui.search.MatchScorer
import com.simple.tvbox.ui.search.SearchActivity
import com.simple.tvbox.ui.settings.SettingsActivity
import com.simple.tvbox.util.SettingsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主入口。
 *
 * 行结构：
 *   1. 快捷入口行：搜索 / 最近观看 / 扫码搜索 / 扫码传文件 / 扫码设置 / 设置 / 刷新
 *   2. 热门影视行：从当前源实时拉取带海报的视频内容
 *   3. 已配置源行：用户添加的所有源（点击查看该源分类）
 *   4. 分类快捷行：每个已加载站点的分类（点击进入分类页）
 *   5. 空状态行：未配置源时引导用户去设置
 *
 * 关键：onResume() 重新加载，避免从设置页添加源后回到首页看不到
 */
class HomeFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    @Volatile private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.tv_primary)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.tv_accent)

        // 搜索图标点击
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        // 行内点击
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            handleItemClick(item)
        }

        adapter = rowsAdapter
    }

    override fun onResume() {
        super.onResume()
        // 每次回到主页都重载（清空再重新渲染），保证新加的源能立即出现
        loadHome()
    }

    private fun handleItemClick(item: Any?) {
        when (item) {
            is VideoCard -> {
                if (item.videoId != null) {
                    startActivity(
                        com.simple.tvbox.ui.detail.DetailActivity.intent(
                            requireContext(),
                            siteKey = item.siteKey,
                            videoId = item.videoId,
                            title = item.title
                        )
                    )
                    return
                }
                val intent = if (item.categoryId != null) {
                    // 分类卡片：直接进分类视频列表
                    CategoryActivity.intent(
                        requireContext(),
                        siteKey = item.siteKey,
                        categoryId = item.categoryId,
                        categoryName = item.title
                    )
                } else {
                    // 源卡片：跳到源详情（传源 URL）
                    SourceDetailActivity.intent(
                        requireContext(),
                        siteKey = item.siteKey,
                        sourceName = item.title,
                        sourceUrl = item.id   // VideoCard.id 在 renderConfiguredSourcesRow 里存的就是 src.url
                    )
                }
                startActivity(intent)
            }
            is DoubanPosterCard -> {
                // 首页豆瓣卡片点击 → 聚合搜索真实视频源 top1 → 播放
                onDoubanPosterClicked(item)
            }
            is ActionItem -> item.onClick()
        }
    }

    private fun loadHome() {
        if (isLoading) {
            android.util.Log.i("HomeFragment", "loadHome: already loading, skip")
            return
        }
        isLoading = true
        rowsAdapter.clear()
        lifecycleScope.launch {
            try {
                val sources = TvBoxApp.get().sourceRepository.getAllSources()
            android.util.Log.i("HomeFragment", "loadHome: sources=${sources.size}, thread=${Thread.currentThread().name}")
                // 快捷入口不依赖视频源
                renderQuickEntryRow()
                if (sources.isEmpty()) {
                    renderEmptyState()
                    return@launch
                }
                // 关键防御：任何一行渲染失败都不能影响其他行 (否则 "界面全空")
                // 顺序：先添加所有同步行，最后才添加豆瓣行
                // 原因：renderDoubanRow 内部的 lifecycleScope.launch 异步协程会触发 RecyclerView layout，
                // 如果在同步行之前调用，后续 rowsAdapter.add() 的行不会被渲染（Leanback bug）
                runCatching { renderConfiguredSourcesRow(sources) }
                    .onFailure { android.util.Log.e("HomeFragment", "renderConfiguredSourcesRow failed", it) }
                runCatching { renderHotVideoRows() }
                    .onFailure { android.util.Log.e("HomeFragment", "renderHotVideoRows failed", it) }
                runCatching { renderSiteCategoryRows(sources) }
                    .onFailure { android.util.Log.e("HomeFragment", "renderSiteCategoryRows failed", it) }
                // 豆瓣行放最后（内部的异步 launch 不影响已添加的同步行）
                if (SettingsPrefs.isDoubanEnabled(requireContext())) {
                    renderDoubanRow()
                }
                // 兑底：如果上面都失败了，至少让用户看到“已配置 X 个源”的提示行
                if (rowsAdapter.size() <= 1) {
                    renderLoadFailedHint(sources)
                }
            } catch (t: Throwable) {
                android.util.Log.e("HomeFragment", "loadHome fatal", t)
                Toast.makeText(
                    requireContext(),
                    "加载源失败：${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                // 兑底：保证至少看到快捷入口 + 加载提示
                if (rowsAdapter.size() == 0) {
                    renderQuickEntryRow()
                    renderLoadFailedHint(emptyList())
                }
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 兑底行：当源加载失败时，显示"已配置 X 个源，加载失败，点击重试"
     */
    private fun renderLoadFailedHint(sources: List<com.simple.tvbox.model.Source>) {
        android.util.Log.i("HomeFragment", "renderLoadFailedHint called, sources=${sources.size}")
        val header = HeaderItem(999L, "视频源加载失败")
        val rowAdapter = ArrayObjectAdapter(ActionPresenter())
        rowAdapter.add(ActionItem(id = 9991, title = "重试加载", subTitle = "重新连接已配置的源") {
            TvBoxApp.get().sourceRepository.invalidateCache()
            loadHome()
        })
        if (sources.isNotEmpty()) {
            rowAdapter.add(ActionItem(id = 9992, title = "已配置 ${sources.size} 个源（点重试）", subTitle = sources.joinToString(" · ") { it.name }) {})
        }
        rowAdapter.add(ActionItem(id = 9993, title = "去设置检查", subTitle = "查看源配置 / 重新添加") {
            runCatching { startActivity(Intent(requireContext(), com.simple.tvbox.ui.settings.SettingsActivity::class.java)) }.onFailure { android.util.Log.e("HomeFragment", "open settings failed", it) }
        })
        rowsAdapter.add(ListRow(header, rowAdapter))
    }

    private fun renderEmptyState() {
        val header = HeaderItem(HEADER_EMPTY, "开始使用")
        val item = ActionItem(
            id = 1L,
            title = "还没有视频源，先用“扫码设置”或手动设置添加",
            subTitle = "扫码设置支持手机输入网址或 JSON 配置链接，同步到电视"
        ) {
            runCatching { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }.onFailure { android.util.Log.e("HomeFragment", "open settings failed", it) }
        }
        val rowAdapter = ArrayObjectAdapter(ActionPresenter())
        rowAdapter.add(item)
        rowsAdapter.add(ListRow(header, rowAdapter))
    }

    /**
     * 行 1：快捷入口（搜索 / 最近观看 / 扫码搜索 / 扫码传文件 / 扫码设置 / 设置 / 刷新）
     */
    private fun renderQuickEntryRow() {
        val header = HeaderItem(HEADER_QUICK, getString(R.string.title_quick))
        val rowAdapter = ArrayObjectAdapter(ActionPresenter())
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 1, title = getString(R.string.title_search)) {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 2, title = "最近观看") {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 3, title = "豆瓣热门") {
            startActivity(DoubanActivity.intent(requireContext(), DoubanActivity.TAB_HOME))
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 4, title = "扫码搜索") {
            startActivity(QrActivity.intent(requireContext(), QrActivity.MODE_SEARCH))
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 5, title = "扫码传文件") {
            startActivity(QrActivity.intent(requireContext(), QrActivity.MODE_UPLOAD))
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 6, title = "扫码设置") {
            startActivity(QrActivity.intent(requireContext(), QrActivity.MODE_SETTINGS))
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 7, title = getString(R.string.title_settings)) {
            runCatching { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }.onFailure { android.util.Log.e("HomeFragment", "open settings failed", it) }
        })
        rowAdapter.add(ActionItem(id = HEADER_QUICK * 100 + 8, title = getString(R.string.title_refresh)) {
            // 手动刷新：清空所有缓存后重新加载
            TvBoxApp.get().sourceRepository.invalidateCache()
            Toast.makeText(requireContext(), R.string.refreshing, Toast.LENGTH_SHORT).show()
            loadHome()
        })
        rowsAdapter.add(ListRow(header, rowAdapter))
    }

    /**
     * 行 1.5：豆瓣热门（电影+电视剧），独立行，独立 Presenter
     *
     * 点击 → 聚合搜索所有视频源 → top1 → DetailActivity 播放
     * 无源时 → 浏览器打开豆瓣详情页
     */
    private fun renderDoubanRow() {
        // 顶部"豆瓣频道"快捷入口行：电影 / 电视剧 / Top250
        val headerRow = HeaderItem(HEADER_DOUBAN_QUICK, "豆瓣 · 频道")
        val actionAdapter = ArrayObjectAdapter(ActionPresenter())
        actionAdapter.add(ActionItem(id = 1, title = "豆瓣首页") {
            startActivity(DoubanActivity.intent(requireContext(), DoubanActivity.TAB_HOME))
        })
        actionAdapter.add(ActionItem(id = 2, title = "豆瓣电影") {
            startActivity(DoubanActivity.intent(requireContext(), DoubanActivity.TAB_MOVIE))
        })
        actionAdapter.add(ActionItem(id = 3, title = "豆瓣电视剧") {
            startActivity(DoubanActivity.intent(requireContext(), DoubanActivity.TAB_TV))
        })
        rowsAdapter.add(ListRow(headerRow, actionAdapter))

        // 豆瓣热门电影海报行
        renderDoubanPosterRow(
            rowIndex = HEADER_DOUBAN_MOVIE,
            headerTitle = "豆瓣 · 热门电影",
            type = DoubanService.DoubanMediaType.MOVIE,
            tag = "热门",
            limit = 30
        )

        // 豆瓣热门电视剧海报行
        renderDoubanPosterRow(
            rowIndex = HEADER_DOUBAN_TV,
            headerTitle = "豆瓣 · 热门电视剧",
            type = DoubanService.DoubanMediaType.TV,
            tag = "热门",
            limit = 30
        )
    }

    private fun renderDoubanPosterRow(
        rowIndex: Long,
        headerTitle: String,
        type: DoubanService.DoubanMediaType,
        tag: String,
        limit: Int
    ) {
        val rowAdapter = ArrayObjectAdapter(DoubanPosterPresenter())
        // 占位卡片
        rowAdapter.add(DoubanPosterCard(
            id = "_placeholder_",
            title = "正在加载豆瓣内容…",
            subTitle = headerTitle,
            poster = null,
            detailUrl = "",
            item = null
        ))
        val row = ListRow(HeaderItem(rowIndex, headerTitle), rowAdapter)
        rowsAdapter.add(row)

        lifecycleScope.launch {
            runCatching {
                val items = DoubanService.fetch(type, tag, limit = limit, page = 1)
                if (rowsAdapter.indexOf(row) < 0) return@runCatching
                rowAdapter.clear()
                if (items.isEmpty()) {
                    rowAdapter.add(DoubanPosterCard(
                        id = "_empty_",
                        title = "暂无豆瓣内容",
                        subTitle = "请检查网络或稍后刷新",
                        poster = null,
                        detailUrl = "",
                        item = null
                    ))
                } else {
                    items.forEach { item ->
                        rowAdapter.add(DoubanPosterCard(
                            id = item.id,
                            title = item.title,
                            subTitle = buildString {
                                if (item.rate.isNotBlank()) append("豆瓣 ").append(item.rate)
                                if (item.isNew) {
                                    if (isNotEmpty()) append(" · ")
                                    append("新")
                                }
                            }.ifEmpty { null },
                            poster = item.cover.ifBlank { null },
                            detailUrl = item.detailUrl,
                            item = item
                        ))
                    }
                }
            }.onFailure {
                if (rowsAdapter.indexOf(row) >= 0) {
                    rowAdapter.clear()
                    rowAdapter.add(DoubanPosterCard(
                        id = "_error_",
                        title = "豆瓣加载失败",
                        subTitle = it.message ?: it.javaClass.simpleName,
                        poster = null,
                        detailUrl = "",
                        item = null
                    ))
                }
            }
        }
    }

    /**
     * 首页豆瓣卡片点击：用标题聚合搜索所有视频源，命中 top1 进 DetailActivity。
     */
    private fun onDoubanPosterClicked(card: DoubanPosterCard) {
        if (card.item == null) return
        Toast.makeText(requireContext(), "正在搜索: ${card.title}", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val sources = TvBoxApp.get().sourceRepository.getAllSources()
                if (sources.isEmpty()) {
                    // 没视频源 → 浏览器打开豆瓣详情页
                    openInBrowser(card.detailUrl)
                    return@launch
                }
                val sites = withContext(Dispatchers.IO) {
                    TvBoxApp.get().sourceRepository.loadAllSites()
                }
                if (sites.isEmpty()) {
                    openInBrowser(card.detailUrl)
                    return@launch
                }
                // 多查询
                val queries = buildDoubanSearchQueries(card.title)
                val ranked: List<Pair<SpiderSite, VideoItem>> = withContext(Dispatchers.IO) {
                    val collected: MutableList<Triple<SpiderSite, VideoItem, Int>> = mutableListOf()
                    // 并发按站点查询（避免串行时单站 timeout 卡住其他站）
                    coroutineScope {
                        val deferreds = sites.map { site ->
                            async(Dispatchers.IO) {
                                val client = VideoClientFactory.create(site)
                                if (!client.isSupported()) return@async emptyList<Triple<SpiderSite, VideoItem, Int>>()
                                val local = mutableListOf<Triple<SpiderSite, VideoItem, Int>>()
                                for (q in queries) {
                                    val items: List<VideoItem> = runCatching { client.search(q, 1) }
                                        .getOrDefault(emptyList())
                                    for (v in items) {
                                        local.add(Triple(site, v, MatchScorer.score(q, v.title, v.subTitle)))
                                    }
                                }
                                local
                            }
                        }
                        for (d in deferreds) collected.addAll(d.await())
                    }
                    collected
                        .groupBy { it.first.key + "::" + it.second.id }
                        .map { (_, g) -> g.maxByOrNull { x -> x.third }!! }
                        .filter { it.third > 500 }
                        .sortedWith(
                            compareByDescending<Triple<SpiderSite, VideoItem, Int>> { it.third }
                                .thenBy { it.second.title.length }
                        )
                        .map { it.first to it.second }
                }
                val top = ranked.firstOrNull()
                if (top != null) {
                    startActivity(
                        com.simple.tvbox.ui.detail.DetailActivity.intent(
                            requireContext(),
                            siteKey = top.first.key,
                            videoId = top.second.id,
                            title = top.second.title
                        )
                    )
                } else {
                    Toast.makeText(requireContext(), "暂未找到资源", Toast.LENGTH_SHORT).show()
                    openInBrowser(card.detailUrl)
                }
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "搜索失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildDoubanSearchQueries(rawTitle: String): List<String> {
        val out = LinkedHashSet<String>()
        val original = rawTitle.trim()
        if (original.isNotEmpty()) out.add(original)
        val noParens = original
            .replace(Regex("[\\[\\]【】()（）].*?[\\[\\]【】()（）]"), "")
            .replace(Regex("[\\[\\]【】()（）]"), "")
            .trim()
        if (noParens.isNotEmpty() && noParens != original) out.add(noParens)
        val cleaned = noParens
            .replace(Regex("\\s*(19|20)\\d{2}\\s*$"), "")
            .replace(Regex("\\s*第[一二三四五六七八九十0-9]+季\\s*$"), "")
            .replace(Regex("\\s*Season\\s*[0-9]+\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isNotEmpty() && cleaned != noParens && cleaned != original) out.add(cleaned)
        return out.toList()
    }

    private fun openInBrowser(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "未找到浏览器", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 行 2：热门影视。实时从可用源首页分类里拉取视频，优先展示带海报的内容。
     */
    private fun renderHotVideoRows() {
        val rowAdapter = ArrayObjectAdapter(PosterCardPresenter())
        rowAdapter.add(VideoCard(
            id = "hot_loading",
            title = "正在更新热门内容",
            subTitle = "从当前视频源实时获取",
            poster = null,
            siteKey = "",
            categoryId = null
        ))
        val row = ListRow(HeaderItem(HEADER_HOT, "热门影视 · 实时更新"), rowAdapter)
        rowsAdapter.add(row)

        lifecycleScope.launch {
            runCatching {
                val hotItems = withContext(Dispatchers.IO) { fetchHotVideos() }
                if (rowsAdapter.indexOf(row) < 0) return@runCatching
                rowAdapter.clear()
                if (hotItems.isEmpty()) {
                    rowAdapter.add(VideoCard(
                        id = "hot_empty",
                        title = "暂无热门内容",
                        subTitle = "请检查源或稍后刷新",
                        poster = null,
                        siteKey = ""
                    ))
                } else {
                    hotItems.forEach { rowAdapter.add(it) }
                }
            }.onFailure {
                if (rowsAdapter.indexOf(row) >= 0) {
                    rowAdapter.clear()
                    rowAdapter.add(VideoCard(
                        id = "hot_error",
                        title = "热门内容更新失败",
                        subTitle = it.message ?: it.javaClass.simpleName,
                        poster = null,
                        siteKey = ""
                    ))
                }
            }
        }
    }

    private suspend fun fetchHotVideos(): List<VideoCard> {
        val repo = TvBoxApp.get().sourceRepository
        val sites = repo.loadAllSites().filter { VideoClientFactory.create(it).isSupported() }
        val cards = linkedMapOf<String, VideoCard>()
        for (site in sites.take(5)) {
            val client = VideoClientFactory.create(site)
            val cats = runCatching { client.fetchHomeCategories() }.getOrDefault(emptyList())
            val preferredCats = cats.sortedBy { cat -> hotCategoryPriority(cat.name) }.take(3)
            for (cat in preferredCats) {
                val items = runCatching { client.fetchCategory(cat.id, 1) }.getOrDefault(emptyList())
                for (v in items) {
                    if (v.title.isBlank()) continue
                    val key = site.key + "::" + v.id
                    cards.putIfAbsent(key, VideoCard(
                        id = v.id,
                        title = v.title,
                        subTitle = listOfNotNull(v.subTitle, site.name, cat.name).joinToString(" · "),
                        poster = v.poster,
                        siteKey = site.key,
                        videoId = v.id
                    ))
                    if (cards.size >= 24) return cards.values.toList()
                }
            }
        }
        return cards.values.sortedByDescending { if (it.poster.isNullOrBlank()) 0 else 1 }.take(24)
    }

    private fun hotCategoryPriority(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("热") || n.contains("最新") || n.contains("推荐") -> 0
            n.contains("电影") -> 1
            n.contains("电视") || n.contains("剧") -> 2
            n.contains("动漫") || n.contains("综艺") -> 3
            else -> 5
        }
    }

    /**
     * 行 2：已配置的源（点击查看该源分类页）
     */
    private fun renderConfiguredSourcesRow(sources: List<Source>) {
        val header = HeaderItem(HEADER_SOURCES, getString(R.string.title_sources) + "（${sources.size}）")
        val rowAdapter = ArrayObjectAdapter(CardPresenter())
        sources.forEach { src ->
            val isHtml = src.kind == Source.Kind.HTML
            rowAdapter.add(VideoCard(
                id = src.url,
                title = src.name,
                subTitle = if (isHtml) "HTML 网站" else "JSON 协议",
                poster = null,
                siteKey = if (isHtml) TvBoxApp.get().sourceRepository.htmlSiteKey(src.url) else src.url,
                categoryId = null
            ))
        }
        rowsAdapter.add(ListRow(header, rowAdapter))
    }

    /**
     * 行 3+：每个站点的分类（异步加载）
     */
    private fun renderSiteCategoryRows(sources: List<Source>) {
        android.util.Log.i("HomeFragment", "renderSiteCategoryRows: sources=${sources.size}")
        sources.forEachIndexed { idx, src ->
            val isHtml = src.kind == Source.Kind.HTML
            val siteKey = if (isHtml) TvBoxApp.get().sourceRepository.htmlSiteKey(src.url) else src.url
            val siteKind = if (isHtml) "html://${src.url}" else src.url

            // v1.0.9 重要修复：
            // Leanback BrowseSupportFragment 在 loadHome 同步阶段之后不再重建 header 列表。
            // 如果用 lifecycleScope.launch 异步 add row，row 虽然被 add 但 header 不会显示。
            // 修复：同步 add 一个占位 row，fetchHomeCategories 异步填入卡片内容。
            addCategoryRowForSite(
                rowIndex = HEADER_SITES + idx,
                headerTitle = src.name,
                siteKey = siteKey,
                api = siteKind
            )
        }
    }

    /**
     * 兜底行：某个源探测失败时显示"站点加载失败"提示
     * v1.0.9 新增：避免以前异常被静默吞掉、用户看不到任何反馈的问题
     */
    private fun addLoadFailedSiteRow(rowIndex: Long, headerTitle: String, errorMessage: String) {
        android.util.Log.i("HomeFragment", "addLoadFailedSiteRow: headerTitle=$headerTitle, error=$errorMessage")
        if (!isAdded) return
        android.widget.Toast.makeText(requireContext(), "$headerTitle 加载失败: $errorMessage", android.widget.Toast.LENGTH_LONG).show()
        val header = HeaderItem(rowIndex, "$headerTitle · 加载失败")
        val rowAdapter = ArrayObjectAdapter(ActionPresenter())
        rowAdapter.add(ActionItem(
            id = 9981,
            title = "重试",
            subTitle = "重新加载该源"
        ) {
            Toast.makeText(requireContext(), "重试中…", Toast.LENGTH_SHORT).show()
            loadHome()
        })
        rowAdapter.add(ActionItem(
            id = 9982,
            title = "去设置检查",
            subTitle = "查看/删除该源（错误：${errorMessage.take(40)}）"
        ) {
            runCatching {
                startActivity(
                    Intent(requireContext(), com.simple.tvbox.ui.settings.SettingsActivity::class.java)
                )
            }.onFailure { android.util.Log.e("HomeFragment", "open settings failed", it) }
        })
        rowsAdapter.add(ListRow(header, rowAdapter))
    }

    /**
     * 添加某个站点的分类行（异步拉分类）
     */
    private fun addCategoryRowForSite(rowIndex: Long, headerTitle: String, siteKey: String, api: String) {
        android.util.Log.i("HomeFragment", "addCategoryRowForSite: headerTitle=$headerTitle, siteKey=$siteKey, api=$api")
        val client = VideoClientFactory.create(
            SpiderSite(
                key = siteKey,
                name = headerTitle,
                type = 1,
                api = api
            )
        )
        if (!client.isSupported()) return

        val rowAdapter = ArrayObjectAdapter(CardPresenter())
        // 占位
        rowAdapter.add(VideoCard(
            id = "${siteKey}__loading",
            title = headerTitle,
            subTitle = getString(R.string.loading),
            poster = null,
            siteKey = siteKey,
            categoryId = null
        ))
        val row = ListRow(HeaderItem(rowIndex, headerTitle), rowAdapter)
        rowsAdapter.add(row)

        lifecycleScope.launch {
            runCatching {
                val cats: List<VideoCategory> = withContext(Dispatchers.IO) {
                    client.fetchHomeCategories()
                }
                // 注意：rowsAdapter 可能已经被 loadHome 重建了，所以这里要检查 row 是否还在
                if (rowsAdapter.indexOf(row) < 0) return@runCatching
                rowAdapter.clear()
                if (cats.isEmpty()) {
                    rowAdapter.add(VideoCard(
                        id = "${siteKey}__empty",
                        title = headerTitle,
                        subTitle = "（无分类）",
                        poster = null,
                        siteKey = siteKey
                    ))
                } else {
                    cats.take(20).forEach { cat ->
                        rowAdapter.add(VideoCard(
                            id = "${siteKey}__${cat.id}",
                            title = cat.name,
                            subTitle = headerTitle,
                            poster = null,
                            siteKey = siteKey,
                            categoryId = cat.id
                        ))
                    }
                }
            }.onFailure { t ->
                android.util.Log.e("HomeFragment", "addCategoryRowForSite fetchHomeCategories failed for " + siteKey, t)
                if (!isAdded) return@onFailure
                if (rowsAdapter.indexOf(row) < 0) return@onFailure
                rowsAdapter.remove(row)
                addLoadFailedSiteRow(rowIndex, headerTitle, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val HEADER_QUICK = 0L
        private const val HEADER_EMPTY = 1L
        private const val HEADER_HOT = 2L
        private const val HEADER_SOURCES = 3L
        private const val HEADER_DOUBAN_QUICK = 10L
        private const val HEADER_DOUBAN_MOVIE = 11L
        private const val HEADER_DOUBAN_TV = 12L
        private const val HEADER_SITES = 100L
    }
}

/** 顶部按钮 / 快捷操作 */
data class ActionItem(
    val id: Long,
    val title: String,
    val subTitle: String? = null,
    val onClick: () -> Unit
)

/** 视频卡片 */
data class VideoCard(
    val id: String,
    val title: String,
    val subTitle: String?,
    val poster: String?,
    val siteKey: String,
    val categoryId: String? = null,
    val sourceUrl: String? = null,
    val videoId: String? = null,
    val historySubtitle: String? = null,
    val isHistory: Boolean = false
)

/** 首页豆瓣海报卡片（点击触发聚合搜索） */
data class DoubanPosterCard(
    val id: String,
    val title: String,
    val subTitle: String?,
    val poster: String?,
    val detailUrl: String,
    val item: DoubanService.DoubanItem? = null
)
