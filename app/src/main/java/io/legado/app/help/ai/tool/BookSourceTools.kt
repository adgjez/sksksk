package io.legado.app.help.ai.tool

import io.legado.app.utils.LogUtils
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * 把生成的 BookSource JSON 保存到 app 私有目录。
 *
 * 输出位置：filesDir/ai_book_sources/{name}.json
 * 用户可从菜单"导入网络书源"或 base 提供的"本地导入"流程读这个文件。
 */
class SaveBookSourceTool : AiTool {
    override val name = "save_book_source"
    override val description = "保存书源 JSON 到 app 私有目录。返回绝对路径 + 简短摘要。" +
            "name 必须是 snake_case。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "name":        {"type":"string", "description":"snake_case 书源名, 例 biquge_xyz"},
          "json":        {"type":"string", "description":"完整 BookSource JSON 对象"},
          "overwrite":   {"type":"boolean", "default": false}
        }, "required":["name","json"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val name = arguments["name"]?.toString()?.trim().orEmpty()
        val jsonRaw = arguments["json"]?.toString().orEmpty()
        val overwrite = arguments["overwrite"] as? Boolean ?: false
        if (name.isEmpty() || jsonRaw.isEmpty()) {
            return AiToolResult("missing name/json", isError = true)
        }
        if (!name.matches(Regex("^[a-z0-9_]{2,40}$"))) {
            return AiToolResult("name must be snake_case (a-z0-9_), got: $name", isError = true)
        }
        // 校验是合法 JSON
        val obj = runCatching { JSONObject(jsonRaw) }.getOrNull()
            ?: return AiToolResult("json is not valid JSON object", isError = true)
        val outDir = File(appCtx.filesDir, "ai_book_sources").apply { mkdirs() }
        val outFile = File(outDir, "$name.json")
        if (outFile.exists() && !overwrite) {
            return AiToolResult("file exists; pass overwrite=true: ${outFile.absolutePath}", isError = true)
        }
        // 必填字段校验
        val url = obj.optString("bookSourceUrl", "")
        val name0 = obj.optString("bookSourceName", "")
        if (url.isBlank() || name0.isBlank()) {
            return AiToolResult("JSON must have bookSourceUrl + bookSourceName", isError = true)
        }
        return runCatching {
            outFile.writeText(obj.toString(2))
            LogUtils.d("SaveBookSource", "saved ${outFile.absolutePath} (${outFile.length()}B)")
            AiToolResult(
                "saved: ${outFile.absolutePath}\n" +
                "name=$name0, url=$url, file=${outFile.length()}B\n" +
                "可在 base 的书源管理 → 本地导入 选这个文件。"
            )
        }.getOrElse { AiToolResult("write error: ${it.message}", isError = true) }
    }
}

/** 列出已保存的书源 JSON（agent 调试 / 用户用）。 */
class ListSavedBookSourcesTool : AiTool {
    override val name = "list_saved_book_sources"
    override val description = "列出 app 私有目录里所有已保存的书源 JSON（含名称、bookSourceUrl、文件大小）。"
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val outDir = File(appCtx.filesDir, "ai_book_sources")
        if (!outDir.exists()) return AiToolResult("(no saved sources yet)")
        val files = outDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        if (files.isEmpty()) return AiToolResult("(no saved sources yet)")
        val lines = files.sortedBy { it.name }.map { f ->
            val obj = runCatching { JSONObject(f.readText()) }.getOrNull()
            val nm = obj?.optString("bookSourceName", f.nameWithoutExtension) ?: f.nameWithoutExtension
            val url = obj?.optString("bookSourceUrl", "?") ?: "?"
            "- $nm (${f.name}, ${f.length()}B, $url)"
        }
        return AiToolResult(lines.joinToString("\n"))
    }
}
