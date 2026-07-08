package com.simple.tvbox.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.Source
import com.simple.tvbox.util.SettingsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频源设置页。
 *
 * 两种源类型：
 * - JSON：TVBox 标准协议源 URL
 * - HTML：传统 PHP 模板影视站根 URL（实验性）
 *
 * 用户通过"添加 JSON / HTML 源"两个按钮分别添加，列表里看得到类型标签。
 */
class SettingsActivity : FragmentActivity() {

    private lateinit var listView: RecyclerView
    private lateinit var adapter: SourceAdapter
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.title_sources)

        listView = findViewById(R.id.settings_list)
        nameInput = findViewById(R.id.settings_name_input)
        urlInput = findViewById(R.id.settings_url_input)
        statusText = findViewById(R.id.settings_status)

        adapter = SourceAdapter(onDelete = { src ->
            TvBoxApp.get().sourceRepository.removeSource(src)
            Toast.makeText(this, R.string.settings_source_deleted, Toast.LENGTH_SHORT).show()
            refresh()
        })
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<Button>(R.id.settings_add_json_btn).setOnClickListener {
            addSource(Source.Kind.JSON)
        }
        findViewById<Button>(R.id.settings_add_html_btn).setOnClickListener {
            addSource(Source.Kind.HTML)
        }
        findViewById<Button>(R.id.settings_test_btn).setOnClickListener { testSource() }

        // 豆瓣开关
        val doubanToggle = findViewById<Button>(R.id.settings_douban_toggle)
        fun renderDoubanToggle() {
            val enabled = SettingsPrefs.isDoubanEnabled(this)
            doubanToggle.text = if (enabled) "关闭" else "开启"
        }
        renderDoubanToggle()
        doubanToggle.setOnClickListener {
            val newVal = !SettingsPrefs.isDoubanEnabled(this)
            SettingsPrefs.setDoubanEnabled(this, newVal)
            renderDoubanToggle()
            Toast.makeText(
                this,
                if (newVal) "豆瓣热门已开启" else "豆瓣热门已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }
        // 整行也可点击切换（TV 端友好）
        findViewById<android.widget.LinearLayout>(R.id.settings_douban_section)
            .setOnClickListener { doubanToggle.performClick() }

        // 关键 UX：键盘"完成/Done"键直接保存（按完键盘不用再去找按钮）
        urlInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            if (isDone) {
                addSource(Source.Kind.HTML)  // 默认按 HTML 源保存
                true
            } else false
        }
        nameInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            if (isDone) {
                // 焦点切到 URL 框，让用户继续输 URL
                urlInput.requestFocus()
                true
            } else false
        }

        refresh()
    }

    private fun refresh() {
        val sources = TvBoxApp.get().sourceRepository.getAllSources()
        adapter.submit(sources)
        statusText.text = when {
            sources.isEmpty() -> getString(R.string.settings_empty)
            else -> "已配置 ${sources.size} 个源（JSON: ${sources.count { it.kind == Source.Kind.JSON }}, HTML: ${sources.count { it.kind == Source.Kind.HTML }}）"
        }
    }

    private fun addSource(kind: Source.Kind) {
        android.util.Log.i("Settings", "addSource called kind=$kind")
        try {
            val app = TvBoxApp.get()
            val name = nameInput.text.toString().trim().ifBlank {
                if (kind == Source.Kind.HTML) "HTML 网站" else "未命名源"
            }
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "请填写视频源链接", Toast.LENGTH_SHORT).show()
                urlInput.requestFocus()
                return
            }
            val existing = app.sourceRepository.getAllSources().any { it.url == url }
            if (existing) {
                android.util.Log.w("Settings", "duplicate url=$url")
                // 已存在：给"删除再添加"的明确提示，不静默失败
                Toast.makeText(this, "该 URL 已存在（请先在列表里删除旧的）", Toast.LENGTH_LONG).show()
                refresh()
                listView.scrollToPosition(0)
                return
            }
            val added = app.sourceRepository.addSource(Source(name = name, url = url, kind = kind))
            android.util.Log.i("Settings", "addSource result=$added name=$name url=$url")
            if (added) {
                nameInput.setText("")
                urlInput.setText("")
                Toast.makeText(this, "已添加：$name（回到主页查看）", Toast.LENGTH_SHORT).show()
                refresh()
                listView.scrollToPosition(0)
                // 保存成功直接返回主页（让用户看到主页的"视频源"行已经显示）
                listView.postDelayed({ finish() }, 600)
            } else {
                Toast.makeText(this, "添加失败：未知原因", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            android.util.Log.e("Settings", "addSource error", t)
            Toast.makeText(this, "异常: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun testSource() {
        val url = urlInput.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "请填写视频源链接", Toast.LENGTH_SHORT).show()
            return
        }
        // 测试时按 URL 启发式猜测类型
        val kind = if (url.endsWith(".json", ignoreCase = true) ||
            url.contains("ac=", ignoreCase = true) ||
            url.contains("provide/", ignoreCase = true)) {
            Source.Kind.JSON
        } else {
            Source.Kind.HTML
        }
        statusText.text = getString(R.string.loading) + "（按${kind}源测试）"
        val repo = TvBoxApp.get().sourceRepository
        lifecycleScope.launch {
            try {
                val site = withContext(Dispatchers.IO) {
                    repo.testAndLoad(Source(name = "test", url = url, kind = kind))
                }
                statusText.text = if (kind == Source.Kind.HTML) {
                    "${getString(R.string.settings_test_ok)} · 模板识别成功，站点 ${site.name}"
                } else {
                    "${getString(R.string.settings_test_ok)} · 站点 ${site.name}"
                }
            } catch (t: Throwable) {
                statusText.text = getString(R.string.settings_test_fail, t.message ?: "")
            }
        }
    }
}

/** RecyclerView 适配器 */
class SourceAdapter(
    private val onDelete: (Source) -> Unit
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    private val items = mutableListOf<Source>()

    fun submit(list: List<Source>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val src = items[position]
        holder.name.text = src.name
        holder.url.text = src.url
        holder.kind.text = when (src.kind) {
            Source.Kind.JSON -> "JSON"
            Source.Kind.HTML -> "HTML"
        }
        holder.del.setOnClickListener { onDelete(src) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.item_source_name)
        val url: TextView = view.findViewById(R.id.item_source_url)
        val kind: TextView = view.findViewById(R.id.item_source_kind)
        val del: Button = view.findViewById(R.id.item_source_delete)
    }
}
