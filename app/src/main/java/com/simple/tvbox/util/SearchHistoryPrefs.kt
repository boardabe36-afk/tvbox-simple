package com.simple.tvbox.util

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史仓库 (v1.0.18)。
 *
 * 用 SharedPreferences 存最近 N 条搜索关键词，按时间倒序、去重、不区分大小写。
 * 不存服务器端热门词（懒加载）。
 */
class SearchHistoryPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 获取历史（最近 [MAX_HISTORY] 条），按时间倒序。
     */
    @Synchronized
    fun getAll(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * 添加一条搜索词到历史头部（如果已存在则上移到头部）。
     * @return 添加后的最新历史列表
     */
    @Synchronized
    fun add(keyword: String): List<String> {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return getAll()
        val current = getAll().toMutableList()
        // 大小写不敏感去重
        val lower = trimmed.lowercase()
        current.removeAll { it.lowercase() == lower }
        current.add(0, trimmed)
        // 限长
        val trimmed2 = current.take(MAX_HISTORY)
        save(trimmed2)
        return trimmed2
    }

    /**
     * 清空所有历史。
     */
    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * 删除单条历史。
     */
    @Synchronized
    fun remove(keyword: String) {
        val current = getAll().toMutableList()
        current.removeAll { it.equals(keyword, ignoreCase = true) }
        save(current)
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "search_history_prefs"
        private const val KEY_HISTORY = "search_history"
        private const val MAX_HISTORY = 12
    }
}
