package com.simple.tvbox.ui.home

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
import com.simple.tvbox.model.SpiderSite
import com.simple.tvbox.model.VideoCategory
import com.simple.tvbox.source.VideoClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个源的全部分类页：网格展示。
 */
class SourceDetailActivity : FragmentActivity() {

    private lateinit var container: GridLayout
    private var siteKey: String = ""
    private var sourceName: String = ""
    private var sourceUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_detail)

        siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: ""
        sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME) ?: "源"
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""
        if (siteKey.isBlank()) {
            finish()
            return
        }

        title = sourceName
        container = findViewById(R.id.source_detail_container)
        findViewById<TextView>(R.id.source_detail_subtitle)?.text = sourceUrl
        loadCategories()
    }

    private fun loadCategories() {
        val isHtml = sourceUrl.isNotBlank()
        val api = if (isHtml) "html://$sourceUrl" else sourceUrl
        val site = SpiderSite(
            key = siteKey,
            name = sourceName,
            type = 1,
            api = api
        )
        val client = VideoClientFactory.create(site)
        lifecycleScope.launch {
            try {
                val cats: List<VideoCategory> = withContext(Dispatchers.IO) {
                    if (!client.isSupported()) throw IllegalStateException("该站点类型暂不支持")
                    client.fetchHomeCategories()
                }
                renderCategories(cats)
            } catch (t: Throwable) {
                renderError("加载失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun renderError(message: String) {
        container.removeAllViews()
        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.LTGRAY)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        container.addView(tv)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun renderCategories(cats: List<VideoCategory>) {
        container.removeAllViews()
        if (cats.isEmpty()) {
            val tv = TextView(this).apply {
                text = "该源没有可用的分类"
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            }
            container.addView(tv)
            return
        }
        cats.forEach { cat ->
            val tv = TextView(this).apply {
                text = cat.name
                textSize = 22f
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_card)
                setTextColor(Color.WHITE)
                setPadding(28, 24, 28, 24)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dpToPx(220)
                    height = dpToPx(96)
                    setMargins(10, 10, 10, 10)
                }
                setOnClickListener { onCategoryClick(cat) }
            }
            container.addView(tv)
        }
    }

    private fun onCategoryClick(cat: VideoCategory) {
        startActivity(
            CategoryActivity.intent(
                this,
                siteKey = siteKey,
                categoryId = cat.id,
                categoryName = cat.name
            )
        )
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SITE_KEY = "***"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_SOURCE_URL = "source_url"

        fun intent(ctx: Context, siteKey: String, sourceName: String, sourceUrl: String) =
            Intent(ctx, SourceDetailActivity::class.java).apply {
                putExtra(EXTRA_SITE_KEY, siteKey)
                putExtra(EXTRA_SOURCE_NAME, sourceName)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
            }

        fun intent(ctx: Context, sourceKey: String, sourceName: String) =
            Intent(ctx, SourceDetailActivity::class.java).apply {
                putExtra(EXTRA_SITE_KEY, sourceKey)
                putExtra(EXTRA_SOURCE_NAME, sourceName)
            }
    }
}
