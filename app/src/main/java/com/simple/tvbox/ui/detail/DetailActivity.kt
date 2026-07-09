package com.simple.tvbox.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.source.VideoClientFactory
import com.simple.tvbox.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频详情 + 剧集列表。
 * 剧集用网格展示，适合遥控器上下左右选择。
 */
class DetailActivity : FragmentActivity() {

    private lateinit var titleView: TextView
    private lateinit var descView: TextView
    private lateinit var episodeContainer: GridLayout
    private var site: SpiderSite? = null
    private var videoId: String = ""
    private var episodes: List<Pair<String, String>> = emptyList()
    private var title: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        titleView = findViewById(R.id.detail_title)
        descView = findViewById(R.id.detail_desc)
        episodeContainer = findViewById(R.id.detail_episodes)

        val siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: return finish()
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return finish()
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        titleView.text = title

        site = findSite(siteKey)
        if (site == null) {
            Toast.makeText(this, "未找到站点", Toast.LENGTH_LONG).show()
            return
        }
        load()
    }

    private fun findSite(key: String): SpiderSite? {
        val repo = TvBoxApp.get().sourceRepository
        return repo.getAllSources()
            .firstNotNullOfOrNull { src -> repo.findSite(src.url, key) }
    }

    private fun load() {
        val s = site ?: return
        val client = VideoClientFactory.create(s)
        lifecycleScope.launch {
            try {
                val (info, eps) = withContext(Dispatchers.IO) {
                    val detail = client.fetchDetailInfo(videoId)
                    val episodes = client.fetchEpisodes(videoId)
                    detail to episodes
                }
                descView.text = listOfNotNull(
                    info?.optString("vod_year").ifBlankOrNull(),
                    info?.optString("vod_area").ifBlankOrNull(),
                    info?.optString("vod_director").ifBlankOrNull(),
                    info?.optString("vod_actor").ifBlankOrNull()
                ).joinToString("  ·  ").ifBlank { "暂无简介" }
                episodes = eps
                renderEpisodes()
            } catch (t: Throwable) {
                Toast.makeText(this@DetailActivity, "加载失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun String?.ifBlankOrNull(): String? = if (this.isNullOrBlank()) null else this

    private fun renderEpisodes() {
        episodeContainer.removeAllViews()
        episodes.forEachIndexed { index, (name, url) ->
            val tv = TextView(this).apply {
                text = name
                textSize = 16f
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_card)
                setTextColor(Color.WHITE)
                setPadding(24, 16, 24, 16)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dpToPx(150)
                    height = dpToPx(76)
                    setMargins(8, 8, 8, 8)
                }
                setOnClickListener { play(index) }
            }
            episodeContainer.addView(tv)
        }
        if (episodes.isEmpty()) {
            val tv = TextView(this).apply {
                text = "该视频没有可播放的剧集"
                setTextColor(Color.LTGRAY)
                setPadding(40, 20, 40, 20)
            }
            episodeContainer.addView(tv)
        }
    }

    private fun play(index: Int) {
        val s = site ?: return
        if (index < 0 || index >= episodes.size) return
        val (name, url) = episodes[index]
        val srcUrl = findSourceUrlForSite(s.key)
        startActivity(
            PlayerActivity.intent(
                this,
                title = titleView.text.toString(),
                subtitle = name,
                siteKey = s.key,
                sourceUrl = srcUrl,
                episodeUrl = url,
                videoId = videoId,
                episodeList = episodes,
                episodeIndex = index
            )
        )
    }

    private fun findSourceUrlForSite(siteKey: String): String {
        val repo = TvBoxApp.get().sourceRepository
        return repo.getAllSources().firstOrNull { src -> repo.findSite(src.url, siteKey) != null }?.url ?: ""
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SITE_KEY = "site_key"
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, siteKey: String, videoId: String, title: String) =
            Intent(ctx, DetailActivity::class.java).apply {
                putExtra(EXTRA_SITE_KEY, siteKey)
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
