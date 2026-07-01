package com.simple.tvbox.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.Source
import com.simple.tvbox.model.WatchHistoryItem
import com.simple.tvbox.source.VideoClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频播放页。
 *
 * 用 Media3/ExoPlayer 播 m3u8/mp4 直链。
 *
 * 关键处理：
 * - m3u8 服务器要 Referer，否则 403
 * - m3u8 可能是 master playlist（嵌套），需要 HlsMediaSource 显式构造
 * - 错误友好显示：失败时显示错误覆盖层，提供"重试/返回"按钮
 * - 偶发 thisUrl 解析失败时（视频源临时挂掉），点重试能重抓
 * - 播放时自动保存观看历史，下次打开同一集自动断点续看
 */
class PlayerActivity : FragmentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorDetail: TextView
    private lateinit var retryBtn: Button
    private lateinit var closeBtn: Button

    private var title: String = ""
    private var subtitle: String = ""
    private var episodeUrl: String = ""      // 原始剧集 URL（如 /movie/.../...）
    private var siteKey: String = ""         // 站点 key
    private var sourceUrl: String = ""       // 源 URL（用于反查 site）
    private var videoId: String = ""         // 视频 ID（用于历史入口回到详情）
    private var currentResolvedUrl: String = ""  // 已经 resolve 过的 m3u8 缓存
    private var pendingResumeMs: Long = 0L
    private var hasAppliedResume = false
    private var historyKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
        episodeUrl = intent.getStringExtra(EXTRA_EPISODE_URL) ?: intent.getStringExtra(EXTRA_URL) ?: run {
            Toast.makeText(this, "无播放地址", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: ""
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""

        val historyRepo = TvBoxApp.get().watchHistoryRepository
        historyKey = historyRepo.buildKey(siteKey, sourceUrl, episodeUrl)
        historyRepo.find(siteKey, sourceUrl, episodeUrl)?.let { history ->
            if (history.positionMs > RESUME_THRESHOLD_MS) {
                pendingResumeMs = history.positionMs
            }
            currentResolvedUrl = history.resolvedUrl.orEmpty()
        }

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.player_title)
        loadingView = findViewById(R.id.player_loading)
        errorPanel = findViewById(R.id.player_error_panel)
        errorTitle = findViewById(R.id.player_error_title)
        errorDetail = findViewById(R.id.player_error_detail)
        retryBtn = findViewById(R.id.player_retry_btn)
        closeBtn = findViewById(R.id.player_close_btn)

        titleView.text = buildTitleText()

        retryBtn.setOnClickListener { resolveAndPlay(forceRefresh = true) }
        closeBtn.setOnClickListener { finish() }

        resolveAndPlay(forceRefresh = false)
    }

    /**
     * 重新解析剧集 URL → m3u8 → 播放。
     * forceRefresh=true 时不读缓存，从头跑。
     */
    private fun resolveAndPlay(forceRefresh: Boolean) {
        saveProgressIfNeeded()
        player?.release()
        player = null
        hasAppliedResume = false
        loadingView.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        playerView.visibility = View.INVISIBLE

        lifecycleScope.launch {
            try {
                val resolved = if (!forceRefresh && currentResolvedUrl.isNotBlank()) {
                    currentResolvedUrl
                } else {
                    withContext(Dispatchers.IO) { resolveUrl() }
                }
                currentResolvedUrl = resolved
                if (resolved.isBlank()) {
                    showError("无法解析播放地址",
                        "可能是该视频的源站链接已失效，请尝试其他源或反馈给开发者。")
                    return@launch
                }
                initPlayer(resolved)
            } catch (t: Throwable) {
                Log.e(TAG, "resolveAndPlay failed", t)
                showError("解析失败", "${t.javaClass.simpleName}: ${t.message?.take(120) ?: "未知"}")
            }
        }
    }

    private fun resolveUrl(): String {
        // 找站点
        val app = TvBoxApp.get()
        val src = app.sourceRepository.getAllSources().firstOrNull { src ->
            src.url == sourceUrl || (src.kind == Source.Kind.HTML && sourceUrl == src.url)
        } ?: run {
            Log.w(TAG, "no source matched sourceUrl=$sourceUrl")
            // 兜底：直接拿 episodeUrl 当 m3u8
            return episodeUrl
        }
        val site = app.sourceRepository.findSite(src.url, siteKey) ?: return episodeUrl
        val client = VideoClientFactory.create(site)
        val info = client.resolvePlayUrl(episodeUrl)
        Log.i(TAG, "resolved url=${info.url}")
        return info.url
    }

    private fun initPlayer(url: String) {
        Log.i(TAG, "initPlayer url=$url")

        // 推断 Referer：从 URL 里抽 host 的根域名
        val referer = inferReferer(url)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer" to referer
                )
            )

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()
        playerView.player = exo
        playerView.visibility = View.VISIBLE

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                saveProgressIfNeeded()
                Log.e(TAG, "播放失败 code=${error.errorCode} name=${error.errorCodeName}", error)
                Log.e(TAG, "cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}")
                val msg = error.cause?.message?.take(200) ?: "未知错误"
                showError("播放失败 [${error.errorCodeName}]", msg)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    loadingView.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
                if (state == Player.STATE_READY) {
                    errorPanel.visibility = View.GONE
                    loadingView.visibility = View.GONE
                    applyResumeIfNeeded(exo)
                    saveProgressIfNeeded()
                }
                if (state == Player.STATE_ENDED) {
                    saveProgressIfNeeded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgressIfNeeded()
            }
        })

        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        val mediaSource: MediaSource = HlsMediaSource.Factory(httpFactory)
            .createMediaSource(mediaItem)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    private fun applyResumeIfNeeded(exo: ExoPlayer) {
        if (hasAppliedResume || pendingResumeMs <= RESUME_THRESHOLD_MS) return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        val safePosition = if (duration > 0) {
            pendingResumeMs.coerceAtMost((duration - END_IGNORE_THRESHOLD_MS).coerceAtLeast(0L))
        } else {
            pendingResumeMs
        }
        if (safePosition > RESUME_THRESHOLD_MS) {
            exo.seekTo(safePosition)
            Toast.makeText(this, "已从 ${formatTime(safePosition)} 继续播放", Toast.LENGTH_SHORT).show()
        }
        hasAppliedResume = true
    }

    private fun saveProgressIfNeeded() {
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        val shouldKeepProgress = position > RESUME_THRESHOLD_MS &&
            (duration <= 0L || position < duration - END_IGNORE_THRESHOLD_MS)
        val savedPosition = if (shouldKeepProgress) position else 0L
        val item = WatchHistoryItem(
            key = historyKey,
            title = title,
            subtitle = subtitle.ifBlank { null },
            siteKey = siteKey,
            sourceUrl = sourceUrl,
            videoId = videoId.ifBlank { null },
            episodeUrl = episodeUrl,
            resolvedUrl = currentResolvedUrl.ifBlank { null },
            positionMs = savedPosition,
            durationMs = duration,
            updatedAt = System.currentTimeMillis()
        )
        TvBoxApp.get().watchHistoryRepository.upsert(item)
    }

    private fun buildTitleText(): String {
        val base = if (subtitle.isNotBlank()) "$title · $subtitle" else title
        return if (pendingResumeMs > RESUME_THRESHOLD_MS) {
            "$base  ·  上次看到 ${formatTime(pendingResumeMs)}"
        } else {
            base
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun showError(title: String, detail: String) {
        loadingView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        errorTitle.text = title
        errorDetail.text = detail
    }

    /**
     * 推断 Referer：从 m3u8 URL 抽主域名（不含 path/query）。
     * 国内很多 CDN 要 Referer 是源站才能放。
     */
    private fun inferReferer(url: String): String {
        return runCatching {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}/"
        }.getOrDefault("https://www.baidu.com/")
    }

    override fun onPause() {
        saveProgressIfNeeded()
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        saveProgressIfNeeded()
        super.onDestroy()
        player?.release()
        player = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (errorPanel.visibility == View.VISIBLE) {
                finish()
                return true
            }
            saveProgressIfNeeded()
            player?.stop()
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "Player"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        /** 原始剧集 URL（/movie/.../...），未 resolve */
        private const val EXTRA_EPISODE_URL = "episode_url"
        private const val EXTRA_URL = "url"  // 兼容旧调用：直接传 m3u8
        private const val EXTRA_SITE_KEY = "site_key"
        private const val EXTRA_SOURCE_URL = "source_url"
        private const val EXTRA_VIDEO_ID = "video_id"

        private const val RESUME_THRESHOLD_MS = 10_000L
        private const val END_IGNORE_THRESHOLD_MS = 30_000L

        /**
         * 旧版调用：直接传已 resolve 的 m3u8（向后兼容）
         */
        fun intent(ctx: Context, title: String, subtitle: String?, url: String) =
            Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle ?: "")
                putExtra(EXTRA_URL, url)
            }

        /**
         * 新版调用：传剧集 URL + 站点信息，进入播放页后再 resolve。
         * 这样 thisUrl 临时挂掉时点重试能再次请求。
         */
        fun intent(
            ctx: Context, title: String, subtitle: String?,
            siteKey: String, sourceUrl: String, episodeUrl: String,
            videoId: String? = null
        ) = Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SUBTITLE, subtitle ?: "")
            putExtra(EXTRA_SITE_KEY, siteKey)
            putExtra(EXTRA_SOURCE_URL, sourceUrl)
            putExtra(EXTRA_EPISODE_URL, episodeUrl)
            putExtra(EXTRA_VIDEO_ID, videoId ?: "")
        }
    }
}
