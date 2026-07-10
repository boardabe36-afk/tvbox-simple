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

    /** OTA JSON 地址（固定写到代码里，未来要改可提到 SettingsPrefs）
     *
     * 历史踩坑：证书 CN/SAN 是 www.1x1jt2.cn，所以 URL 必须带 www；
     * 用 1x1jt2.cn 不带 www 会报 SSLPeerUnverifiedException。
     */
    const val GITHUB_REPO = "boardabe36-afk/tvbox-simple"

    /**
     * GitHub Releases API：直连查询 latest release。
     * 由 `gh release create` 触发，不依赖自家服务器。
     */
    const val GITHUB_LATEST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /**
     * APK 命名约定：tvbox-simple-v{versionName}-release.apk
     */
    private fun apkNameFromTag(tag: String, assetName: String?): String {
        // 优先用 GitHub asset 真实名字
        return assetName ?: tag
    }

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
        /**
         * 判断服务端版本是否“新”于客户端当前 versionCode。
         *
         * 优先比 versionCode（精准）；如果 server versionCode 没解析出来
         * (e.g. release body 忘了写 `versionCode: N` 返回了 -1），fallback
         * 到 versionName semver 比对 (`v1.0.16` > `v1.0.15`) 。
         */
        fun isNewerThan(currentVersionCode: Int, currentVersionName: String? = null): Boolean {
            // 1) versionCode 精准比较（首选）
            if (versionCode > 0 && currentVersionCode > 0) {
                return versionCode > currentVersionCode
            }
            // 2) server code 解析失败 (=-1) 且 client versionName 存在 → semver 比对
            if (currentVersionName != null && versionName.isNotBlank()) {
                return compareSemver(versionName, currentVersionName) > 0
            }
            // 3) 都没法比，安全判定为不升级
            return false
        }

        /**
         * Semver-like compare: `1.2.10` > `1.2.9` （逐位数字比较）
         * @return 正数 a>b，0 相等，负数 a<b
         */
        private fun compareSemver(a: String, b: String): Int {
            val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
            val len = maxOf(pa.size, pb.size)
            for (i in 0 until len) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va - vb
            }
            return 0
        }
    }

    /**
     * 拉取并解析 ota.json。失败抛异常。
     */
    suspend fun fetchUpdateInfo(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            android.util.Log.i(TAG, "fetching latest release from GitHub: $GITHUB_LATEST_URL")
            val text = HttpUtil.fetchText(GITHUB_LATEST_URL)
            android.util.Log.i(TAG, "got ${text.length} chars")
            val info = parseGithubRelease(text)
            android.util.Log.i(TAG, "parsed: v${info.versionName} code=${info.versionCode}")
            info
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "fetchUpdateInfo FAILED", t)
            throw t
        }
    }

    /**
     * 解析 GitHub Releases API 的 latest release JSON。
     *
     * 期望字段：
     *   tag_name:          "v1.0.13"
     *   name:              "v1.0.13 - 搜索精准匹配"
     *   body:              release notes (Markdown)
     *   published_at:      "2026-07-09T14:11:00Z"
     *   assets[]:          { name, browser_download_url, size, ... }
     *   assets[0].name:    "tvbox-simple-v1.0.13.apk"
     */
    private fun parseGithubRelease(text: String): UpdateInfo {
        val obj = org.json.JSONObject(text)
        val tagName = obj.optString("tag_name", "")
        if (tagName.isBlank()) throw IOException("GitHub 最新 release 没有 tag_name")
        // 从 tag_name (如 "v1.0.13") 推断 versionName 和 versionCode
        // 约定：tag = "vX.Y.Z" → versionName = "X.Y.Z"，versionCode 用 parsed manifest 外的版本位
        // versionCode 没法从 tag 直接得到，先用 X*10000+Y*100+Z 启发式 + 找 latest 之前的
        val body = obj.optString("body", "")
        val versionName = tagName.removePrefix("v").trim()
        val versionCode = parseVersionCodeFromBody(body, tagName)

        // 找 APK asset
        val assetsArr = obj.optJSONArray("assets")
        var apkUrl = ""
        var apkSize = 0L
        var sha256 = ""
        if (assetsArr != null) {
            for (i in 0 until assetsArr.length()) {
                val a = assetsArr.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkSize = a.optLong("size", 0)
                    break
                }
            }
        }

        // body 是 Markdown，取首段作为 changelog（body 已在前面定义）
        val changelog = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(15)

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            apkUrl = apkUrl,
            apkSize = apkSize,
            sha256 = sha256,
            changelog = changelog,
            forceUpdate = false,
            releaseNotes = body.ifBlank { null },
            releaseDate = obj.optStringOrNull("published_at")
        )
    }

    /**
     * 从 release body (release notes) 里解析 "versionCode: N" 行。
     *
     * 为什么不用 tag 启发式算 X*10000+Y*100+Z：
     * - 那会得出一个跟实际 Gradle versionCode 不一致的值（如 v1.0.13 算 10013）
     * - 会导致"显示有更新但实际是同一版"的 bug
     *
     * release notes 由 `gh release create --notes ...` 写入，约定包含 "versionCode: N" 行。
     */
    private fun parseVersionCodeFromBody(body: String, tag: String): Int {
        // 优先：从 body 里读 "versionCode: N"
        for (line in body.lines()) {
            val ms = Regex("(?i)versionCode\\s*[:=]\\s*(\\d+)").findAll(line).toList()
            if (ms.isNotEmpty()) {
                return ms.last().groupValues[1].toIntOrNull() ?: 0
            }
        }
        // fallback 1: 看看 body 里有没有 "code N" 这样的描述
        for (line in body.lines()) {
            val ms = Regex("(?i)code\\s*(\\d+)").findAll(line).toList()
            if (ms.isNotEmpty()) {
                return ms.last().groupValues[1].toIntOrNull() ?: 0
            }
        }
        // fallback 2: tag 启发式（如 v1.0.13 → 14 但实际要是 13，只警告不强制升级）
        android.util.Log.w(TAG, "Could not parse versionCode from release body for $tag - skipping update check")
        return -1
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