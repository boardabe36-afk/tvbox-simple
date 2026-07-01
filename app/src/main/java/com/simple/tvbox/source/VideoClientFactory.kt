package com.simple.tvbox.source

import com.simple.tvbox.model.SpiderSite

/**
 * 根据 [SpiderSite.api] 字段选择合适的 [VideoClient] 实现。
 *
 * 约定：
 * - api 以 `html://` 开头 → [GenericHtmlClient]
 * - 其他 → [SpiderClient]（TVBox JSON 协议）
 */
object VideoClientFactory {

    private const val HTML_PREFIX = "html://"

    fun create(site: SpiderSite): VideoClient {
        return if (site.api.startsWith(HTML_PREFIX)) {
            GenericHtmlClient(site.api.removePrefix(HTML_PREFIX))
        } else {
            SpiderClient(site)
        }
    }
}
