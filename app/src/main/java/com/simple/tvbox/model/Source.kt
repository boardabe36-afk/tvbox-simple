package com.simple.tvbox.model

/**
 * 用户在设置中添加的一个"视频源"。
 *
 * - [Kind.JSON]：TVBox 标准源 URL，HTTP 拉取后返回 base64/明文 JSON
 * - [Kind.HTML]：传统 PHP 模板影视站的根 URL（icaiqi / 一起看 / 555yy 这类），
 *                App 端启发式识别模板后爬取
 */
data class Source(
    val name: String,
    val url: String,
    val kind: Kind = Kind.JSON,
    val addedAt: Long = System.currentTimeMillis()
) {

    enum class Kind {
        /** TVBox JSON 协议源 */
        JSON,

        /** 传统 HTML 影视站（实验性） */
        HTML
    }
}
