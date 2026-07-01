package com.simple.tvbox.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.simple.tvbox.TvBoxApp
import com.simple.tvbox.model.Source
import com.simple.tvbox.ui.search.SearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简易局域网网页服务：扫码搜索 + 手机传文件 + 扫码设置源。
 *
 * 仅服务同一局域网访问；URL 带随机 token，避免同网段误访问。
 * 不做公网穿透，不上传到第三方。
 */
object LocalWebServer {

    private const val TAG = "LocalWebServer"
    private const val MAX_UPLOAD_BYTES = 100L * 1024L * 1024L
    private val ISO: Charset = Charsets.ISO_8859_1

    private val executor = Executors.newCachedThreadPool()
    private val started = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var token: String = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var lastUploadedFile: File? = null

    var port: Int = 0
        private set

    fun ensureStarted(context: Context): Int {
        if (started.get()) return port
        synchronized(this) {
            if (started.get()) return port
            val socket = ServerSocket(0)
            socket.reuseAddress = true
            serverSocket = socket
            port = socket.localPort
            token = UUID.randomUUID().toString().replace("-", "")
            started.set(true)
            executor.execute { acceptLoop(context.applicationContext, socket) }
            return port
        }
    }

    fun url(context: Context, path: String): String? {
        val ip = LanUtil.localIp(context) ?: return null
        val p = ensureStarted(context)
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://$ip:$p$cleanPath?token=$token"
    }

    fun lastUpload(): File? = lastUploadedFile

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (started.get()) {
            runCatching {
                val client = socket.accept()
                executor.execute { handleClient(context, client) }
            }.onFailure { Log.w(TAG, "accept failed", it) }
        }
    }

    private fun handleClient(context: Context, socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val uri = Uri.parse(target)
            if (uri.getQueryParameter("token") != token) {
                writeText(client, 403, "text/plain; charset=utf-8", "Forbidden")
                return
            }
            when {
                method == "GET" && uri.path == "/search" -> writeText(client, 200, "text/html; charset=utf-8", searchPage(uri.getQueryParameter("q").orEmpty()))
                method == "GET" && uri.path == "/upload" -> writeText(client, 200, "text/html; charset=utf-8", uploadPage())
                method == "GET" && uri.path == "/settings" -> writeText(client, 200, "text/html; charset=utf-8", settingsPage())
                method == "GET" && uri.path == "/do_search" -> {
                    val q = uri.getQueryParameter("q").orEmpty().trim()
                    if (q.isNotBlank()) {
                        SearchActivity.launchFromRemote(context, q)
                        writeText(client, 200, "text/html; charset=utf-8", donePage("已在电视上搜索：${escapeHtml(q)}"))
                    } else {
                        writeText(client, 400, "text/html; charset=utf-8", donePage("片名不能为空"))
                    }
                }
                method == "GET" && uri.path == "/add_source" -> handleAddSource(client, uri)
                method == "POST" && uri.path == "/upload" -> handleUpload(context, client, input, headers)
                else -> writeText(client, 404, "text/plain; charset=utf-8", "Not Found")
            }
        }
    }

    private fun handleAddSource(client: Socket, uri: Uri) {
        val url = uri.getQueryParameter("url").orEmpty().trim()
        val name = uri.getQueryParameter("name").orEmpty().trim()
        val kindParam = uri.getQueryParameter("kind").orEmpty().trim().lowercase()
        if (url.isBlank()) {
            writeText(client, 400, "text/html; charset=utf-8", donePage("源链接不能为空"))
            return
        }
        val kind = when (kindParam) {
            "json" -> Source.Kind.JSON
            "html" -> Source.Kind.HTML
            else -> guessSourceKind(url)
        }
        val sourceName = name.ifBlank { if (kind == Source.Kind.JSON) "扫码 JSON 源" else "扫码 HTML 源" }
        val repo = TvBoxApp.get().sourceRepository
        val message = runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val source = Source(name = sourceName, url = url, kind = kind)
                    val exists = repo.getAllSources().any { it.url == url }
                    if (exists) {
                        "该源已存在：${escapeHtml(url)}"
                    } else {
                        repo.testAndLoad(source)
                        val added = repo.addSource(source)
                        if (added) {
                            repo.invalidateCache()
                            "添加成功：${escapeHtml(sourceName)}<br>${escapeHtml(url)}<br><br>回到电视主页点“刷新”即可看到新内容。"
                        } else {
                            "添加失败：未知原因"
                        }
                    }
                }.getOrElse { "添加失败：${escapeHtml(it.message ?: it.javaClass.simpleName)}" }
            }
        }
        writeText(client, 200, "text/html; charset=utf-8", donePage(message))
    }

    private fun guessSourceKind(url: String): Source.Kind {
        val lower = url.lowercase()
        return if (lower.endsWith(".json") || lower.contains("ac=") || lower.contains("provide/") || lower.contains("/api.php")) {
            Source.Kind.JSON
        } else {
            Source.Kind.HTML
        }
    }

    private fun handleUpload(context: Context, client: Socket, input: InputStream, headers: Map<String, String>) {
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        val contentType = headers["content-type"].orEmpty()
        if (len <= 0L || len > MAX_UPLOAD_BYTES) {
            writeText(client, 413, "text/html; charset=utf-8", donePage("文件为空或超过 100MB 限制"))
            return
        }
        val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
        if (boundary.isBlank()) {
            writeText(client, 400, "text/html; charset=utf-8", donePage("上传格式不正确"))
            return
        }
        val body = readExactly(input, len.toInt())
        val boundaryBytes = "--$boundary".toByteArray(ISO)
        val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(ISO), 0)
        if (headerEnd < 0) {
            writeText(client, 400, "text/html; charset=utf-8", donePage("未找到文件内容"))
            return
        }
        val partHeaderStart = indexOf(body, boundaryBytes, 0).coerceAtLeast(0)
        val partHeader = String(body, partHeaderStart, headerEnd - partHeaderStart, ISO)
        val filename = Regex("""filename="([^"]*)"""").find(partHeader)?.groupValues?.get(1)
            ?.ifBlank { "upload.bin" }
            ?.let { safeFileName(it) }
            ?: "upload.bin"
        val contentStart = headerEnd + 4
        val nextBoundary = indexOf(body, "\r\n--$boundary".toByteArray(ISO), contentStart)
        val contentEnd = if (nextBoundary > contentStart) nextBoundary else body.size
        if (contentEnd <= contentStart) {
            writeText(client, 400, "text/html; charset=utf-8", donePage("文件内容为空"))
            return
        }
        val dir = File(context.getExternalFilesDir(null), "Uploads").apply { mkdirs() }
        val outFile = uniqueFile(dir, filename)
        outFile.outputStream().use { it.write(body, contentStart, contentEnd - contentStart) }
        lastUploadedFile = outFile
        writeText(client, 200, "text/html; charset=utf-8", donePage("上传成功：${escapeHtml(outFile.name)}<br>已保存到电视：${escapeHtml(outFile.absolutePath)}"))
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == 10) break
            if (b != 13) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val data = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(data, offset, len - offset)
            if (n < 0) break
            offset += n
        }
        return if (offset == len) data else data.copyOf(offset)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, start: Int): Int {
        if (pattern.isEmpty()) return -1
        var i = start.coerceAtLeast(0)
        while (i <= data.size - pattern.size) {
            var j = 0
            while (j < pattern.size && data[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }

    private fun writeText(socket: Socket, code: Int, contentType: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "OK" }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun searchPage(defaultQuery: String): String = page("电视搜索", """
        <h1>在电视上搜索</h1>
        <form method="get" action="/do_search">
          <input type="hidden" name="token" value="$token" />
          <input name="q" value="${escapeHtml(defaultQuery)}" placeholder="输入片名" autofocus />
          <button type="submit">发送到电视搜索</button>
        </form>
        <p>手机和电视需要连接同一局域网。</p>
    """.trimIndent())

    private fun uploadPage(): String = page("传文件到电视", """
        <h1>传文件到电视</h1>
        <form method="post" enctype="multipart/form-data" action="/upload?token=$token">
          <input type="file" name="file" required />
          <button type="submit">上传到电视</button>
        </form>
        <p>单个文件最大 100MB；文件保存在 App 专属 Uploads 目录。</p>
    """.trimIndent())

    private fun settingsPage(): String = page("扫码设置视频源", """
        <h1>扫码设置视频源</h1>
        <form method="get" action="/add_source">
          <input type="hidden" name="token" value="$token" />
          <label>源名称（可选）</label>
          <input name="name" placeholder="例如：我的 JSON 源 / 某某影视站" />
          <label>网址或 JSON 配置链接</label>
          <input name="url" placeholder="https://example.com/tvbox.json 或影视站首页" required autofocus />
          <label>源类型</label>
          <select name="kind">
            <option value="auto">自动识别</option>
            <option value="json">JSON 配置源</option>
            <option value="html">HTML 影视站</option>
          </select>
          <button type="submit">同步到电视</button>
        </form>
        <p>提交后电视会测试链接，通过后写入本机源列表。手机和电视必须在同一局域网。</p>
    """.trimIndent())

    private fun donePage(message: String): String = page("完成", "<h1>$message</h1><p>可以关闭此页面，回到电视查看。</p>")

    private fun page(title: String, body: String): String = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>${escapeHtml(title)}</title>
        <style>
          body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0f1014;color:#eee;padding:24px;line-height:1.6}
          h1{font-size:26px} label{display:block;color:#bbb;margin-top:14px}
          input,select{box-sizing:border-box;width:100%;font-size:20px;padding:14px;margin:8px 0 12px;border-radius:10px;border:1px solid #444;background:#1a1b22;color:#fff}
          button{width:100%;font-size:20px;padding:14px;margin-top:10px;border:0;border-radius:10px;background:#4f8ef7;color:white} p{color:#aaa}
        </style></head><body>$body</body></html>
    """.trimIndent()

    private fun safeFileName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\r\\n\\t]"), "_")
        .take(120)
        .ifBlank { "upload.bin" }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isBlank() || it == name) "" else ".$it" }
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base-$i$ext")
            i++
        }
        return f
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
