package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.ai.tool.CharacterAssignment
import io.legado.app.help.ai.tool.ParagraphSplitter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.msg

/**
 * 角色朗读：拿章节正文，让 AI 标每段的角色+音色，返回结构化结果。
 *
 * 用法：
 * ```
 * val result = CharacterTtsService.assign(book, chapterIndex, provider)
 * if (result.isSuccess) result.getOrThrow().forEach { it.voice }
 * ```
 *
 * 复用 base 的 HttpTTS 即可播放每段。
 */
object CharacterTtsService {

    data class AssignmentResult(
        val bookName: String,
        val chapterTitle: String,
        val assignments: List<CharacterAssignment>,
    )

    /**
     * 让 AI 标每段的角色+音色。内部用 Agent：
     * 1) read_chapter 工具读章节（已在 AiReadingAssistantActivity 注册）
     * 2) system prompt 强制 AI 返回 [{"paragraph":int,"character":str,"voice":str,"text":str}] JSON
     * 3) 解析 JSON 返回
     */
    suspend fun assign(
        book: Book,
        chapterIndex: Int,
        provider: AiProvider,
        maxParagraphs: Int = 30,
    ): Result<AssignmentResult> = runCatching {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            ?: error("chapter not found")
        val content = BookHelp.getContent(book, chapter)
            ?: error("chapter content not in cache; open it in reader first")
        val paragraphs = ParagraphSplitter.split(content).take(maxParagraphs)
        require(paragraphs.isNotEmpty()) { "no paragraphs to analyze" }

        val sys = """
            你是《${book.name}》的有声书导演。
            用户给你一组章节段落（数组），你的任务是：
            1) 判断每段是旁白还是某角色对白（用角色原名；不确定就标"旁白"）
            2) 为每段推荐一个合适的 TTS 音色。优先从这组里选：
               - 青年女: zh-CN-XiaoxiaoNeural / zh-CN-XiaomengNeural
               - 青年男: zh-CN-YunyangNeural / zh-CN-YunjianNeural
               - 老年男: zh-CN-DongchenNeural
               - 少女: zh-CN-XiaoyouNeural
               - 老奶奶: zh-CN-XiaozeNeural
            3) 严格只返回 JSON 数组，不要任何其他文字。
               格式: [{"paragraph":<0-based index>,"character":"<名>","voice":"<音色>","text":"<段首20字>"}]
        """.trimIndent()

        val messages = listOf(
            io.legado.app.data.entities.AiMessage(
                id = "user-1",
                conversationId = "tts_assign",
                role = io.legado.app.data.entities.AiMessage.ROLE_USER,
                content = buildString {
                    appendLine("请分析以下 ${paragraphs.size} 段：")
                    paragraphs.forEachIndexed { i, p ->
                        appendLine("[$i] $p")
                    }
                }
            )
        )

        val service: AiService = OpenAiService()
        val result = service.chat(provider, sys, messages).getOrThrow()
        val assignments = CharacterAssignment.parse(result.content)
        check(assignments.isNotEmpty()) { "AI returned no valid JSON; raw: ${result.content.take(200)}" }
        AssignmentResult(
            bookName = book.name,
            chapterTitle = chapter.title,
            assignments = assignments,
        )
    }
}
