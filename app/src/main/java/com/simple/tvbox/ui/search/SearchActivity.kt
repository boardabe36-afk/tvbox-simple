package com.simple.tvbox.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.source.VideoClientFactory
import com.simple.tvbox.ui.detail.DetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：聚合多个源做搜索，结果全局按匹配精度排序并网格展示。
 */
class SearchActivity : FragmentActivity() {

    private lateinit var searchInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var resultsContainer: GridLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        searchInput = findViewById(R.id.search_input)
        progress = findViewById(R.id.search_progress)
        emptyText = findViewById(R.id.search_empty)
        resultsContainer = findViewById(R.id.search_results_container)

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

        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let { query ->
            searchInput.setText(query)
            searchInput.setSelection(query.length)
            doSearch()
        }
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
                    sites.map { site ->
                        async {
                            runCatching {
                                val client = VideoClientFactory.create(site)
                                if (client.isSupported()) {
                                    client.search(keyword, 1).map { v ->
                                        SearchResult(site, v, score(keyword, v.title, v.subTitle))
                                    }
                                } else emptyList()
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                        .flatten()
                        .distinctBy { it.site.key + "::" + it.item.id }
                        .sortedWith(
                            compareByDescending<SearchResult> { it.score }
                                .thenBy { it.item.title.length }
                                .thenBy { it.site.name }
                        )
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
        val header = TextView(this).apply {
            text = "${results.size} 条结果 · 已按“$keyword”的匹配精度排序"
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

    private fun score(keyword: String, title: String, subTitle: String?): Int {
        val q = normalize(keyword)
        val t = normalize(title)
        val sub = normalize(subTitle.orEmpty())
        if (q.isBlank() || t.isBlank()) return 0
        var s = 0
        when {
            t == q -> s += 10_000
            t.startsWith(q) -> s += 8_000
            t.contains(q) -> s += 6_000
            q.contains(t) -> s += 4_000
        }
        val compactQ = q.replace(" ", "")
        val compactT = t.replace(" ", "")
        when {
            compactT == compactQ -> s += 5_000
            compactT.startsWith(compactQ) -> s += 3_000
            compactT.contains(compactQ) -> s += 2_000
        }
        s += commonSubsequenceScore(compactQ, compactT) * 20
        if (sub.contains(q) || sub.contains(compactQ)) s += 300
        s -= kotlin.math.abs(compactT.length - compactQ.length) * 8
        return s
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[\\s·・:：,，.。!！?？《》<>\\[\\]（）()_\\-]+"), " ")
        .trim()

    private fun commonSubsequenceScore(a: String, b: String): Int {
        var i = 0
        var hit = 0
        for (ch in b) {
            if (i < a.length && a[i] == ch) {
                hit++
                i++
            }
        }
        return hit
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private data class SearchResult(val site: SpiderSite, val item: VideoItem, val score: Int)

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
