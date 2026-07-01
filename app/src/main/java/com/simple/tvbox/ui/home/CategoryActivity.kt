package com.simple.tvbox.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.simple.tvbox.R

/** 视频分类列表页（点击分类进入） */
class CategoryActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: return finish()
        val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME) ?: "分类"

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.main_browse_fragment,
                    CategoryFragment.newInstance(siteKey, categoryId, categoryName)
                )
                .commitNow()
        }
    }

    companion object {
        private const val EXTRA_SITE_KEY = "site_key"
        private const val EXTRA_CATEGORY_ID = "category_id"
        private const val EXTRA_CATEGORY_NAME = "category_name"

        fun intent(ctx: Context, siteKey: String, categoryId: String?, categoryName: String) =
            Intent(ctx, CategoryActivity::class.java).apply {
                putExtra(EXTRA_SITE_KEY, siteKey)
                putExtra(EXTRA_CATEGORY_ID, categoryId)
                putExtra(EXTRA_CATEGORY_NAME, categoryName)
            }
    }
}
