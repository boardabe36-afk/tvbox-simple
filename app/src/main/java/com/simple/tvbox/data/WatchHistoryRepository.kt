package com.simple.tvbox.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simple.tvbox.model.WatchHistoryItem
import java.security.MessageDigest

/**
 * 观看历史仓库。
 *
 * - 用 SharedPreferences 保存，避免为简版 App 引入 Room/SQLite。
 * - key = sha256(siteKey + sourceUrl + episodeUrl)，同一集重复播放会覆盖断点。
 * - 自动裁剪最多 [MAX_HISTORY] 条，避免历史无限增长。
 */
class WatchHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun getAll(): List<WatchHistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val type = object : TypeToken<List<WatchHistoryItem>>() {}.type
        return runCatching { gson.fromJson<List<WatchHistoryItem>>(raw, type) }
            .getOrNull()
            .orEmpty()
            .sortedByDescending { it.updatedAt }
    }

    fun find(siteKey: String, sourceUrl: String, episodeUrl: String): WatchHistoryItem? =
        getAll().firstOrNull { it.key == buildKey(siteKey, sourceUrl, episodeUrl) }

    @Synchronized
    fun upsert(item: WatchHistoryItem) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.key == item.key }
        if (idx >= 0) list[idx] = item else list.add(item)
        save(list)
    }

    @Synchronized
    fun remove(key: String) {
        save(getAll().filterNot { it.key == key })
    }

    fun buildKey(siteKey: String, sourceUrl: String, episodeUrl: String): String =
        sha256("$siteKey\n$sourceUrl\n$episodeUrl")

    private fun save(items: List<WatchHistoryItem>) {
        val compact = items
            .sortedByDescending { it.updatedAt }
            .take(MAX_HISTORY)
        prefs.edit()
            .putString(KEY_ITEMS, gson.toJson(compact))
            .apply()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "watch_history_prefs"
        private const val KEY_ITEMS = "watch_history_items"
        private const val MAX_HISTORY = 200
    }
}
