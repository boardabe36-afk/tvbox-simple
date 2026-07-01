package com.simple.tvbox.ui.history

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.ui.home.CardPresenter
import com.simple.tvbox.ui.home.VideoCard
import com.simple.tvbox.ui.player.PlayerActivity

/** 最近观看独立入口，网格展示全部历史。 */
class HistoryFragment : VerticalGridSupportFragment() {

    private val gridAdapter = ArrayObjectAdapter(CardPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "最近观看"
        gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = 4
            shadowEnabled = true
        }
        adapter = gridAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val card = item as? VideoCard ?: return@OnItemViewClickedListener
            startActivity(
                PlayerActivity.intent(
                    requireContext(),
                    title = card.title,
                    subtitle = card.historySubtitle,
                    siteKey = card.siteKey,
                    sourceUrl = card.sourceUrl.orEmpty(),
                    episodeUrl = card.id,
                    videoId = card.videoId
                )
            )
        }
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        gridAdapter.clear()
        val history = TvBoxApp.get().watchHistoryRepository.getAll()
            .filter { it.positionMs > 0L }
        history.forEach { h ->
            gridAdapter.add(VideoCard(
                id = h.episodeUrl,
                title = h.title,
                subTitle = listOfNotNull(
                    h.subtitle?.takeIf { it.isNotBlank() },
                    "看到 ${formatTime(h.positionMs)}"
                ).joinToString(" · "),
                poster = null,
                siteKey = h.siteKey,
                sourceUrl = h.sourceUrl,
                videoId = h.videoId,
                historySubtitle = h.subtitle,
                isHistory = true
            ))
        }
        if (history.isEmpty()) {
            Toast.makeText(requireContext(), "暂无观看历史", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }
}
