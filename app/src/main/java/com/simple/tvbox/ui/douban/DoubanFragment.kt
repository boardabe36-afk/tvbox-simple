package com.simple.tvbox.ui.douban

import android.content.Intent
import android.net.Uri
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
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.source.DoubanService
import com.simple.tvbox.source.VideoClientFactory
import com.simple.tvbox.ui.detail.DetailActivity
import com.simple.tvbox.ui.home.ActionItem
import com.simple.tvbox.ui.home.ActionPresenter
import com.simple.tvbox.ui.home.CardPresenter
import com.simple.tvbox.ui.home.VideoCard
import com.simple.tvbox.ui.search.MatchScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 豆瓣 Fragment。
 *
 * 模式：
 *  - home: 电影热门 + 电视剧热门 + 电影 Top250 + 电视剧 Top250
 *  - movie: 只看电影频道（多个标签为行）
 *  - tv:    只看电视剧频道
 *
 * 每行 = 一个标签下的视频列表。
 *
 * 点击单条：触发"标题聚合搜索" → top1 → DetailActivity 播放。
 *          全部搜不到 → 浏览器打开豆瓣详情页。
 */
class DoubanFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    private val tab: String by lazy {
        arguments?.getString(ARG_TAB) ?: DoubanActivity.TAB_HOME
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = titleForTab(tab)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.tv_primary)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.tv_accent)

        // 搜索图标点击：跳到原生搜索页（让用户搜本地源）
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), com.simple.tvbox.ui.search.SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            handleItemClick(item)
        }

        adapter = rowsAdapter
    }

    override fun onResume() {
        super.onResume()
        loadRows()
    }

    private fun titleForTab(tab: String) = when (tab) {
        DoubanActivity.TAB_MOVIE -> "豆瓣 · 电影"
        DoubanActivity.TAB_TV -> "豆瓣 · 电视剧"
        else -> "豆瓣热门"
    }

    private fun handleItemClick(item: Any?) {
        when (item) {
            is DoubanCard -> onDoubanCardClicked(item)
            is ActionItem -> item.onClick()
        }
    }

    /**
     * 豆瓣卡片点击：聚合搜索真实视频源。
     */
    private fun onDoubanCardClicked(card: DoubanCard) {
        Toast.makeText(requireContext(), "正在搜索: ${card.title}", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val sources = TvBoxApp.get().sourceRepository.getAllSources()
                if (sources.isEmpty()) {
                    // 没有视频源：跳浏览器
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

                // 用 MatchScorer 全源打分
                val queries = buildSearchQueries(card.title)
                val ranked: List<Pair<SpiderSite, VideoItem>> = withContext(Dispatchers.IO) {
                    val collected: MutableList<Triple<SpiderSite, VideoItem, Int>> = mutableListOf()
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
                        .filter { it.third > 500 }  // 阈值：太低的不要
                        .sortedWith(
                            compareByDescending<Triple<SpiderSite, VideoItem, Int>> { it.third }
                                .thenBy { it.second.title.length }
                        )
                        .map { it.first to it.second }
                }

                val top = ranked.firstOrNull()
                if (top != null) {
                    startActivity(
                        DetailActivity.intent(
                            requireContext(),
                            siteKey = top.first.key,
                            videoId = top.second.id,
                            title = top.second.title
                        )
                    )
                } else {
                    // 全部搜不到：跳浏览器
                    Toast.makeText(requireContext(), "暂未找到资源", Toast.LENGTH_SHORT).show()
                    openInBrowser(card.detailUrl)
                }
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "搜索失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 从豆瓣标题生成多个搜索查询：
     * 1. 原标题
     * 2. 去年份/副标题后的干净标题（如 "狂飙 (2023)" → "狂飙"）
     * 3. 拆掉空格后的紧凑版本
     */
    private fun buildSearchQueries(rawTitle: String): List<String> {
        val out = LinkedHashSet<String>()
        val original = rawTitle.trim()
        if (original.isNotEmpty()) out.add(original)

        // 去括号内容（含中英括号）
        val noParens = original
            .replace(Regex("[\\[\\]【】()（）].*?[\\[\\]【】()（）]"), "")
            .replace(Regex("[\\[\\]【】()（）]"), "")
            .trim()
        if (noParens.isNotEmpty() && noParens != original) out.add(noParens)

        // 去尾部年份/季数
        val cleaned = noParens
            .replace(Regex("\\s*(19|20)\\d{2}\\s*$"), "")
            .replace(Regex("\\s*第[一二三四五六七八九十0-9]+季\\s*$"), "")
            .replace(Regex("\\s*Season\\s*[0-9]+\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isNotEmpty() && cleaned != noParens && cleaned != original) out.add(cleaned)

        return out.toList()
    }

    private fun openInBrowser(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "未找到浏览器，请安装浏览器后重试", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 加载行内容
     */
    private fun loadRows() {
        rowsAdapter.clear()

        // 顶部：切换 tab 的快捷入口
        renderTabSwitchRow()

        // 根据 tab 拉数据
        when (tab) {
            DoubanActivity.TAB_MOVIE -> renderChannel(DoubanService.DoubanMediaType.MOVIE)
            DoubanActivity.TAB_TV -> renderChannel(DoubanService.DoubanMediaType.TV)
            else -> {
                // home: 电影热门 + 电视剧热门 + Top250
                renderSingleRow("电影热门", DoubanService.DoubanMediaType.MOVIE, "热门", 0L)
                renderSingleRow("电视剧热门", DoubanService.DoubanMediaType.TV, "热门", 1L)
                renderSingleRow("电影 Top250", DoubanService.DoubanMediaType.MOVIE, "豆瓣高分", 2L)
                renderSingleRow("电视剧 Top250", DoubanService.DoubanMediaType.TV, "豆瓣高分", 3L)
                renderSingleRow("最新电影", DoubanService.DoubanMediaType.MOVIE, "最新", 4L)
                renderSingleRow("最新电视剧", DoubanService.DoubanMediaType.TV, "最新", 5L)
            }
        }
    }

    private fun renderTabSwitchRow() {
        val header = HeaderItem(0L, "切换频道")
        val rowAdapter = ArrayObjectAdapter(ActionPresenter())
        rowAdapter.add(ActionItem(id = 1L, title = "豆瓣首页") {
            (activity as? DoubanActivity)?.recreate()
        })
        rowAdapter.add(ActionItem(id = 2L, title = "只看电影") {
            startActivity(DoubanActivity.intent(requireContext(), DoubanActivity.TAB_MOVIE))
        })
        rowAdapter.add(ActionItem(id = 3L, title = "只看电视剧") {
            startActivity(DoubanActivity.intent(requireContext(), DoubanActivity.TAB_TV))
        })
        rowsAdapter.add(ListRow(header, rowAdapter))
    }

    /**
     * 单个频道的多行渲染（电影/电视剧）
     */
    private fun renderChannel(type: DoubanService.DoubanMediaType) {
        val tags = if (type == DoubanService.DoubanMediaType.MOVIE)
            DoubanService.MOVIE_TAGS else DoubanService.TV_TAGS
        // 主要标签放在前面
        val mainTags = listOf("热门", "最新", "豆瓣高分", "经典")
        val orderedTags = mainTags.filter { it in tags } + tags.filter { it !in mainTags }
        orderedTags.forEachIndexed { idx, tag ->
            renderSingleRow(
                headerTitle = tag,
                type = type,
                tag = tag,
                rowIndex = 100L + idx
            )
        }
    }

    /**
     * 渲染单个标签的行
     */
    private fun renderSingleRow(headerTitle: String, type: DoubanService.DoubanMediaType, tag: String, rowIndex: Long) {
        val rowAdapter = ArrayObjectAdapter(DoubanCardPresenter())
        // 占位
        rowAdapter.add(DoubanCard.placeholder(headerTitle))
        val row = ListRow(HeaderItem(rowIndex, headerTitle), rowAdapter)
        rowsAdapter.add(row)

        lifecycleScope.launch {
            runCatching {
                val items = DoubanService.fetch(type, tag, limit = 30, page = 1)
                if (rowsAdapter.indexOf(row) < 0) return@runCatching
                rowAdapter.clear()
                if (items.isEmpty()) {
                    rowAdapter.add(DoubanCard.error(headerTitle))
                } else {
                    items.forEach { rowAdapter.add(DoubanCard.fromDoubanItem(it)) }
                }
            }.onFailure {
                if (rowsAdapter.indexOf(row) >= 0) {
                    rowAdapter.clear()
                    rowAdapter.add(DoubanCard.error(headerTitle, it.message))
                }
            }
        }
    }

    companion object {
        private const val ARG_TAB = "tab"

        fun newInstance(tab: String): DoubanFragment {
            return DoubanFragment().apply {
                arguments = Bundle().apply { putString(ARG_TAB, tab) }
            }
        }
    }
}

/**
 * 豆瓣卡片：用于 DoubanCardPresenter
 */
data class DoubanCard(
    val id: String,
    val title: String,
    val subTitle: String?,
    val poster: String?,
    val detailUrl: String,
    val isPlaceholder: Boolean = false,
    val isError: Boolean = false,
    val errorMsg: String? = null
) {
    companion object {
        fun fromDoubanItem(item: DoubanService.DoubanItem): DoubanCard =
            DoubanCard(
                id = item.id,
                title = item.title,
                subTitle = buildString {
                    if (item.rate.isNotBlank()) append("豆瓣 ${item.rate}")
                    if (item.isNew) {
                        if (isNotEmpty()) append(" · ")
                        append("新上映")
                    }
                }.ifEmpty { null },
                poster = item.cover.ifBlank { null },
                detailUrl = item.detailUrl
            )

        fun placeholder(headerTitle: String): DoubanCard =
            DoubanCard(
                id = "_placeholder_",
                title = "加载中…",
                subTitle = headerTitle,
                poster = null,
                detailUrl = "",
                isPlaceholder = true
            )

        fun error(headerTitle: String, msg: String? = null): DoubanCard =
            DoubanCard(
                id = "_error_",
                title = "加载失败",
                subTitle = listOfNotNull(headerTitle, msg).joinToString(" · "),
                poster = null,
                detailUrl = "",
                isError = true
            )
    }
}