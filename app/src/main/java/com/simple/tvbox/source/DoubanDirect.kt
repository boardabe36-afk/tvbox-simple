package com.simple.tvbox.source

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 豆瓣直接抓取（用 java.net.HttpURLConnection，不依赖 OkHttp）
 *
 * 背景：发现 OkHttp 4.12.0 的 ResponseBody.byteStream() 在某些情况下拿到的字节流
 * 已经被 charset 转码过了，导致 UTF-8 中文被错误按 ISO-8859-1 解码再被 UTF-8 读取时变成乱码。
 *
 * 临时方案：直接用 HttpURLConnection + readBytes() 拿真正的 raw 字节。
 *
 * 实际生产环境是否真的需要这个 fallback 待验证（先把这个跑通）。
 */
object DoubanDirect {

    private const val TAG = "DoubanDirect"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * 抓豆瓣搜索 API
     */
    fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://movie.douban.com/")
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $url")
            }
            val rawBytes = conn.inputStream.readAllBytes()
            return String(rawBytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}