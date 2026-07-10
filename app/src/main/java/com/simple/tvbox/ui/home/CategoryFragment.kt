package com.simple.tvbox.ui.home

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoItem
import com.simple.tvbox.source.VideoClientFactory
import com.simple.tvbox.ui.detail.DetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个分类下的视频列表：网格展示。
 *
 * v1.0.19:
 * - 顶栏标题包含分类 ID，便于诊断"所有分类显示同一列表"的源端 bug
 * - 每次 fetchCategory 走 Log.i 输出 URL + 返回数量
 * - 加载完成后 Toast 提示总结果数
 */
class CategoryFragment : VerticalGridSupportFragment() {

    private val gridAdapter = ArrayObjectAdapter(CardPresenter())
    private var site: SpiderSite? = null
    private var categoryId: String? = null
    private var categoryName: String = "分类"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = findSiteFromAnySource()
        categoryId = arguments?.getString(ARG_CATEGORY_ID)
        categoryName = arguments?.getString(ARG_CATEGORY_NAME) ?: "分类"
        // 标题带分类 ID，用户能一眼看到当前分类 ID 是否真不同
        title = "$categoryName [${categoryId ?: "?"}]"
        gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = 4
            shadowEnabled = true
        }
        adapter = gridAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoCard) {
                val s = site ?: return@OnItemViewClickedListener
                startActivity(
                    DetailActivity.intent(
                        requireContext(),
                        siteKey = s.key,
                        videoId = item.id,
                        title = item.title
                    )
                )
            }
        }
        // 顶栏搜索按钮点击 = 手动刷新（调试用）
        setOnSearchClickedListener {
            Toast.makeText(
                requireContext(),
                "手动刷新分类 [$categoryId] ${site?.key}",
                Toast.LENGTH_SHORT
            ).show()
            android.util.Log.i("CategoryFragment", "Manual refresh for cid=$categoryId site=${site?.key}")
            load()
        }
        load()
    }

    private fun findSiteFromAnySource(): SpiderSite? {
        val key = arguments?.getString(ARG_SITE_KEY) ?: return null
        val repo = TvBoxApp.get().sourceRepository
        return repo.getAllSources()
            .firstNotNullOfOrNull { src -> repo.findSite(src.url, key) }
    }

    private fun load() {
        val s = site ?: run {
            Toast.makeText(requireContext(), "未找到站点", Toast.LENGTH_LONG).show()
            return
        }
        val cid = categoryId
        if (cid.isNullOrBlank()) {
            Toast.makeText(requireContext(), "无效的分类", Toast.LENGTH_LONG).show()
            return
        }
        android.util.Log.i("CategoryFragment", "fetchCategory site=${s.key} cid=$cid api=${s.api}")
        val client = VideoClientFactory.create(s)
        lifecycleScope.launch {
            try {
                val items: List<VideoItem> = withContext(Dispatchers.IO) {
                    if (!client.isSupported()) throw IllegalStateException("该站点类型暂不支持")
                    client.fetchCategory(cid, 1)
                }
                android.util.Log.i("CategoryFragment", "fetchCategory returned ${items.size} items for cid=$cid")
                render(items)
            } catch (t: Throwable) {
                android.util.Log.e("CategoryFragment", "fetchCategory FAILED for cid=$cid", t)
                Toast.makeText(requireContext(), "加载失败：${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render(items: List<VideoItem>) {
        gridAdapter.clear()
        items.forEach { v ->
            gridAdapter.add(
                VideoCard(
                    id = v.id,
                    title = v.title,
                    subTitle = v.subTitle,
                    poster = v.poster,
                    siteKey = v.sourceKey
                )
            )
        }
        if (items.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                "${categoryName} · 共 ${items.size} 个结果",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val ARG_SITE_KEY = "site_key"
        private const val ARG_CATEGORY_ID = "category_id"
        private const val ARG_CATEGORY_NAME = "category_name"

        fun newInstance(siteKey: String, categoryId: String?, name: String): CategoryFragment {
            return CategoryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SITE_KEY, siteKey)
                    putString(ARG_CATEGORY_ID, categoryId)
                    putString(ARG_CATEGORY_NAME, name)
                }
            }
        }
    }
}