package com.simple.tvbox.ui.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.simple.tvbox.R
import com.simple.tvbox.util.LanUtil
import com.simple.tvbox.util.LocalWebServer

/** 二维码入口：手机扫码后在同一局域网内访问电视上的临时网页。 */
class QrActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SEARCH
        val title = when (mode) {
            MODE_UPLOAD -> "扫码传文件"
            MODE_SETTINGS -> "扫码设置"
            else -> "扫码搜索"
        }
        val desc = when (mode) {
            MODE_UPLOAD -> "手机扫码后选择文件，直接传到电视 App 专属目录。"
            MODE_SETTINGS -> "手机扫码后输入网址或 JSON 配置链接，直接同步到电视源列表。"
            else -> "手机扫码后输入片名，电视会自动打开搜索结果。"
        }
        findViewById<TextView>(R.id.qr_title).text = title
        findViewById<TextView>(R.id.qr_desc).text = desc

        val path = when (mode) {
            MODE_UPLOAD -> "/upload"
            MODE_SETTINGS -> "/settings"
            else -> "/search"
        }
        val url = LocalWebServer.url(this, path)
        if (url.isNullOrBlank()) {
            findViewById<TextView>(R.id.qr_status).text = "无法获取局域网 IP，请确认电视已连接 WiFi/网线。"
            Toast.makeText(this, "无法获取局域网 IP", Toast.LENGTH_LONG).show()
            return
        }
        findViewById<ImageView>(R.id.qr_image).setImageBitmap(LanUtil.qrBitmap(url, 512))
        findViewById<TextView>(R.id.qr_url).text = url
        findViewById<TextView>(R.id.qr_status).text = "请让手机和电视连接同一局域网；此入口仅在当前 App 进程内有效。"
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        const val MODE_SEARCH = "search"
        const val MODE_UPLOAD = "upload"
        const val MODE_SETTINGS = "settings"

        fun intent(ctx: Context, mode: String): Intent = Intent(ctx, QrActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode)
        }
    }
}
