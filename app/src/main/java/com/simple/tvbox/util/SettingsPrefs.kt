package com.simple.tvbox.util

import android.content.Context

/**
 * 全局开关/偏好设置（与"视频源"无关的简单 KV 配置）。
 *
 * - doubanEnabled：是否在首页展示豆瓣行 / 聚合搜索（默认开启）
 *
 * 持久化在 tvbox_simple_prefs（与 SourceRepository 共享 prefs 文件）。
 */
object SettingsPrefs {

    private const val PREFS_NAME = "tvbox_simple_prefs"
    private const val KEY_DOUBAN_ENABLED = "douban_enabled"

    /** 默认开启（避免新装用户感觉"没有豆瓣入口"） */
    const val DEFAULT_DOUBAN_ENABLED = true

    fun isDoubanEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOUBAN_ENABLED, DEFAULT_DOUBAN_ENABLED)
    }

    fun setDoubanEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DOUBAN_ENABLED, enabled)
            .apply()
    }
}