package io.legado.app.help.ai.tool

import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Enhanced fetch_html tool for AI agent.
 *
 * Improvements over original:
 * - Auto charset detection (GBK/GB2312/UTF-8) via Content-Type header + meta tags
 * - POST method support (form / json body)
 * - Custom headers (cookie, referer, etc.)
 * - Response metadata (final URL after redirects, content-type, encoding)
 * - Selective content extraction (body only, strip script/style/nav)
 * - Configurable timeout
 */
class FetchHtmlTool : AiTool {
    override val name = "fetch_html"
    override val description = "Fetch URL HTML for agent analysis. Supports GET/POST, custom headers, " +
            "auto charset detection (GBK/UTF-8), and content extraction modes. " +
            "Returns truncated text with response metadata."
    override val parametersSchema = """
        {"type":"object","properties":{
          "url":         {"type":"string", "description":"Full http(s) URL"},
          "method":      {"type":"string", "enum": ["GET","POST"], "default": "GET"},
          "maxBytes":    {"type":"integer", "default": 50000, "description":"Truncate body to this many bytes"},
          "userAgent":   {"type":"string", "description":"Custom User-Agent. Default: Chrome desktop UA"},
          "headers":     {"type":"object", "description":"Extra headers as key-value pairs (cookie, referer, etc.)"},
          "body":        {"type":"string", "description":"POST body (raw text). Used when method=POST"},
          "bodyType":    {"type":"string", "enum": ["form","json","raw"], "default": "form", "description":"POST body encoding type"},
          "extractMode": {"type":"string", "enum": ["raw","body","text"], "default": "body", "description":"raw=full HTML, body=extract <body> only, text=strip all tags"},
          "timeoutMs":   {"type":"integer", "default": 15000, "description":"Request timeout in milliseconds"}
        }, "required":["url"]}
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult = runCatching {
        val url = arguments["url"]?.toString()?.trim().orEmpty()
        val method = arguments["method"]?.toString()?.uppercase() ?: "GET"
        val maxBytes = (arguments["maxBytes"] as? Number)?.toInt() ?: 50000
        val ua = arguments["userAgent"]?.toString()
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        val extractMode = arguments["extractMode"]?.toString() ?: "body"
        val timeoutMs = (arguments["timeoutMs"] as? Number)?.toInt() ?: 15000

        if (url.isEmpty()) return@runCatching AiToolResult("missing 'url'", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@runCatching AiToolResult("url must start with http(s)://", isError = true)
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        // Custom headers
        @Suppress("UNCHECKED_CAST")
        val extraHeaders = arguments["headers"] as? Map<String, Any?>
        extraHeaders?.forEach { (k, v) ->
            if (k.isNotBlank() && v != null) builder.header(k, v.toString())
        }

        // POST body
        if (method == "POST") {
            val body = arguments["body"]?.toString().orEmpty()
            val bodyType = arguments["bodyType"]?.toString() ?: "form"
            val contentType = when (bodyType) {
                "json" -> "application/json; charset=utf-8"
                "raw" -> "text/plain; charset=utf-8"
                else -> "application/x-www-form-urlencoded; charset=utf-8"
            }
            builder.post(body.toRequestBody(contentType.toMediaTypeOrNull()))
        }

        // Build a per-request client with the specified timeout
        val client = okHttpClient.newBuilder()
            .callTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@runCatching AiToolResult(
                    "HTTP ${resp.code} ${resp.message}\nfinalUrl=${resp.request.url}",
                    isError = true
                )
            }
            val body = resp.body
                ?: return@runCatching AiToolResult("empty body", isError = true)

            // Read raw bytes (up to maxBytes)
            val bytes = ByteArray(maxBytes)
            val read = body.byteStream().use { it.read(bytes) }
            if (read <= 0) return@runCatching AiToolResult("empty body (0 bytes)", isError = true)

            // Detect charset: 1) Content-Type header, 2) meta tag in HTML, 3) fallback UTF-8
            val contentTypeHeader = body.contentType()?.toString().orEmpty()
            var charset = extractCharsetFromHeader(contentTypeHeader)
            val rawText = String(bytes, 0, read, Charsets.ISO_8859_1) // safe for byte-level scan
            if (charset == null) {
                charset = extractCharsetFromMeta(rawText) ?: "UTF-8"
            }
            val fullText = String(bytes, 0, read, charset(charset))

            // Content extraction
            val extracted = when (extractMode) {
                "raw" -> fullText
                "text" -> stripTags(fullText)
                else -> extractBody(fullText)
            }

            val metadata = buildString {
                append("[fetched ${read}B from $url]\n")
                append("[charset=$charset, contentType=$contentTypeHeader]\n")
                append("[finalUrl=${resp.request.url}]\n")
                val setCookie = resp.headers("Set-Cookie")
                if (setCookie.isNotEmpty()) {
                    append("[setCookie=${setCookie.joinToString("; ") { it.substringBefore(";") }}]\n")
                }
            }

            AiToolResult("$metadata\n$extracted")
        }
    }.getOrElse { AiToolResult("fetch_html error: ${it.message}", isError = true) }

    companion object {
        private fun extractCharsetFromHeader(contentType: String): String? {
            val regex = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
            return regex.find(contentType)?.groupValues?.get(1)
        }

        private fun extractCharsetFromMeta(html: String): String? {
            // Look for <meta charset="gbk"> or <meta http-equiv="Content-Type" content="...charset=gbk">
            val metaRegex = Regex(
                """<meta[^>]+charset\s*=\s*["']?([\w-]+)""",
                RegexOption.IGNORE_CASE
            )
            return metaRegex.find(html)?.groupValues?.get(1)
        }

        private fun extractBody(html: String): String {
            // Extract <body>...</body> content, strip script/style/nav/footer
            val bodyRegex = Regex("""<body[^>]*>(.*?)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val bodyContent = bodyRegex.find(html)?.groupValues?.get(1) ?: html
            return bodyContent
                .replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""<nav[^>]*>.*?</nav>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""<footer[^>]*>.*?</footer>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""\s{2,}"""), "\n")
                .trim()
        }

        private fun stripTags(html: String): String {
            return html
                .replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""<[^>]+>"""), "")
                .replace(Regex("""&nbsp;"""), " ")
                .replace(Regex("""&amp;"""), "&")
                .replace(Regex("""&lt;"""), "<")
                .replace(Regex("""&gt;"""), ">")
                .replace(Regex("""&quot;"""), "\"")
                .replace(Regex("""&#39;"""), "'")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }

        private fun charset(name: String): java.nio.charset.Charset {
            return runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
    }
}
