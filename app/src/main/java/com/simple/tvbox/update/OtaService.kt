package com.simple.tvbox.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.simple.tvbox.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * OTA 升级服务。
 *
 * 工作流程：
 *   1. 拉取 ota.json（包含最新版本号、apkUrl、sha256、changelog、forceUpdate）
 *   2. 与 BuildConfig.VERSION_CODE 比对
 *   3. 新版本时返回 [UpdateInfo]
 *   4. 用户确认后下载 APK
 *   5. 下载完成后启动系统安装器（FileProvider）
 *
 * 重要：
 *   - OTA URL 走 https://1x1jt2.cn/app/ota-tvbox.json（爸爸的阿里云域名）
 *   - APK 下载走同一域名 /app/ 路径
 *   - sha256 校验：本地计算 vs ota.json 给的，不一致不安装
 *   - FileProvider 走 androidx.core.content.FileProvider（兼容 Android 7+）
 */
object OtaService {

    private const val TAG = "OtaService"

    /** OTA JSON 地址（固定写到代码里，未来要改可提到 SettingsPrefs） */
    const val OTA_URL = "https://1x1jt2.cn/app/ota-tvbox.json"

    /** APK 下载保存目录（在 app 私有 cache 目录，无需权限） */
    private fun apkDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "ota")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 元数据
     */
    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String,
        val changelog: List<String>,
        val forceUpdate: Boolean,
        val releaseNotes: String?,
        val releaseDate: String?
    ) {
        /** 用户的当前 versionCode >= 服务端 versionCode 时 = 无更新 */
        fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode
    }

    /**
     * 拉取并解析 ota.json。失败抛异常。
     */
    suspend fun fetchUpdateInfo(): UpdateInfo = withContext(Dispatchers.IO) {
        val text = HttpUtil.fetchText(OTA_URL)
        parse(text)
    }

    /**
     * 解析 OTA JSON。
     */
    private fun parse(text: String): UpdateInfo {
        val obj = JSONObject(text)
        val changelogArr = obj.optJSONArray("changelog")
        val changelog = if (changelogArr != null) {
            (0 until changelogArr.length()).map { changelogArr.optString(it) }
        } else emptyList()
        return UpdateInfo(
            versionName = obj.optString("versionName", ""),
            versionCode = obj.optInt("versionCode", 0),
            apkUrl = obj.optString("apkUrl", ""),
            apkSize = obj.optLong("apkSize", 0),
            sha256 = obj.optString("sha256", "").lowercase(),
            changelog = changelog,
            forceUpdate = obj.optBoolean("forceUpdate", false),
            releaseNotes = obj.optStringOrNull("releaseNotes"),
            releaseDate = obj.optStringOrNull("releaseDate")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "").trim()
        return v.ifBlank { null }
    }

    /**
     * 下载 APK（流式写文件）。回调进度（0-100）。
     *
     * @return 写入的本地文件
     */
    suspend fun downloadApk(
        ctx: Context,
        info: UpdateInfo,
        onProgress: ((percent: Int, downloaded: Long, total: Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (info.apkUrl.isBlank()) throw IllegalStateException("apkUrl 为空")

        val targetFile = File(apkDir(ctx), "tvbox-simple-v${info.versionName}-release.apk")

        // 用 OkHttp 直接拿流，便于回调进度
        val request = okhttp3.Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", HttpUtil.USER_AGENT)
            .header("Accept", "*/*")
            .build()
        HttpUtil.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} 下载 APK 失败")
            }
            val body = resp.body ?: throw IOException("空响应")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            val tmpFile = File(targetFile.parentFile, targetFile.name + ".tmp")
            FileOutputStream(tmpFile).use { out ->
                val buf = ByteArray(8 * 1024)
                var downloaded = 0L
                var lastReport = 0
                val source = body.byteStream()
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    downloaded += n
                    if (onProgress != null && total > 0) {
                        val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        if (percent - lastReport >= 1) {
                            onProgress(percent, downloaded, total)
                            lastReport = percent
                        }
                    }
                }
                out.flush()
            }
            // 校验 sha256（O(几十 MB) 内存开销可接受；如果是大文件用流式 hash）
            val hash = sha256File(tmpFile)
            if (info.sha256.isNotBlank() && hash != info.sha256.lowercase()) {
                tmpFile.delete()
                throw IOException("SHA256 校验失败 (期望 ${info.sha256}, 实际 $hash)")
            }
            // 改名
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                // rename 失败（罕见）：copy + delete
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            targetFile
        }
    }

    /**
     * 启动系统安装器。
     * Android 7+ 走 FileProvider。
     */
    fun installApk(ctx: Context, apkFile: File) {
        val authority = ctx.packageName + ".fileprovider"
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(ctx, authority, apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            // 让系统安装器能读到（针对不同来源的安装）
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        Log.i(TAG, "启动安装器: uri=$uri file=$apkFile")
        ctx.startActivity(intent)
    }

    /**
     * 检查"安装未知来源"是否已授权（Android 8+ 必须先授权才能 install）。
     *
     * @return true = 已授权 / false = 未授权（UI 层弹窗引导用户去设置）
     */
    fun canInstallUnknownApks(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return ctx.packageManager.canRequestPackageInstalls()
    }

    /**
     * 计算文件 SHA256
     */
    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}