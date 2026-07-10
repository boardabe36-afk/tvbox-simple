package com.simple.tvbox.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Pair as AndroidPair
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.Source
import com.simple.tvbox.model.WatchHistoryItem
import com.simple.tvbox.source.VideoClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 视频播放页 (v1.0.16)。
 *
 * 用 Media3/ExoPlayer 播 m3u8/mp4 直链。
 *
 * 关键处理：
 * - m3u8 服务器要 Referer，否则 403
 * - m3u8 可能是 master playlist（嵌套），需要 HlsMediaSource 显式构造
 * - 错误友好显示：失败时显示错误覆盖层，提供"重试/返回"按钮
 * - 偶发 thisUrl 解析失败时（视频源临时挂掉），点重试能重抓
 * - 播放时自动保存观看历史，下次打开同一集自动断点续看
 * - **自动播放下一集**：播放结束(STATE_ENDED)时自动切到下一集
 * - **画质选择** (v1.0.16): ExoPlayer TrackSelector 切换视频 track 高度
 * - **音效模式** (v1.0.16): LoudnessEnhancer + Equalizer preset (标准/音乐/影院/杜比/人声)
 * - **选集面板** (v1.0.16): 进度条下方横滑剧集,一键跳播
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
    private lateinit var nextBtn: Button
    private lateinit var autoPlayToggle: Button
    private lateinit var qualityBtn: Button
    private lateinit var audioBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var episodesToggleBtn: Button
    private lateinit var episodesPanel: LinearLayout
    private lateinit var episodesList: RecyclerView
    private lateinit var episodesTitle: TextView
    private lateinit var optionsPanel: LinearLayout
    private lateinit var optionsTitle: TextView
    private lateinit var optionsList: RecyclerView

    private var title: String = ""
    private var subtitle: String = ""
    private var episodeUrl: String = ""
    private var siteKey: String = ""
    private var sourceUrl: String = ""
    private var videoId: String = ""
    private var currentResolvedUrl: String = ""
    private var pendingResumeMs: Long = 0L
    private var hasAppliedResume = false
    private var historyKey: String = ""

    // Episode list for auto-play next
    private var episodeList: List<Pair<String, String>> = emptyList()
    private var currentEpisodeIndex: Int = -1
    private var autoPlayNext: Boolean = true
    private var isSwitchingEpisode: Boolean = false

    // Audio session effects (v1.0.16)
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    // Quality selection (v1.0.16)
    private enum class QualityTier(val labelResId: Int, val maxHeight: Int) {
        AUTO(R.string.player_quality_auto, Int.MAX_VALUE),
        SD_360(R.string.player_quality_360, 360),
        SD_480(R.string.player_quality_480, 480),
        HD_720(R.string.player_quality_720, 720),
        FHD_1080(R.string.player_quality_1080, 1080),
        UHD_4K(R.string.player_quality_4k, 2160),
    }
    private var selectedQuality: QualityTier = QualityTier.AUTO

    // Audio mode selection (v1.0.16)
    private enum class AudioMode(val labelResId: Int) {
        STANDARD(R.string.player_audio_standard),
        MUSIC(R.string.player_audio_music),
        MOVIE(R.string.player_audio_movie),
        DOLBY(R.string.player_audio_dolby),
        VOICE(R.string.player_audio_voice),
        CUSTOM(R.string.player_audio_custom),
    }
    private var selectedAudioMode: AudioMode = AudioMode.STANDARD

    // Custom EQ band levels (mB), 5 bands covering low/mid/high.
    // Indexed from 0 = lowest band to 4 = highest band.
    private val customEqLevels = ShortArray(5) { 0 }
    private var lastKeyDownTimestamp: Long = 0L

    // Playback speed (v1.0.18)
    private enum class PlaybackSpeed(val rate: Float, val label: String) {
        SLOW_075(0.75f, "0.75x"),
        NORMAL_100(1.0f, "1.0x"),
        FAST_125(1.25f, "1.25x"),
        FAST_150(1.5f, "1.5x"),
        FAST_200(2.0f, "2.0x"),
    }
    private var selectedSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL_100

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

        // Parse episode list from JSON
        val episodeListJson = intent.getStringExtra(EXTRA_EPISODE_LIST) ?: ""
        if (episodeListJson.isNotBlank()) {
            try {
                val arr = JSONArray(episodeListJson)
                episodeList = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    obj.getString("name") to obj.getString("url")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse episode list", e)
            }
        }
        currentEpisodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, -1)
        if (currentEpisodeIndex < 0 && episodeList.isNotEmpty()) {
            currentEpisodeIndex = episodeList.indexOfFirst { it.second == episodeUrl }
        }

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
        nextBtn = findViewById(R.id.player_next_btn)
        autoPlayToggle = findViewById(R.id.player_autoplay_toggle)
        qualityBtn = findViewById(R.id.player_quality_btn)
        audioBtn = findViewById(R.id.player_audio_btn)
        speedBtn = findViewById(R.id.player_speed_btn)
        episodesToggleBtn = findViewById(R.id.player_episodes_toggle_btn)
        episodesPanel = findViewById(R.id.player_episodes_panel)
        episodesList = findViewById(R.id.player_episodes_list)
        episodesTitle = findViewById(R.id.player_episodes_title)
        optionsPanel = findViewById(R.id.player_options_panel)
        optionsTitle = findViewById(R.id.player_options_title)
        optionsList = findViewById(R.id.player_options_list)

        titleView.text = buildTitleText()
        updateNextButton()
        updateAutoPlayToggle()

        retryBtn.setOnClickListener { resolveAndPlay(forceRefresh = true) }
        closeBtn.setOnClickListener { finish() }
        nextBtn.setOnClickListener { playNextEpisode() }
        autoPlayToggle.setOnClickListener {
            autoPlayNext = !autoPlayNext
            updateAutoPlayToggle()
            Toast.makeText(this,
                if (autoPlayNext) "自动播放下一集已开启" else "自动播放下一集已关闭",
                Toast.LENGTH_SHORT).show()
        }
        qualityBtn.setOnClickListener { showQualityDialog() }
        audioBtn.setOnClickListener { showAudioDialog() }
        speedBtn.setOnClickListener { showSpeedDialog() }
        episodesToggleBtn.setOnClickListener { toggleEpisodesPanel() }
        updateSpeedButton()

        // Setup episodes list
        setupEpisodesList()
        updateEpisodesToggleButton()

        resolveAndPlay(forceRefresh = false)
    }

    private fun setupEpisodesList() {
        episodesList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val adapter = EpisodeAdapter(episodeList, currentEpisodeIndex) { index ->
            jumpToEpisode(index)
        }
        episodesList.adapter = adapter
        // Auto scroll to current episode
        if (currentEpisodeIndex >= 0) {
            episodesList.post {
                episodesList.scrollToPosition(currentEpisodeIndex.coerceAtLeast(0))
            }
        }
    }

    private fun updateEpisodesToggleButton() {
        episodesToggleBtn.visibility = if (episodeList.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleEpisodesPanel() {
        if (episodeList.isEmpty()) {
            Toast.makeText(this, R.string.player_episode_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (episodesPanel.visibility == View.VISIBLE) {
            episodesPanel.visibility = View.GONE
            optionsPanel.visibility = View.GONE
        } else {
            episodesPanel.visibility = View.VISIBLE
            optionsPanel.visibility = View.GONE
            episodesTitle.text = getString(R.string.player_episodes) + " (${episodeList.size}集)"
            // Scroll to current
            (episodesList.adapter as? EpisodeAdapter)?.let { adapter ->
                adapter.setSelectedIndex(currentEpisodeIndex)
                episodesList.post {
                    episodesList.scrollToPosition(currentEpisodeIndex.coerceAtLeast(0))
                }
            }
        }
    }

    /**
     * Jump to a specific episode index.
     */
    private fun jumpToEpisode(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        if (index == currentEpisodeIndex) {
            episodesPanel.visibility = View.GONE
            return
        }
        if (isSwitchingEpisode) return
        isSwitchingEpisode = true

        saveProgressIfNeeded()

        currentEpisodeIndex = index
        val (epName, epUrl) = episodeList[index]
        episodeUrl = epUrl
        subtitle = epName
        currentResolvedUrl = ""
        pendingResumeMs = 0L
        hasAppliedResume = false
        historyKey = TvBoxApp.get().watchHistoryRepository.buildKey(siteKey, sourceUrl, episodeUrl)

        TvBoxApp.get().watchHistoryRepository.find(siteKey, sourceUrl, episodeUrl)?.let { history ->
            if (history.positionMs > RESUME_THRESHOLD_MS) {
                pendingResumeMs = history.positionMs
            }
            currentResolvedUrl = history.resolvedUrl.orEmpty()
        }

        titleView.text = buildTitleText()
        updateNextButton()

        // Update episode adapter selection
        (episodesList.adapter as? EpisodeAdapter)?.setSelectedIndex(currentEpisodeIndex)
        episodesPanel.visibility = View.GONE

        Toast.makeText(this, "正在播放: $epName", Toast.LENGTH_SHORT).show()
        resolveAndPlay(forceRefresh = false)
        isSwitchingEpisode = false
    }

    private fun updateNextButton() {
        val hasNext = currentEpisodeIndex >= 0 && currentEpisodeIndex < episodeList.size - 1
        nextBtn.visibility = if (hasNext) View.VISIBLE else View.GONE
        nextBtn.text = if (hasNext) "下一集: ${episodeList[currentEpisodeIndex + 1].first}" else ""
    }

    private fun updateAutoPlayToggle() {
        autoPlayToggle.text = if (autoPlayNext) "自动连播: 开" else "自动连播: 关"
    }

    /**
     * Switch to the next episode.
     */
    private fun playNextEpisode() {
        if (currentEpisodeIndex < 0 || currentEpisodeIndex >= episodeList.size - 1) return
        jumpToEpisode(currentEpisodeIndex + 1)
    }

    // ============================================================
    // v1.0.16 Quality / Audio
    // ============================================================

    private fun showQualityDialog() {
        episodesPanel.visibility = View.GONE
        optionsPanel.visibility = View.VISIBLE
        optionsTitle.text = getString(R.string.player_quality)
        val options = QualityTier.entries
        val adapter = OptionAdapter(
            options.map { getString(it.labelResId) },
            options.indexOf(selectedQuality)
        ) { index ->
            val tier = QualityTier.entries[index]
            applyQuality(tier)
            optionsPanel.visibility = View.GONE
        }
        optionsList.layoutManager = LinearLayoutManager(this)
        optionsList.adapter = adapter
    }

    private fun showAudioDialog() {
        episodesPanel.visibility = View.GONE
        optionsPanel.visibility = View.VISIBLE
        optionsTitle.text = getString(R.string.player_audio_mode)
        val options = AudioMode.entries
        val adapter = OptionAdapter(
            options.map { getString(it.labelResId) },
            options.indexOf(selectedAudioMode)
        ) { index ->
            val mode = AudioMode.entries[index]
            if (mode == AudioMode.CUSTOM) {
                optionsPanel.visibility = View.GONE
                showCustomEqDialog()
            } else {
                applyAudioMode(mode)
                optionsPanel.visibility = View.GONE
            }
        }
        optionsList.layoutManager = LinearLayoutManager(this)
        optionsList.adapter = adapter
    }

    private fun showSpeedDialog() {
        episodesPanel.visibility = View.GONE
        optionsPanel.visibility = View.VISIBLE
        optionsTitle.text = getString(R.string.player_speed)
        val options = PlaybackSpeed.entries
        val adapter = OptionAdapter(
            options.map { it.label },
            options.indexOf(selectedSpeed)
        ) { index ->
            val speed = PlaybackSpeed.entries[index]
            applySpeed(speed)
            optionsPanel.visibility = View.GONE
        }
        optionsList.layoutManager = LinearLayoutManager(this)
        optionsList.adapter = adapter
    }

    private fun applySpeed(speed: PlaybackSpeed) {
        selectedSpeed = speed
        player?.setPlaybackSpeed(speed.rate)
        updateSpeedButton()
        Toast.makeText(this,
            getString(R.string.player_speed_applied, speed.label),
            Toast.LENGTH_SHORT).show()
    }

    private fun updateSpeedButton() {
        speedBtn.text = selectedSpeed.label
    }

    /**
     * v1.0.18 自定义 EQ 对话框：5 段 SeekBar，实时设置 Equalizer.setBandLevel。
     * Equalizer 一般有 5 个 band（设备不同 5-10 个）。取最低 5 个频率。
     */
    private fun showCustomEqDialog() {
        val eq = equalizer
        if (eq == null || audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            Toast.makeText(this, "音频会话未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val totalBands = eq.numberOfBands.toInt()
        if (totalBands < 1) {
            Toast.makeText(this, "本设备不支持均衡器", Toast.LENGTH_SHORT).show()
            return
        }
        // Equalizer 各 band 频率范围 (mHz) — 我们选 5 个代表频段：
        // 低音 (60-100Hz), 中低音 (250Hz), 中音 (1kHz), 中高音 (4kHz), 高音 (10-14kHz)
        // 这里用均匀取 5 段 (把可用的 band 等分到 5 段)
        val bandCount = 5
        val bandIndices = IntArray(bandCount) { i ->
            // 等分采样: 跳过最低 1/8 + 最高 1/8, 防止极端频段
            val start = (totalBands / 8).coerceAtLeast(0)
            val end = (totalBands - totalBands / 8).coerceAtMost(totalBands)
            val span = (end - start).coerceAtLeast(1)
            start + (i * span / bandCount).coerceAtMost(span - 1)
        }
        val labels = listOf(
            R.string.player_eq_bass,
            R.string.player_eq_mid_bass,
            R.string.player_eq_mid,
            R.string.player_eq_mid_treble,
            R.string.player_eq_treble
        )

        // 加载当前 band level
        for (i in bandIndices.indices) {
            try {
                customEqLevels[i] = eq.getBandLevel(bandIndices[i].toShort())
            } catch (_: Throwable) { customEqLevels[i] = 0 }
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_eq, null, false)
        val bandsContainer = dialogView.findViewById<LinearLayout>(R.id.eq_bands_container)
        val seekBars = mutableListOf<android.widget.SeekBar>()

        // Equalizer 范围 (mB)
        val range = runCatching { eq.bandLevelRange }.getOrDefault(shortArrayOf(-1500, 1500))
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()
        val spanMb = (maxMb - minMb).coerceAtLeast(1)

        val density = resources.displayMetrics.density

        for (i in 0 until bandCount) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            val label = TextView(this).apply {
                text = getString(labels[i])
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
            }
            val valueLabel = TextView(this).apply {
                text = String.format("%+d dB", customEqLevels[i] / 100)
                setTextColor(Color.LTGRAY)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
            }
            // 垂直 SeekBar 模拟：用横向 SeekBar 但 rotation 90 太复杂; 用 Horizontal 显示 db 数
            val sb = android.widget.SeekBar(this).apply {
                max = spanMb
                progress = (customEqLevels[i].toInt() - minMb).coerceIn(0, spanMb)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (40 * density).toInt()
                )
            }
            sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val mb = (progress + minMb).toShort()
                    customEqLevels[i] = mb
                    valueLabel.text = String.format("%+d dB", mb / 100)
                    try {
                        eq.setBandLevel(bandIndices[i].toShort(), mb)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setBandLevel failed", t)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
            col.addView(label)
            col.addView(sb)
            col.addView(valueLabel)
            bandsContainer.addView(col)
            seekBars.add(sb)
        }

        dialogView.findViewById<Button>(R.id.eq_reset).setOnClickListener {
            // 重置所有 band 到 0
            for (i in 0 until bandCount) {
                customEqLevels[i] = 0
                seekBars[i].progress = -minMb
                try { eq.setBandLevel(bandIndices[i].toShort(), 0) } catch (_: Throwable) {}
            }
            Toast.makeText(this, "已重置 EQ", Toast.LENGTH_SHORT).show()
        }
        dialogView.findViewById<Button>(R.id.eq_close).setOnClickListener {
            (it.context as? android.app.Activity)?.let { d -> /* close */ }
            // 用 dialog dismiss
            (dialogView.parent as? android.view.ViewGroup)?.removeView(dialogView)
            (this as? FragmentActivity)?.let { _ -> }
        }

        // 用 AlertDialog 包
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialogView.findViewById<Button>(R.id.eq_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
        // 标记为自定义 EQ
        selectedAudioMode = AudioMode.CUSTOM
    }

    /**
     * Apply quality selection by setting TrackSelectionParameters.
     */
    private fun applyQuality(tier: QualityTier) {
        val exo = player ?: run {
            selectedQuality = tier
            Toast.makeText(this, getString(R.string.player_quality_applied, getString(tier.labelResId)), Toast.LENGTH_SHORT).show()
            return
        }
        selectedQuality = tier
        val params = exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, tier.maxHeight)
            .build()
        // AUTO = clear restriction
        if (tier == QualityTier.AUTO) {
            val autoParams = exo.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints()
                .build()
            exo.trackSelectionParameters = autoParams
        } else {
            exo.trackSelectionParameters = params
        }
        Toast.makeText(this, getString(R.string.player_quality_applied, getString(tier.labelResId)), Toast.LENGTH_SHORT).show()
    }

    /**
     * Apply audio mode by configuring LoudnessEnhancer + Equalizer preset.
     */
    private fun applyAudioMode(mode: AudioMode) {
        selectedAudioMode = mode
        val sessId = audioSessionId
        if (sessId == C.AUDIO_SESSION_ID_UNSET) {
            Toast.makeText(this, getString(R.string.player_audio_applied, getString(mode.labelResId)), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Re-create loudness enhancer
            loudnessEnhancer?.let { eff -> eff.release() }
            loudnessEnhancer = LoudnessEnhancer(sessId).apply {
                // Mode-specific gain in millibels (1 dB = 100 mB)
                val gainMb = when (mode) {
                    AudioMode.STANDARD -> 0       // 0 dB (no boost)
                    AudioMode.MUSIC -> 600        // +6 dB (boost music)
                    AudioMode.MOVIE -> 800        // +8 dB (cinema punch)
                    AudioMode.DOLBY -> 1200       // +12 dB (Dolby-style loudness)
                    AudioMode.VOICE -> 400        // +4 dB (voice clarity)
                    AudioMode.CUSTOM -> 0         // Custom EQ uses user's band levels, no loudness boost
                }
                setTargetGain(gainMb)
                enabled = true
            }
            // Configure equalizer preset
            equalizer?.let { eff -> eff.release() }
            equalizer = Equalizer(0, sessId).apply {
                when (mode) {
                    AudioMode.STANDARD -> {
                        // Standard: keep current preset or first
                        enabled = true
                    }
                    AudioMode.MUSIC -> {
                        val idx = findPresetContains("Pop") ?: findPresetContains("Rock") ?: 0
                        usePreset(idx.toShort())
                        enabled = true
                    }
                    AudioMode.MOVIE -> {
                        val idx = findPresetContains("Movie") ?: findPresetContains("Cinema") ?: 0
                        usePreset(idx.toShort())
                        enabled = true
                    }
                    AudioMode.DOLBY -> {
                        // Boost bass + treble for dolby-style
                        val bands = numberOfBands.toInt()
                        if (bands > 0) setBandLevel(0.toShort(), 800.toShort())
                        if (bands > 7) setBandLevel(7.toShort(), 600.toShort())
                        enabled = true
                    }
                    AudioMode.VOICE -> {
                        // Boost mid for voice clarity
                        val bands = numberOfBands.toInt()
                        if (bands > 3) setBandLevel(3.toShort(), 700.toShort())
                        if (bands > 4) setBandLevel(4.toShort(), 700.toShort())
                        enabled = true
                    }
                    AudioMode.CUSTOM -> {
                        // Restore custom EQ band levels (user's previous adjustments)
                        enabled = true
                        try {
                            val bands = numberOfBands.toInt()
                            if (bands >= 1) {
                                // Map 5 custom levels to available bands
                                val start = (bands / 8).coerceAtLeast(0)
                                val end = (bands - bands / 8).coerceAtMost(bands)
                                val span = (end - start).coerceAtLeast(5)
                                for (i in 0 until 5) {
                                    val targetBand = (start + i * span / 5).coerceAtMost(bands - 1)
                                    setBandLevel(targetBand.toShort(), customEqLevels[i])
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "restore custom EQ failed", t)
                        }
                    }
                }
            }
            Log.i(TAG, "Audio mode applied: $mode (sessId=$sessId)")
        } catch (t: Throwable) {
            Log.w(TAG, "applyAudioMode failed for $mode", t)
        }
        Toast.makeText(this, getString(R.string.player_audio_applied, getString(mode.labelResId)), Toast.LENGTH_SHORT).show()
    }

    private fun findPresetContains(keyword: String): Int? {
        val eq = equalizer ?: return null
        return try {
            val total = eq.numberOfPresets.toInt()
            for (i in 0 until total) {
                val name = eq.getPresetName(i.toShort())
                if (name.contains(keyword, ignoreCase = true)) return i
            }
            null
        } catch (t: Throwable) { null }
    }

    private fun releaseAudioEffects() {
        val le = loudnessEnhancer
        if (le != null) {
            try { le.release() } catch (_: Throwable) {}
        }
        val eq = equalizer
        if (eq != null) {
            try { eq.release() } catch (_: Throwable) {}
        }
        loudnessEnhancer = null
        equalizer = null
    }

    // ============================================================
    // Original playback methods (largely unchanged from v1.0.15)
    // ============================================================

    /**
     * 重新解析剧集 URL -> m3u8 -> 播放。
     * forceRefresh=true 时不读缓存，从头跑。
     */
    private fun resolveAndPlay(forceRefresh: Boolean) {
        saveProgressIfNeeded()
        player?.release()
        player = null
        releaseAudioEffects()
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
        val app = TvBoxApp.get()
        val src = app.sourceRepository.getAllSources().firstOrNull { src ->
            src.url == sourceUrl || (src.kind == Source.Kind.HTML && sourceUrl == src.url)
        } ?: run {
            Log.w(TAG, "no source matched sourceUrl=$sourceUrl")
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

        // Apply current quality preference to new player
        if (selectedQuality != QualityTier.AUTO) {
            val params = exo.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, selectedQuality.maxHeight)
                .build()
            exo.trackSelectionParameters = params
        }

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
                    // Capture audio session ID once available
                    captureAudioSessionId(exo)
                }
                if (state == Player.STATE_ENDED) {
                    saveProgressIfNeeded()
                    onEpisodeEnded()
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

    private fun captureAudioSessionId(exo: ExoPlayer) {
        // Audio session ID becomes available after audio renderer has the output
        // Try several times as it's set asynchronously
        lifecycleScope.launch {
            repeat(10) { i ->
                val id = exo.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: run {
                    delay(300)
                    return@repeat
                }
                if (id != audioSessionId) {
                    audioSessionId = id
                    Log.i(TAG, "captured audioSessionId=$id")
                    // Re-apply current audio mode to new session
                    if (selectedAudioMode != AudioMode.STANDARD) {
                        applyAudioMode(selectedAudioMode)
                    }
                }
                return@launch
            }
        }
    }

    /**
     * Called when the current episode finishes playing (STATE_ENDED).
     * Auto-plays the next episode if enabled.
     */
    private fun onEpisodeEnded() {
        if (!autoPlayNext) return
        if (currentEpisodeIndex < 0 || currentEpisodeIndex >= episodeList.size - 1) return

        Log.i(TAG, "Episode ended, auto-playing next (index=${currentEpisodeIndex + 1})")
        lifecycleScope.launch {
            delay(800)
            playNextEpisode()
        }
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
        val epInfo = if (episodeList.isNotEmpty() && currentEpisodeIndex >= 0) {
            " (${currentEpisodeIndex + 1}/${episodeList.size})"
        } else ""
        return if (pendingResumeMs > RESUME_THRESHOLD_MS) {
            "$base$epInfo  ·  上次看到 ${formatTime(pendingResumeMs)}"
        } else {
            "$base$epInfo"
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
        player?.release()
        player = null
        releaseAudioEffects()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (errorPanel.visibility == View.VISIBLE) {
                finish()
                return true
            }
            // Close any open panel first
            if (optionsPanel.visibility == View.VISIBLE || episodesPanel.visibility == View.VISIBLE) {
                optionsPanel.visibility = View.GONE
                episodesPanel.visibility = View.GONE
                return true
            }
            saveProgressIfNeeded()
            player?.stop()
            finish()
            return true
        }
        // KEYCODE_DPAD_RIGHT long press: skip to next episode
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event?.repeatCount ?: 0 > 5)) {
            if (currentEpisodeIndex >= 0 && currentEpisodeIndex < episodeList.size - 1) {
                playNextEpisode()
                return true
            }
        }
        // KEYCODE_DPAD_LEFT long press: previous episode
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event?.repeatCount ?: 0 > 5)) {
            if (currentEpisodeIndex > 0) {
                jumpToEpisode(currentEpisodeIndex - 1)
                return true
            }
        }
        // v1.0.18 KEYCODE_DPAD_CENTER long press: 循环切换倍速
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event?.repeatCount ?: 0 == 0) {
            lastKeyDownTimestamp = System.currentTimeMillis()
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event?.action == KeyEvent.ACTION_UP) {
            val held = System.currentTimeMillis() - lastKeyDownTimestamp
            if (held in 400..1500 && selectedSpeed != PlaybackSpeed.NORMAL_100) {
                // 长按松开回到 1.0x
                applySpeed(PlaybackSpeed.NORMAL_100)
                return true
            }
        }
        // v1.0.18 KEYCODE_DPAD_UP long press: 弹倍速选择 dialog
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event?.repeatCount ?: 0 > 5) {
            showSpeedDialog()
            return true
        }
        // v1.0.18 KEYCODE_DPAD_DOWN long press: 弹音效 dialog
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event?.repeatCount ?: 0 > 5) {
            showAudioDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "Player"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_EPISODE_URL = "episode_url"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SITE_KEY = "site_key"
        private const val EXTRA_SOURCE_URL = "source_url"
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_EPISODE_LIST = "episode_list"
        private const val EXTRA_EPISODE_INDEX = "episode_index"

        private const val RESUME_THRESHOLD_MS = 10_000L
        private const val END_IGNORE_THRESHOLD_MS = 30_000L

        fun intent(ctx: Context, title: String, subtitle: String?, url: String) =
            Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle ?: "")
                putExtra(EXTRA_URL, url)
            }

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

        /**
         * New: pass full episode list for auto-play next.
         */
        fun intent(
            ctx: Context, title: String, subtitle: String?,
            siteKey: String, sourceUrl: String, episodeUrl: String,
            videoId: String? = null,
            episodeList: List<Pair<String, String>>,
            episodeIndex: Int
        ) = Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SUBTITLE, subtitle ?: "")
            putExtra(EXTRA_SITE_KEY, siteKey)
            putExtra(EXTRA_SOURCE_URL, sourceUrl)
            putExtra(EXTRA_EPISODE_URL, episodeUrl)
            putExtra(EXTRA_VIDEO_ID, videoId ?: "")
            val arr = JSONArray()
            for ((name, url) in episodeList) {
                val obj = org.json.JSONObject()
                obj.put("name", name)
                obj.put("url", url)
                arr.put(obj)
            }
            putExtra(EXTRA_EPISODE_LIST, arr.toString())
            putExtra(EXTRA_EPISODE_INDEX, episodeIndex)
        }
    }
}

/**
 * Adapter for the bottom episode chip strip (v1.0.16).
 * Each item is a single episode name. Tapping jumps to that episode.
 */
internal class EpisodeAdapter(
    private var items: List<Pair<String, String>>,
    private var selectedIndex: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    fun setSelectedIndex(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_episode, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (name, _) = items[position]
        holder.name.text = name
        holder.itemView.isSelected = (position == selectedIndex)
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.item_episode_name)
    }
}

/**
 * Adapter for quality / audio mode options dialog (v1.0.16).
 */
internal class OptionAdapter(
    private val labels: List<String>,
    private var selectedIndex: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<OptionAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_option, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.label.text = labels[position]
        holder.check.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = labels.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.item_option_label)
        val check: TextView = view.findViewById(R.id.item_option_check)
    }
}