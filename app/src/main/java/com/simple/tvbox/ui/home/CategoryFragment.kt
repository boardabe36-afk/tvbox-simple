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
 * 单个分类下的视频列表：网格展示，不再横向一排。
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
        title = categoryName
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
        val client = VideoClientFactory.create(s)
        lifecycleScope.launch {
            try {
                val items: List<VideoItem> = withContext(Dispatchers.IO) {
                    if (!client.isSupported()) throw IllegalStateException("该站点类型暂不支持")
                    client.fetchCategory(cid, 1)
                }
                render(items)
            } catch (t: Throwable) {
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
    }

    companion object {
        private const val ARG_SITE_KEY = "***"
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
