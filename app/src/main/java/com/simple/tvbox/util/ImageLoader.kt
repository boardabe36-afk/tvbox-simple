package com.simple.tvbox.util

import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** 简单海报加载器，避免为首页热门海报引入 Glide/Coil。 */
object ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 80
    })

    fun load(url: String?, target: ImageView) {
        target.tag = url
        if (url.isNullOrBlank()) {
            target.setImageDrawable(ColorDrawable(0xFF262A34.toInt()))
            return
        }
        cache[url]?.let {
            target.setImageBitmap(it)
            return
        }
        target.setImageDrawable(ColorDrawable(0xFF262A34.toInt()))
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url) }
            if (bitmap != null) cache[url] = bitmap
            if (target.tag == url && bitmap != null) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetch(rawUrl: String): android.graphics.Bitmap? {
        return runCatching {
            val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 TVBoxSimple")
                setRequestProperty("Referer", rawUrl.substringBeforeLast('/', rawUrl))
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
