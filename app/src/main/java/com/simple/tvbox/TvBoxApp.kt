package com.simple.tvbox

import android.app.Application
import com.simple.tvbox.data.SourceRepository
import com.simple.tvbox.data.WatchHistoryRepository

/**
 * 应用入口。
 * 负责在启动时准备好全局单例（仓库、HTTP 客户端等）。
 */
class TvBoxApp : Application() {

    lateinit var sourceRepository: SourceRepository
        private set

    lateinit var watchHistoryRepository: WatchHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("TvBoxApp", "onCreate start")
        instance = this
        sourceRepository = SourceRepository(applicationContext)
        watchHistoryRepository = WatchHistoryRepository(applicationContext)
        android.util.Log.i("TvBoxApp", "onCreate done")
    }

    companion object {
        @Volatile
        private var instance: TvBoxApp? = null

        fun get(): TvBoxApp =
            instance ?: error("TvBoxApp.onCreate() 还没执行")
    }
}
