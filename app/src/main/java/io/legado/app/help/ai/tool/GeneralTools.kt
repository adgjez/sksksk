package io.legado.app.help.ai.tool

import io.legado.app.data.appDb
import io.legado.app.help.ai.memory.AiMemoryEntry
import io.legado.app.help.ai.memory.AiMemoryStore
import io.legado.app.utils.LogUtils
import okhttp3.Request
import io.legado.app.help.http.okHttpClient
import org.json.JSONObject
import java.util.UUID

/**
 * 给 agent 用的"读"工具。
 */

/** 列某本书的所有章节（标题 + index）。 */
class ListChaptersTool : AiTool {
    override val name = "list_chapters"
    override val description = "列出指定 bookUrl 的所有章节（标题 + 0-based index）。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "bookUrl": {"type":"string"},
          "search":  {"type":"string", "description":"可选，按标题模糊匹配"}
        }, "required":["bookUrl"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val bookUrl = arguments["bookUrl"]?.toString().orEmpty()
        val search = arguments["search"]?.toString()?.lowercase()?.trim().orEmpty()
        if (bookUrl.isEmpty()) return AiToolResult("missing 'bookUrl'", isError = true)
        val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
        val filtered = if (search.isBlank()) chapters
                       else chapters.filter { it.title.lowercase().contains(search) }
        if (filtered.isEmpty()) return AiToolResult("(no chapters)")
        val total = filtered.size
        val truncated = filtered.take(50)
        val text = truncated.joinToString("\n") { "[${it.index}] ${it.title}" }
        return if (total > 50) {
            AiToolResult("$text\n\n(showing 50 of $total chapters. Use search to filter.)")
        } else {
            AiToolResult(text)
        }
    }
}

/** 在书里搜文字（命中章节 + 命中位置上下文）。 */
class SearchInBookTool : AiTool {
    override val name = "search_in_book"
    override val description = "在当前所有书的章节正文里搜一段文字，返回书名 / 章节 / 命中片段。" +
            "第一次会扫所有书，正文需已在本地缓存。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "keyword": {"type":"string"},
          "bookUrl": {"type":"string", "description":"可选，限定单本书"},
          "maxHits": {"type":"integer", "default": 5}
        }, "required":["keyword"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val keyword = arguments["keyword"]?.toString().orEmpty()
        val bookUrl = arguments["bookUrl"]?.toString()?.trim()
        val maxHits = (arguments["maxHits"] as? Number)?.toInt() ?: 5
        if (keyword.isEmpty()) return AiToolResult("missing 'keyword'", isError = true)

        val books = if (bookUrl != null && bookUrl.isNotEmpty()) {
            listOf(bookUrl)
        } else {
            appDb.bookDao.all.map { it.bookUrl }
        }
        val hits = mutableListOf<String>()
        var booksScanned = 0
        val maxBooksScan = 20  // 防止扫描过多书籍导致超时
        for (url in books) {
            if (booksScanned >= maxBooksScan) break
            booksScanned++
            val chapters = appDb.bookChapterDao.getChapterList(url)
            for (c in chapters) {
                if (hits.size >= maxHits) break
                val text = io.legado.app.help.book.BookHelp.getContent(
                    appDb.bookDao.getBook(url) ?: continue, c
                ) ?: continue
                val idx = text.indexOf(keyword, ignoreCase = true)
                if (idx >= 0) {
                    val ctx = text.substring(maxOf(0, idx - 30), minOf(text.length, idx + 100))
                    hits.add("[$url#${c.index}] ${c.title}\n  ...$ctx...")
                }
            }
            if (hits.size >= maxHits) break
        }
        return if (hits.isEmpty()) {
            AiToolResult("(no hits${if (booksScanned >= maxBooksScan) " (scanned first $maxBooksScan books)" else ""})")
        } else {
            AiToolResult(hits.joinToString("\n\n"))
        }
    }
}

/** 写笔记（自动加 note: 前缀的 memory 写入）。 */
class AddNoteTool(
    private val memory: AiMemoryStore,
) : AiTool {
    override val name = "add_note"
    override val description = "保存一条笔记到长期记忆。会自动加 note_ 前缀，避免和其他 key 冲突。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "title":  {"type":"string", "description":"笔记标题，会作为 key 后缀"},
          "content":{"type":"string"}
        }, "required":["title","content"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val title = arguments["title"]?.toString()?.trim().orEmpty()
        val content = arguments["content"]?.toString().orEmpty()
        if (title.isEmpty() || content.isEmpty()) {
            return AiToolResult("missing title/content", isError = true)
        }
        val key = "note_${title.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(30)}"
        memory.put(AiMemoryEntry(
            id = UUID.randomUUID().toString(),
            key = key, value = content, scope = "global", importance = 40,
        ))
        return AiToolResult("noted: $key")
    }
}

/** 用 DuckDuckGo 搜（不需 API key，返回结果标题+摘要）。 */
class SearchWebTool : AiTool {
    override val name = "search_web"
    override val description = "用 DuckDuckGo 搜（不需要 API key）。返回最多 5 条结果" +
            "（标题 + URL + 摘要）。适合查词、查新闻、查技术问题。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "query": {"type":"string"},
          "maxResults": {"type":"integer", "default": 5}
        }, "required":["query"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult = runCatching {
        val q = arguments["query"]?.toString()?.trim().orEmpty()
        val max = (arguments["maxResults"] as? Number)?.toInt() ?: 5
        if (q.isEmpty()) return@runCatching AiToolResult("missing 'query'", isError = true)

        val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Legado-AI-Reader)")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching AiToolResult("HTTP ${resp.code}", isError = true)
            val html = resp.body?.string().orEmpty()
            // 极简解析：抓 <a class="result__a" href="...">title</a> 和 .result__snippet
            val titleRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""")
            val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>([^<]+)</a>""")
            val titles = titleRegex.findAll(html).take(max).toList()
            val snippets = snippetRegex.findAll(html).take(max).map { it.groupValues[1] }.toList()
            if (titles.isEmpty()) return@runCatching AiToolResult("(no results)")
            val text = titles.mapIndexed { i, m ->
                val url = m.groupValues[1]
                val title = m.groupValues[2].replace("&amp;", "&").trim()
                val snip = snippets.getOrNull(i)?.trim() ?: ""
                "${i + 1}. $title\n   $url\n   $snip"
            }.joinToString("\n\n")
            AiToolResult(text)
        }
    }.getOrElse { AiToolResult("search_web error: ${it.message}", isError = true) }
}
