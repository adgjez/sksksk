package io.legado.app.help.ai.tool

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.delay

/**
 * AI Tool: 批量书源检测。
 *
 * 对一批书源执行搜索测试，返回每个源的通过/失败状态。
 * Agent 可用此工具诊断书源健康度，辅助用户修复或清理失效源。
 */
class CheckBookSourcesTool : AiTool {
    override val name = "check_book_sources"
    override val description = "Batch-check book sources by running a test search. " +
            "Returns pass/fail for each source with error details. " +
            "Useful for diagnosing dead sources or finding working ones."
    override val parametersSchema = """
        {"type":"object","properties":{
          "searchKey":  {"type":"string", "description":"Test keyword to search (default: '斗破苍穹')"},
          "maxSources":  {"type":"integer", "default": 10, "description":"Max sources to test"},
          "groupFilter": {"type":"string", "description":"Only test sources whose group contains this string"},
          "timeoutMs":   {"type":"integer", "default": 10000, "description":"Per-source timeout"}
        }}
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val searchKey = arguments["searchKey"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "斗破苍穹"
        val maxSources = (arguments["maxSources"] as? Number)?.toInt() ?: 10
        val groupFilter = arguments["groupFilter"]?.toString()?.trim().orEmpty()

        val allSources = appDb.bookSourceDao.getAllSources()
        val filtered = if (groupFilter.isEmpty()) allSources
                       else allSources.filter { it.bookSourceGroup?.contains(groupFilter) == true }

        if (filtered.isEmpty()) return AiToolResult("(no book sources found)")

        val toTest = filtered.take(maxSources)
        val results = mutableListOf<String>()

        for (source in toTest) {
            val status = testSource(source, searchKey)
            results.add(status)
            // Small delay to avoid hammering servers
            delay(200)
        }

        val passed = results.count { it.startsWith("[PASS]") }
        val failed = results.count { it.startsWith("[FAIL]") }
        val summary = "Checked ${toTest.size} sources: $passed passed, $failed failed.\n\n"
        return AiToolResult(summary + results.joinToString("\n"))
    }

    private suspend fun testSource(source: BookSource, searchKey: String): String {
        val name = source.bookSourceName
        val url = source.bookSourceUrl
        return runCatching {
            val searchResults = WebBook.searchBookAwait(source, searchKey, page = 1)
            if (searchResults.isNullOrEmpty()) {
                "[FAIL] $name ($url): no results"
            } else {
                "[PASS] $name ($url): ${searchResults.size} results"
            }
        }.getOrElse { e ->
            "[FAIL] $name ($url): ${e.message?.take(80)}"
        }
    }
}

/**
 * AI Tool: 查询阅读统计数据。
 *
 * 返回用户的阅读统计摘要：总阅读时长、书籍数、最近阅读记录等。
 */
class ReadStatsTool : AiTool {
    override val name = "read_stats"
    override val description = "Query reading statistics: total read time, book count, " +
            "recent reading sessions, top books by time. Helps agent understand user's reading habits."
    override val parametersSchema = """
        {"type":"object","properties":{
          "type":   {"type":"string", "enum": ["summary","recent","top_books","by_date"], "default": "summary"},
          "days":   {"type":"integer", "default": 7, "description":"For recent/by_date: how many days to look back"},
          "limit":  {"type":"integer", "default": 10, "description":"Max items to return"}
        }}
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val type = arguments["type"]?.toString() ?: "summary"
        val days = (arguments["days"] as? Number)?.toInt() ?: 7
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 10

        return when (type) {
            "summary" -> getSummary()
            "recent" -> getRecentSessions(days, limit)
            "top_books" -> getTopBooks(limit)
            "by_date" -> getByDate(days)
            else -> AiToolResult("unknown type: $type. Use: summary, recent, top_books, by_date", isError = true)
        }
    }

    private fun getSummary(): AiToolResult {
        val totalBooks = appDb.bookDao.allBookCount
        val totalSources = appDb.bookSourceDao.getAllSources().size
        val readRecordCount = appDb.readRecordDao.count
        val sessionCount = appDb.readRecordDao.getSessionsCount()
        val detailsCount = appDb.readRecordDao.getDetailsCount()

        val text = buildString {
            appendLine("=== Reading Stats Summary ===")
            appendLine("Books on shelf: $totalBooks")
            appendLine("Book sources: $totalSources")
            appendLine("Read records: $readRecordCount")
            appendLine("Read sessions: $sessionCount")
            appendLine("Read detail entries: $detailsCount")
        }
        return AiToolResult(text)
    }

    private suspend fun getRecentSessions(days: Int, limit: Int): AiToolResult {
        val sessions = appDb.readRecordDao.getAllSessionsList()
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val recent = sessions
            .filter { it.startTime >= cutoff }
            .sortedByDescending { it.startTime }
            .take(limit)

        if (recent.isEmpty()) return AiToolResult("(no sessions in last $days days)")

        val text = buildString {
            appendLine("=== Recent $days days (top $limit) ===")
            for (s in recent) {
                val durationMin = (s.endTime - s.startTime) / 60000
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(s.startTime))
                appendLine("- [$date] ${s.bookName} by ${s.bookAuthor}: ${durationMin}min, ${s.words} words")
            }
        }
        return AiToolResult(text)
    }

    private suspend fun getTopBooks(limit: Int): AiToolResult {
        val bookTimes = appDb.readRecordDao.getBookReadTimes()
        if (bookTimes.isEmpty()) return AiToolResult("(no reading data)")

        val sorted = bookTimes.sortedByDescending { it.totalReadTime }.take(limit)
        val text = buildString {
            appendLine("=== Top $limit Books by Read Time ===")
            for (b in sorted) {
                val hours = b.totalReadTime / 3600
                val mins = (b.totalReadTime % 3600) / 60
                appendLine("- ${b.bookName} by ${b.bookAuthor}: ${hours}h ${mins}min")
            }
        }
        return AiToolResult(text)
    }

    private suspend fun getByDate(days: Int): AiToolResult {
        val details = appDb.readRecordDao.getAllDetailsList()
        val cutoffDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date(System.currentTimeMillis() - days * 24L * 3600 * 1000))

        val byDate = details
            .filter { it.date >= cutoffDate }
            .groupBy { it.date }
            .mapValues { (_, list) -> list.sumOf { it.readTime } }
            .toList()
            .sortedByDescending { it.first }
            .take(days)

        if (byDate.isEmpty()) return AiToolResult("(no data for last $days days)")

        val text = buildString {
            appendLine("=== Daily Reading (last $days days) ===")
            for ((date, time) in byDate) {
                val hours = time / 3600
                val mins = (time % 3600) / 60
                appendLine("- $date: ${hours}h ${mins}min")
            }
        }
        return AiToolResult(text)
    }
}

/**
 * AI Tool: 验证书源 JSON 结构。
 *
 * 检查一个 BookSource JSON 是否包含必填字段、字段格式是否正确。
 * 不执行网络请求，只做静态验证。
 */
class ValidateBookSourceTool : AiTool {
    override val name = "validate_book_source"
    override val description = "Validate a BookSource JSON for required fields and format. " +
            "Does NOT make network requests - only checks JSON structure. " +
            "Returns field-by-field validation results."
    override val parametersSchema = """
        {"type":"object","properties":{
          "json": {"type":"string", "description":"BookSource JSON string to validate"},
          "name": {"type":"string", "description":"Optional name for identification"}
        }, "required":["json"]}
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val jsonRaw = arguments["json"]?.toString().orEmpty()
        if (jsonRaw.isEmpty()) return AiToolResult("missing 'json'", isError = true)

        val obj = runCatching { org.json.JSONObject(jsonRaw) }.getOrNull()
            ?: return AiToolResult("json is not valid JSON object", isError = true)

        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Required fields
        val requiredFields = mapOf(
            "bookSourceUrl" to "string",
            "bookSourceName" to "string",
            "bookSourceType" to "int",
        )
        for ((field, type) in requiredFields) {
            val value = obj.opt(field)
            if (value == null || value.toString().isBlank()) {
                issues.add("MISSING: $field")
            }
        }

        // bookSourceType validation
        val sourceType = obj.optInt("bookSourceType", -1)
        if (sourceType !in 0..1) {
            issues.add("INVALID: bookSourceType must be 0 (text) or 1 (audio), got $sourceType")
        }

        // Search URL check
        val searchUrl = obj.optString("searchUrl", "")
        if (searchUrl.isBlank()) {
            warnings.add("WARN: searchUrl is empty - source won't support search")
        } else {
            if (!searchUrl.contains("{{key}}") && !searchUrl.contains("\${key}")) {
                warnings.add("WARN: searchUrl has no {{key}} placeholder - search may not work")
            }
        }

        // Rule objects check
        val ruleSearch = obj.optJSONObject("ruleSearch")
        if (ruleSearch == null) {
            warnings.add("WARN: ruleSearch is missing - source won't support search")
        } else {
            checkRuleField(ruleSearch, "bookList", "ruleSearch.bookList", issues, true)
            checkRuleField(ruleSearch, "name", "ruleSearch.name", issues, true)
            checkRuleField(ruleSearch, "bookUrl", "ruleSearch.bookUrl", issues, true)
        }

        val ruleBookInfo = obj.optJSONObject("ruleBookInfo")
        if (ruleBookInfo == null) {
            warnings.add("WARN: ruleBookInfo is missing - book details won't load")
        } else {
            checkRuleField(ruleBookInfo, "name", "ruleBookInfo.name", issues, false)
            checkRuleField(ruleBookInfo, "author", "ruleBookInfo.author", issues, false)
        }

        val ruleToc = obj.optJSONObject("ruleToc")
        if (ruleToc == null) {
            warnings.add("WARN: ruleToc is missing - chapter list won't load")
        } else {
            checkRuleField(ruleToc, "chapterList", "ruleToc.chapterList", issues, true)
            checkRuleField(ruleToc, "chapterName", "ruleToc.chapterName", issues, true)
            checkRuleField(ruleToc, "chapterUrl", "ruleToc.chapterUrl", issues, true)
        }

        val ruleContent = obj.optJSONObject("ruleContent")
        if (ruleContent == null) {
            warnings.add("WARN: ruleContent is missing - chapter content won't load")
        } else {
            checkRuleField(ruleContent, "content", "ruleContent.content", issues, true)
        }

        // Header format check
        val header = obj.optString("header", "")
        if (header.isNotBlank()) {
            if (!header.contains(":")) {
                warnings.add("WARN: header doesn't look like 'Key: Value' format")
            }
        }

        val text = buildString {
            appendLine("=== Validation Result ===")
            appendLine("Source: ${obj.optString("bookSourceName", "?")} (${obj.optString("bookSourceUrl", "?")})")
            appendLine()
            if (issues.isEmpty()) {
                appendLine("[PASS] No critical issues found")
            } else {
                appendLine("[FAIL] ${issues.size} issue(s):")
                issues.forEach { appendLine("  - $it") }
            }
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("${warnings.size} warning(s):")
                warnings.forEach { appendLine("  - $it") }
            }
        }
        return AiToolResult(text, isError = issues.isNotEmpty())
    }

    private fun checkRuleField(
        obj: org.json.JSONObject,
        field: String,
        path: String,
        issues: MutableList<String>,
        required: Boolean
    ) {
        val value = obj.optString(field, "")
        if (value.isBlank()) {
            if (required) issues.add("MISSING: $path")
            else issues.add("EMPTY: $path (optional but recommended)")
        }
    }
}
