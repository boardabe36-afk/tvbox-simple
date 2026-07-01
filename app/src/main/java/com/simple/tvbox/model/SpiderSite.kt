package com.simple.tvbox.model

/**
 * 源链接解析后得到的单个视频网站（爱奇艺、腾讯视频等）。
 *
 * 字段命名兼容 TVBox 协议，参见原版 Spider API。
 */
data class SpiderSite(
    /** 站点 key，全局唯一 */
    val key: String,
    /** 显示名 */
    val name: String,
    /** 0=XML 1=JSON 2=Spider jar 3=自定义 */
    val type: Int,
    /** 站点 API 入口 */
    val api: String,
    /** 是否支持搜索 */
    val searchable: Int = 1,
    /** 是否支持筛选 */
    val filterable: Int = 0,
    /** base64 扩展参数 */
    val ext: String? = null
)

/**
 * 源订阅的整体配置（从 base64 JSON 解出来）。
 */
data class SpiderConfig(
    /** 可选：爬虫 jar 地址（type=2 时使用） */
    val spider: String? = null,
    /** 所有可用的视频站点 */
    val sites: List<SpiderSite> = emptyList(),
    /** 解析器列表（合并到播放地址） */
    val parses: List<Parser> = emptyList(),
    /** 广告规则 */
    val ads: List<String> = emptyList()
)

/**
 * 播放链接解析器（嗅探 m3u8 直链等）。
 */
data class Parser(
    val name: String,
    val url: String,
    val type: Int = 0
)
