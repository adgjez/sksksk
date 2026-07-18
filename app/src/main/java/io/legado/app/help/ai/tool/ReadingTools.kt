package io.legado.app.help.ai.tool

import io.legado.app.help.ai.memory.AiMemoryEntry
import io.legado.app.help.ai.memory.AiMemoryStore
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

/** 列出所有书源 / 触发搜索。 */
class SearchBooksTool : AiTool {
    override val name = "search_books"
    override val description = "在用户的书源里搜索书籍，返回匹配的书名和作者。"
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
        // 实际搜索由 Agent 上下文提供；这里返回占位
        return AiToolResult("(search_books requires caller context; keyword=$keyword, limit=$limit)")
    }
}

/** 读指定书指定章节的正文。 */
class ReadChapterTool : AiTool {
    override val name = "read_chapter"
    override val description = "读取指定书 URL + 章节索引的正文内容（可能很长）。"
    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "bookUrl":      { "type": "string" },
            "chapterIndex": { "type": "integer" },
            "maxChars":     { "type": "integer", "default": 4000, "description": "截断到多少字符" }
          },
          "required": ["bookUrl","chapterIndex"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val bookUrl = arguments["bookUrl"]?.toString().orEmpty()
        val chapterIndex = (arguments["chapterIndex"] as? Number)?.toInt()
            ?: return AiToolResult("missing 'chapterIndex'", isError = true)
        return AiToolResult("(read_chapter needs caller context; bookUrl=$bookUrl, idx=$chapterIndex)")
    }
}
