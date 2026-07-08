package com.simple.tvbox.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.simple.tvbox.R
import com.simple.tvbox.TvBoxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OTA 升级流程的 UI 封装。
 *
 * 用法：
 *   val updater = OtaUpdater(this, this)  // activity + lifecycle owner
 *   updater.checkForUpdate()              // 用户点"检查更新"时调用
 *
 * 内部状态机：
 *   IDLE → CHECKING → NO_UPDATE / HAS_UPDATE / NETWORK_ERROR
 *   HAS_UPDATE → [用户点立即更新] → DOWNLOADING → VERIFYING → INSTALL / FAILED
 */
class OtaUpdater(
    private val activity: android.app.Activity,
    private val lifecycleOwner: LifecycleOwner
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val app get() = activity.applicationContext

    // 进度回调引用（用户点了"立即更新"后绑定）
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var dialog: AlertDialog? = null

    /**
     * 检查更新入口。
     */
    fun checkForUpdate() {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        lifecycleOwner.lifecycleScope.launch {
            try {
                val info = OtaService.fetchUpdateInfo()
                val currentCode = currentVersionCode()
                if (info.isNewerThan(currentCode)) {
                    showUpdateDialog(info)
                } else {
                    showNoUpdateDialog(info.versionName, currentCode)
                }
            } catch (t: Throwable) {
                android.util.Log.e("OtaUpdater", "checkForUpdate failed", t)
                Toast.makeText(
                    activity,
                    "检查更新失败: ${t.message ?: t.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 直接走"有更新"的流程（设置页点了"立即更新"按钮时）
     */
    fun checkAndUpdate(progressBar: ProgressBar?, statusText: TextView?) {
        this.progressBar = progressBar
        this.statusText = statusText
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        statusText?.text = "正在检查更新…"
        statusText?.visibility = View.VISIBLE
        lifecycleOwner.lifecycleScope.launch {
            try {
                val info = OtaService.fetchUpdateInfo()
                val currentCode = currentVersionCode()
                if (info.isNewerThan(currentCode)) {
                    startDownload(info)
                } else {
                    statusText?.text = "已是最新版本 (v${info.versionName})"
                    progressBar?.visibility = View.GONE
                    Toast.makeText(activity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                statusText?.text = "检查失败: ${t.message}"
                progressBar?.visibility = View.GONE
                android.util.Log.e("OtaUpdater", "checkAndUpdate failed", t)
            }
        }
    }

    /**
     * "有新版本" dialog：带 changelog，"立即更新" / "稍后再说"
     */
    private fun showUpdateDialog(info: OtaService.UpdateInfo) {
        val msg = buildString {
            append("发现新版本 v${info.versionName}\n\n")
            append("更新内容：\n")
            info.changelog.take(8).forEach { line ->
                append("• ").append(line).append("\n")
            }
            if (info.changelog.size > 8) append("… 更多请查看 release notes\n")
            append("\nAPK 大小: ").append(formatSize(info.apkSize))
        }
        AlertDialog.Builder(activity)
            .setTitle("有新版本可用")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ ->
                startDownload(info)
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(!info.forceUpdate)
            .show()
    }

    /**
     * "已是最新" dialog
     */
    private fun showNoUpdateDialog(remoteVersion: String, currentCode: Int) {
        AlertDialog.Builder(activity)
            .setTitle("已是最新版本")
            .setMessage("当前 v${currentVersionName()} (code $currentCode)\n服务器最新: v$remoteVersion")
            .setPositiveButton("好的", null)
            .show()
    }

    /**
     * 启动下载：先检查"安装未知来源"权限（Android 8+），再起下载 progress dialog
     */
    private fun startDownload(info: OtaService.UpdateInfo) {
        if (!OtaService.canInstallUnknownApks(activity)) {
            // 引导用户去设置授权
            AlertDialog.Builder(activity)
                .setTitle("需要授权")
                .setMessage("OTA 升级需要先授权「安装未知来源应用」，点击前往设置。")
                .setPositiveButton("前往设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { activity.startActivity(intent) }
                        .onFailure {
                            Toast.makeText(
                                activity,
                                "请在系统设置中允许本应用安装未知应用",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
                .setNegativeButton("取消", null)
                .show()
            // 清理进度 UI
            progressBar?.visibility = View.GONE
            statusText?.text = ""
            return
        }
        showDownloadDialog(info)
    }

    /**
     * 下载 progress dialog（带 cancel）
     */
    private fun showDownloadDialog(info: OtaService.UpdateInfo) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_ota_progress, null, false)
        val title = view.findViewById<TextView>(R.id.ota_progress_title)
        val sub = view.findViewById<TextView>(R.id.ota_progress_sub)
        val bar = view.findViewById<ProgressBar>(R.id.ota_progress_bar)
        val detail = view.findViewById<TextView>(R.id.ota_progress_detail)
        title.text = "正在下载 v${info.versionName}"
        sub.text = "${formatSize(info.apkSize)} · SHA256 校验中…"

        val cancelBtn = view.findViewById<Button>(R.id.ota_progress_cancel)
        var cancelled = false
        cancelBtn.setOnClickListener {
            cancelled = true
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        // 如果设置页有自己的进度 UI，同步更新
        val settingsBar = progressBar
        val settingsStatus = statusText
        settingsBar?.visibility = View.VISIBLE
        settingsStatus?.visibility = View.VISIBLE

        lifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    OtaService.downloadApk(
                        app,
                        info,
                        onProgress = { percent, downloaded, total ->
                            mainHandler.post {
                                bar.progress = percent
                                detail.text = "$percent%  (${formatSize(downloaded)} / ${formatSize(total)})"
                                settingsBar?.progress = percent
                                settingsStatus?.text = "下载中: $percent%"
                            }
                        }
                    )
                }
                if (cancelled) {
                    file.delete()
                    return@launch
                }
                // 下载 + 校验完成
                sub.text = "下载完成，SHA256 校验通过"
                detail.text = "准备安装…"
                settingsStatus?.text = "下载完成，准备安装"
                // 启动系统安装器
                OtaService.installApk(activity, file)
                // 关闭下载 dialog
                mainHandler.postDelayed({ dialog?.dismiss() }, 500)
            } catch (t: Throwable) {
                android.util.Log.e("OtaUpdater", "download failed", t)
                sub.text = "下载失败"
                detail.text = t.message ?: t.javaClass.simpleName
                settingsStatus?.text = "下载失败: ${t.message}"
                cancelBtn.text = "关闭"
                cancelBtn.setOnClickListener { dialog?.dismiss() }
            }
        }
        dialog?.show()
    }

    private fun currentVersionCode(): Int =
        app.packageManager.getPackageInfo(app.packageName, 0).versionCode

    private fun currentVersionName(): String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "$bytes B"
        }
    }
}