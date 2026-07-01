package com.simple.tvbox.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.simple.tvbox.R
import com.simple.tvbox.ui.home.HomeFragment

/**
 * 应用主 Activity。
 * 使用 Leanback 的 BrowseSupportFragment 展示分类入口 + 设置入口。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, HomeFragment())
                .commitNow()
        }
    }
}
