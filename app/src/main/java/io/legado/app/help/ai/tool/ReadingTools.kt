package io.legado.app.help.ai.tool

import io.legado.app.data.appDb
import io.legado.app.help.ai.memory.AiMemoryEntry
import io.legado.app.help.ai.memory.AiMemoryStore
import io.legado.app.help.book.BookHelp
import java.util.UUID

/**
 * 写记忆：把一条重要信息存到记忆库。Agent 可以在对话中自动调用以"自我进化"。
 *
 * 参数：
 * - key: 简短标识，便于以后 recall
 * - value: 具体内容
 * - scope: "global" / "book" / "session"
 * - bookKey: 当 scope=book 时填当前书 key
 */
class WriteMemoryTool(
    private val store: AiMemoryStore,
) : AiTool {
    override val name = "write_memory"
    override val description = "保存一条重要信息到长期记忆。下次相关对话会自动出现。"
    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "key":    { "type": "string",  "description": "简短标签，例如 user_prefers_no_spoilers" },
            "value":  { "type": "string",  "description": "要记住的内容" },
            "scope":  { "type": "string",  "enum": ["global","book","session"], "default": "global" },
            "bookKey":{ "type": "string",  "description": "scope=book 时填入当前书标识" }
          },
          "required": ["key","value"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val key = arguments["key"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return AiToolResult("missing 'key'", isError = true)
        val value = arguments["value"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return AiToolResult("missing 'value'", isError = true)
        val scope = arguments["scope"]?.toString() ?: "global"
        val bookKey = arguments["bookKey"]?.toString().orEmpty()
        val entry = AiMemoryEntry(
            id = UUID.randomUUID().toString(),
            key = key,
            value = value,
            scope = scope,
            bookKey = bookKey,
            importance = 50,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        store.put(entry)
        return AiToolResult("saved memory: ${entry.key}")
    }
}

/**
 * 读记忆：按 key 精确查询。返回匹配的若干条。
 */
class ReadMemoryTool(
    private val store: AiMemoryStore,
) : AiTool {
    override val name = "read_memory"
    override val description = "从长期记忆中按 key 查找匹配的条目。"
    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "查找关键词，会做前缀匹配" },
            "bookKey": { "type": "string", "description": "可选，限定当前书范围" }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val query = arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return AiToolResult("missing 'query'", isError = true)
        val bookKey = arguments["bookKey"]?.toString().orEmpty()
        val hits = store.search(query, bookKey, limit = 5)
        if (hits.isEmpty()) return AiToolResult("(no matching memory)")
        val text = hits.joinToString("\n") { "- [${it.scope}] ${it.key}: ${it.value}" }
        return AiToolResult(text)
    }
}

/** 在用户书架搜索书籍（书名/作者/简介匹配）。 */
class SearchBooksTool : AiTool {
    override val name = "search_books"
    override val description = "在用户书架搜索书籍，返回匹配的书名、作者和 bookUrl。"
    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "keyword": { "type": "string", "description": "搜索关键词（书名/作者/简介）" },
            "limit":   { "type": "integer", "default": 5 }
          },
          "required": ["keyword"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val keyword = arguments["keyword"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return AiToolResult("missing 'keyword'", isError = true)
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 5
        val books = appDb.bookDao.all
        val kw = keyword.lowercase()
        val matched = books.filter { b ->
            b.name.lowercase().contains(kw) ||
            b.author.lowercase().contains(kw) ||
            b.introduce.orEmpty().lowercase().contains(kw)
        }.take(limit)
        if (matched.isEmpty()) return AiToolResult("(no books found for '$keyword')")
        val text = matched.joinToString("\n") { b ->
            "- ${b.name} (作者: ${b.author}) url=${b.bookUrl}"
        }
        return AiToolResult("找到 ${matched.size} 本:\n$text")
    }
}

/** 读取指定书 URL + 章节索引的正文内容。 */
class ReadChapterTool : AiTool {
    override val name = "read_chapter"
    override val description = "读取指定书 URL + 章节索引的正文内容（从本地缓存）。"
    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "bookUrl":      { "type": "string", "description": "书的 bookUrl（可通过 search_books 获取）" },
            "chapterIndex": { "type": "integer", "description": "章节索引（从0开始，可通过 list_chapters 获取）" },
            "maxChars":     { "type": "integer", "default": 4000, "description": "截断到多少字符" }
          },
          "required": ["bookUrl","chapterIndex"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult = runCatching {
        val bookUrl = arguments["bookUrl"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return AiToolResult("missing 'bookUrl'", isError = true)
        val chapterIndex = (arguments["chapterIndex"] as? Number)?.toInt()
            ?: return AiToolResult("missing 'chapterIndex'", isError = true)
        val maxChars = (arguments["maxChars"] as? Number)?.toInt() ?: 4000
        val chapter = appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)
            ?: return AiToolResult("chapter not found (bookUrl=$bookUrl, index=$chapterIndex)", isError = true)
        val book = appDb.bookDao.getBook(bookUrl)
            ?: return AiToolResult("book not found: $bookUrl", isError = true)
        val text = BookHelp.getContent(book, chapter)?.take(maxChars)
            ?: return AiToolResult("content not in local cache yet (bookUrl=$bookUrl, index=$chapterIndex)", isError = true)
        AiToolResult("title=${chapter.title}\n\n$text")
    }.getOrElse { AiToolResult("error: ${it.message}", isError = true) }
}
