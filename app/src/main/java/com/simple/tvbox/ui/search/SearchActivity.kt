package com.simple.tvbox.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.source.VideoClientFactory
import com.simple.tvbox.ui.detail.DetailActivity
import com.simple.tvbox.util.SearchHistoryPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：聚合多个源做搜索，结果全局按匹配精度排序并网格展示。
 *
 * v1.0.18 新增：搜索历史（横滑 chip，带 ✕ 删除按钮，输入框聚焦且无结果时显示）。
 */
class SearchActivity : FragmentActivity() {

    private lateinit var searchInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var resultsContainer: GridLayout
    private lateinit var historyPanel: View
    private lateinit var historyList: RecyclerView
    private lateinit var historyClearBtn: Button
    private lateinit var resultsScroll: View

    private val historyPrefs by lazy { SearchHistoryPrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        searchInput = findViewById(R.id.search_input)
        progress = findViewById(R.id.search_progress)
        emptyText = findViewById(R.id.search_empty)
        resultsContainer = findViewById(R.id.search_results_container)
        historyPanel = findViewById(R.id.search_history_panel)
        historyList = findViewById(R.id.search_history_list)
        historyClearBtn = findViewById(R.id.search_history_clear)
        resultsScroll = findViewById(R.id.search_results_scroll)

        historyList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        historyList.adapter = HistoryAdapter(emptyList(),
            onClick = { kw -> selectHistory(kw) },
            onRemove = { kw -> removeHistory(kw) }
        )

        historyClearBtn.setOnClickListener {
            historyPrefs.clear()
            refreshHistoryPanel()
        }

        // 输入框焦点变化：拿到焦点且无结果时显示历史列表
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && resultsContainer.childCount == 0) {
                showHistoryPanel()
            }
        }
        searchInput.setOnClickListener {
            if (resultsContainer.childCount == 0) showHistoryPanel()
        }

        searchInput.setOnEditorActionListener { _, actionId, event ->
            val isSubmit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSubmit) {
                doSearch()
                true
            } else false
        }
        searchInput.requestFocus()

        val initialQuery = intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }
        if (initialQuery != null) {
            searchInput.setText(initialQuery)
            searchInput.setSelection(initialQuery.length)
            doSearch()
        } else {
            refreshHistoryPanel()
        }
    }

    private fun selectHistory(keyword: String) {
        searchInput.setText(keyword)
        searchInput.setSelection(keyword.length)
        doSearch()
    }

    private fun removeHistory(keyword: String) {
        historyPrefs.remove(keyword)
        refreshHistoryPanel()
    }

    private fun refreshHistoryPanel() {
        val items = historyPrefs.getAll()
        (historyList.adapter as? HistoryAdapter)?.update(items)
        if (items.isNotEmpty() && resultsContainer.childCount == 0) {
            showHistoryPanel()
        } else {
            hideHistoryPanel()
        }
    }

    private fun showHistoryPanel() {
        historyPanel.visibility = View.VISIBLE
        resultsScroll.visibility = View.GONE
    }

    private fun hideHistoryPanel() {
        historyPanel.visibility = View.GONE
        resultsScroll.visibility = View.VISIBLE
    }

    private fun doSearch() {
        val keyword = searchInput.text.toString().trim()
        if (keyword.isBlank()) return
        val sources = TvBoxApp.get().sourceRepository.getAllSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, R.string.search_need_source, Toast.LENGTH_LONG).show()
            return
        }
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        resultsContainer.removeAllViews()
        hideHistoryPanel()
        historyPrefs.add(keyword)

        lifecycleScope.launch {
            try {
                val sites: List<SpiderSite> = withContext(Dispatchers.IO) {
                    TvBoxApp.get().sourceRepository.loadAllSites()
                }
                if (sites.isEmpty()) {
                    progress.visibility = View.GONE
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "没有兼容的视频源（请在设置中检查源）"
                    return@launch
                }

                val ranked: List<SearchResult> = withContext(Dispatchers.IO) {
                    val supportedSites = sites.filter { VideoClientFactory.create(it).isSupported() }
                    android.util.Log.i("SearchActivity", "搜索 \"$keyword\" across ${sites.size} sources, ${supportedSites.size} supported")
                    supportedSites.map { site ->
                        async {
                            val perSiteStart = System.currentTimeMillis()
                            val results = runCatching {
                                val client = VideoClientFactory.create(site)
                                if (client.isSupported()) {
                                    val primaryResults = client.search(keyword, 1)
                                        .map { v ->
                                            SearchResult(
                                                site,
                                                v,
                                                MatchScorer.score(keyword, v.title, v.subTitle)
                                            )
                                        }
                                    val cleanedTitle = stripSearchNoise(keyword)
                                    val secondaryResults = if (cleanedTitle.isNotBlank() && cleanedTitle != keyword) {
                                        runCatching {
                                            client.search(cleanedTitle, 1).map { v ->
                                                SearchResult(
                                                    site,
                                                    v,
                                                    MatchScorer.score(cleanedTitle, v.title, v.subTitle) - 200
                                                )
                                            }
                                        }.getOrDefault(emptyList())
                                    } else emptyList()
                                    primaryResults + secondaryResults
                                } else emptyList()
                            }.getOrElse {
                                android.util.Log.w("SearchActivity", "source ${site.key} failed: ${it.message}")
                                emptyList()
                            }
                            val elapsed = System.currentTimeMillis() - perSiteStart
                            android.util.Log.i("SearchActivity", "source ${site.key} returned ${results.size} items in ${elapsed}ms")
                            results
                        }
                    }.awaitAll()
                        .flatten()
                        .also { all ->
                            android.util.Log.i("SearchActivity", "合并 ${all.size} 条结果 (未去重)")
                        }
                        .groupBy { it.site.key + "::" + it.item.id }
                        .map { (_, group) -> group.maxByOrNull { it.score }!! }
                        .filter { it.score > -1000 }
                        .let { dedupBySiteTop3(it) }
                        .sortedWith(
                            compareByDescending<SearchResult> { it.score }
                                .thenBy { it.item.title.length }
                                .thenBy { it.site.name }
                        )
                        .also { final ->
                            val perSite = final.groupBy { it.site.key }.mapValues { it.value.size }
                            android.util.Log.i("SearchActivity", "最终结果 ${final.size} 条, 分布: $perSite")
                        }
                }

                progress.visibility = View.GONE
                if (ranked.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = getString(R.string.search_no_results)
                } else {
                    renderResults(keyword, ranked)
                }
            } catch (t: Throwable) {
                progress.visibility = View.GONE
                Toast.makeText(this@SearchActivity, "搜索失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderResults(keyword: String, results: List<SearchResult>) {
        resultsContainer.removeAllViews()
        val siteCount = results.map { it.site.key }.distinct().size
        val header = TextView(this).apply {
            text = "${results.size} 条结果 · 跨 $siteCount 个源 · 已按“$keyword”的匹配精度排序"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
            layoutParams = GridLayout.LayoutParams().apply {
                width = GridLayout.LayoutParams.MATCH_PARENT
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, 4)
            }
        }
        resultsContainer.addView(header)
        results.take(100).forEach { result -> resultsContainer.addView(buildCardView(result)) }
    }

    private fun buildCardView(result: SearchResult): View {
        val v = result.item
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            layoutParams = GridLayout.LayoutParams().apply {
                width = dpToPx(230)
                height = dpToPx(138)
                setMargins(10, 10, 10, 10)
            }
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            setOnClickListener {
                startActivity(
                    DetailActivity.intent(
                        this@SearchActivity,
                        siteKey = v.sourceKey,
                        videoId = v.id,
                        title = v.title
                    )
                )
            }
        }
        card.addView(TextView(this).apply {
            text = v.title
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = listOfNotNull(
                v.subTitle?.takeIf { it.isNotBlank() },
                result.site.name,
                "匹配 ${result.score}"
            ).joinToString(" · ")
            textSize = 12f
            setTextColor(Color.LTGRAY)
            maxLines = 2
            setPadding(0, dpToPx(6), 0, 0)
        })
        return card
    }

    private val SEARCH_NOISE = listOf(
        "1080p", "720p", "4k", "8k", "hd", "bd",
        "国语", "粤语", "中字", "双语", "高清", "蓝光",
        "全集", "完整版", "更新至", "完结"
    )

    private fun stripSearchNoise(keyword: String): String {
        var k = keyword
        for (n in SEARCH_NOISE) {
            k = k.replace(Regex("(?i)\\b" + Regex.escape(n) + "\\b"), "")
        }
        k = k.replace(Regex("[\\[\\]【】()（）]"), " ")
        return k.trim().replace(Regex("\\s+"), " ")
    }

    private fun dedupBySiteTop3(results: List<SearchResult>): List<SearchResult> = dedupBySiteTopN(results, 8)

    private fun dedupBySiteTopN(results: List<SearchResult>, topN: Int): List<SearchResult> {
        val bySite = results.groupBy { it.site.key }
        return bySite.flatMap { (_, list) ->
            list.sortedByDescending { it.score }
                .mapIndexed { idx, r ->
                    if (idx < topN) r
                    else r.copy(score = r.score - (idx - (topN - 1)) * 800)
                }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private data class SearchResult(val site: SpiderSite, val item: VideoItem, val score: Int)

    /**
     * v1.0.18 搜索历史 chip Adapter（带 ✕ 删除按钮）
     */
    private inner class HistoryAdapter(
        var items: List<String>,
        val onClick: (String) -> Unit,
        val onRemove: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        fun update(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val kw = items[position]
            holder.keyword.text = kw
            holder.itemView.setOnClickListener { onClick(kw) }
            holder.remove.setOnClickListener { onRemove(kw) }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val keyword: TextView = view.findViewById(R.id.item_history_keyword)
            val remove: TextView = view.findViewById(R.id.item_history_remove)
        }
    }

    companion object {
        private const val EXTRA_QUERY = "query"

        fun intent(ctx: Context, query: String? = null) =
            Intent(ctx, SearchActivity::class.java).apply {
                if (!query.isNullOrBlank()) putExtra(EXTRA_QUERY, query)
            }

        fun launchFromRemote(ctx: Context, query: String) {
            val intent = intent(ctx, query).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ctx.startActivity(intent)
        }
    }
}