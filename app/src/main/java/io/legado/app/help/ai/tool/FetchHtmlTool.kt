package io.legado.app.help.ai.tool

import io.legado.app.help.http.okHttpClient
import okhttp3.Request

/**
 * 拉 URL HTML 给 agent 分析（书源生成 skill 用）。
 *
 * 返回内容会截断到 [maxBytes]（默认 50000 = 50KB），避免把整个网页塞进
 * AI context。多个 URL 可分多次拉。
 */
class FetchHtmlTool : AiTool {
    override val name = "fetch_html"
    override val description = "用 GET 拉取 URL 的 HTML。返回截断到 maxBytes 字节的内容（UTF-8 文本）。" +
            "User-Agent 默认是普通浏览器，书源生成用。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "url":      {"type":"string"},
          "maxBytes": {"type":"integer", "default": 50000},
          "userAgent":{"type":"string", "description":"可选，自定义 UA"}
        }, "required":["url"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult = runCatching {
        val url = arguments["url"]?.toString()?.trim().orEmpty()
        val maxBytes = (arguments["maxBytes"] as? Number)?.toInt() ?: 50000
        val ua = arguments["userAgent"]?.toString()
            ?: "Mozilla/5.0 (Linux; Android 14) Legado-AI-Reader"
        if (url.isEmpty()) return@runCatching AiToolResult("missing 'url'", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@runCatching AiToolResult("url must start with http(s)://", isError = true)
        }
        val request = Request.Builder().url(url).header("User-Agent", ua).build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@runCatching AiToolResult("HTTP ${resp.code}", isError = true)
            }
            val body = resp.body ?: return@runCatching AiToolResult("empty body", isError = true)
            // 流式读 maxBytes
            val bytes = ByteArray(maxBytes)
            val read = body.byteStream().use { it.read(bytes) }
            val text = String(bytes, 0, read, Charsets.UTF_8)
            AiToolResult("[fetched ${read}B from $url]\n\n$text")
        }
    }.getOrElse { AiToolResult("fetch_html error: ${it.message}", isError = true) }
}
