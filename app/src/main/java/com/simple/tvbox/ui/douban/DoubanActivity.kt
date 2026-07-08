package com.simple.tvbox.ui.douban

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.simple.tvbox.R
import com.simple.tvbox.source.DoubanService

/**
 * 豆瓣浏览入口。
 *
 * 用法：从首页的"豆瓣热门"快捷入口进入，或者从快捷入口菜单进入。
 *
 * 默认进入后展示电影热门 + 电视剧热门 + Top250 等多行卡片，
 * 每行点开是单条 → 自动去所有视频源聚合搜索 → 命中即播放，搜不到给豆瓣详情页。
 */
class DoubanActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tab = intent.getStringExtra(EXTRA_TAB) ?: TAB_HOME

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.main_browse_fragment,
                    DoubanFragment.newInstance(tab)
                )
                .commitNow()
        }
    }

    companion object {
        private const val EXTRA_TAB = "tab"

        /** 首页：电影热门 + 电视剧热门 + Top250 综合 */
        const val TAB_HOME = "home"
        /** 电影频道 */
        const val TAB_MOVIE = "movie"
        /** 电视剧频道 */
        const val TAB_TV = "tv"

        fun intent(ctx: Context, tab: String = TAB_HOME) =
            Intent(ctx, DoubanActivity::class.java).apply {
                putExtra(EXTRA_TAB, tab)
            }
    }
}