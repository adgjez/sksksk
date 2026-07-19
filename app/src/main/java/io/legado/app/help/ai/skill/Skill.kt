package io.legado.app.help.ai.skill

/**
 * Skill 元数据。
 *
 * 评估系统字段：
 * - origin: builtin（系统预设） / user（用户手写） / agent（agent 创建）
 * - status: experimental（新创建，待评估） / stable（评分 ≥ 4） / deprecated（评分 ≤ 2 且用 ≥ 3 次）
 * - useCount / ratingSum / ratingCount: 平均分 = ratingSum / ratingCount
 * - lastUsed: 毫秒时间戳
 * - lastEvaluation: 最近一次 agent 自我评估的总结
 */
data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val origin: String = ORIGIN_BUILTIN,
    val status: String = STATUS_STABLE,
    val useCount: Int = 0,
    val ratingSum: Int = 0,
    val ratingCount: Int = 0,
    val lastUsed: Long = 0L,
    val lastEvaluation: String = "",
) {
    val averageRating: Double
        get() = if (ratingCount == 0) 0.0 else ratingSum.toDouble() / ratingCount

    companion object {
        const val ORIGIN_BUILTIN = "builtin"
        const val ORIGIN_USER = "user"
        const val ORIGIN_AGENT = "agent"

        const val STATUS_EXPERIMENTAL = "experimental"
        const val STATUS_STABLE = "stable"
        const val STATUS_DEPRECATED = "deprecated"

        fun activateMemoryKey(name: String) = "active_skill_$name"

        /** 全量预置 skill。 */
        val presets: List<Skill> = listOf(
            Skill(
                name = "summarize_chapter",
                description = "总结章节：用户提供章节正文，输出 100-300 字摘要。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: summarize_chapter
                    用户给你一段书籍章节正文（可能用 read_chapter 工具拉取）。
                    你的任务：用 100-300 字中文摘要该章节关键情节、人物行为、伏笔。
                    不要复述细节；只讲推进了什么。
                """.trimIndent()
            ),
            Skill(
                name = "extract_characters",
                description = "从章节正文中抽取人物清单（名字 + 一句话简介）。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: extract_characters
                    读到章节正文后，列出所有登场人物。每人一行："名字：一句话简介"。
                    不确定就标注（存疑）。
                """.trimIndent()
            ),
            Skill(
                name = "translate_passage",
                description = "把用户给出的段落翻译成中英日韩（用户说哪种就哪种）。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: translate_passage
                    用户给一段外文，翻译成中文或反之。保留文学风格，避免机翻味。
                """.trimIndent()
            ),
            Skill(
                name = "explain_term",
                description = "解释用户提供的术语，可选调用搜索/记忆查询。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: explain_term
                    用户给一个词或概念：先查 memory 是否有相关条目；
                    没有就给一个简洁解释（2-5 句），必要时举书中的例子。
                """.trimIndent()
            ),
            Skill(
                name = "generate_book_source",
                description = "为新网站生成 Legado 兼容的 BookSource JSON。支持多页章节分析、编码自动检测、POST 搜索。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_EXPERIMENTAL,
                instructions = """
                    当前激活 skill: generate_book_source
                    你的任务是为一个新网站生成 Legado (阅读) 兼容的 BookSource JSON 源。

                    ## BookSource JSON 必填字段
                    - bookSourceUrl: string  唯一标识，例 "https://www.biquge.com/" 或 "@biquge_xyz"
                    - bookSourceName: string  显示名，例 "笔趣阁 XYZ"
                    - bookSourceType: int     0 = 文本，1 = 音频（默认 0）
                    - bookSourceGroup: string  分类，例 "小说"
                    - enabled: bool 默认 true
                    - searchUrl: string?  搜索 URL，{{key}} 是关键词占位
                    - ruleSearch: { bookList, name, author, intro, lastChapter, coverUrl, bookUrl }
                    - ruleBookInfo: { name, author, intro, coverUrl, lastChapter, tocUrl, wordCount }
                    - ruleToc: { chapterList, chapterName, chapterUrl }
                    - ruleContent: { content, title, nextContentUrl }
                    - header: string?  额外 header，例 "User-Agent: Mozilla/5.0..."

                    ## 字段值语法（简洁版）
                    - CSS 选择器: `@css:.book-list li`
                    - XPath: `@xpath://div[@class="book"]`
                    - JSONPath: `$.data.list[*]`
                    - 正则提取: `<js>var x = result.match(/title="([^"]+)"/)[1]; x</js>`
                    - 多字段: `{{baseUrl}}{{$.url}}`（变量插值）
                    - 替换规则: `源字符串||替换后` (ruleContent.replaceRegex)
                    - 关键词替换: `$1.$2` (ruleContent.replaceRegex)

                    ## fetch_html 工具增强用法
                    - extractMode: "body" (默认, 去掉 script/style/nav/footer) / "raw" (完整 HTML) / "text" (纯文本)
                    - method: 支持 POST (配合 body + bodyType=form/json/raw)
                    - headers: 可传 cookie/referer 等自定义头
                    - 自动检测 GBK/GB2312/UTF-8 编码（无需手动指定）
                    - 返回中包含 finalUrl (重定向后) 和 setCookie 信息

                    ## 工作流
                    1. 用户给：网站 URL + 书源名（中文显示名）+ 搜索示例关键词
                    2. 调 fetch_html(extractMode="body") 拉首页，观察导航/分类结构
                    3. 调 fetch_html 拉搜索结果页（注意 POST 搜索的网站）
                       - GET 搜索: searchUrl = "https://xxx.com/search?q={{key}}"
                       - POST 搜索: searchUrl = "https://xxx.com/search,{\"method\":\"POST\",\"body\":\"keyword={{key}}\"}"
                    4. 调 fetch_html 拉一本书的详情页
                       - 观察书名/作者/简介/封面的 HTML 结构
                       - 注意详情页可能和目录页同一 URL，也可能 tocUrl 不同
                    5. 调 fetch_html 拉章节列表页
                       - 识别章节列表的 selector (最常见: .chapter-list a / #list dd a / .mulu a)
                       - 注意分页：如果有"下一页"，需要 nextContentUrl 或在 chapterList 里处理
                    6. 调 fetch_html(extractMode="raw") 拉一章正文
                       - 注意正文可能在 div#content / div#content_text / .chapter-content 等
                       - 注意广告/推荐语需要用 replaceRegex 净化
                    7. 看 HTML 结构，识别 4 个规则的 selector
                    8. 输出完整 JSON 对象（不要任何解释文字）
                    9. 调 save_book_source 保存
                    10. 告诉用户文件路径 + 怎么导入

                    ## selector 识别技巧
                    - 先用 extractMode="body" 减少干扰
                    - 列表项: 找重复的 li/dd/a 结构，bookList 选父级，子规则选子元素
                    - 链接: bookUrl/chapterUrl 用 @css:a@href 或 @css:a@href||{{baseUrl}} 拼绝对路径
                    - 正文: content 规则最关键，先试 div#content, div.content, .chapter-content, #booktxt
                    - 多页章节: 如果正文分页，设置 nextContentUrl 规则
                    - 编码: fetch_html 已自动检测，但 searchUrl 里可以写 ,{"charset":"gbk"}

                    ## 常见反爬应对
                    - Cookie 验证: 先 fetch_html 首页拿 setCookie，后续请求带上
                    - Referer: 在 header 字段或 fetch_html headers 里加
                    - JS 渲染: 如果页面是 JS 动态加载，selector 无法直接抓到，在 bookSourceComment 里写明
                    - 验证码: 在 bookSourceComment 里写明，规则可以留空

                    注意：
                    - 所有 selector 必须可工作（不要瞎猜）
                    - 如果网站要登录或验证码，在 bookSourceComment 里写明，规则可以留空
                    - 如果搜不到结果，扩 maxBytes 重新拉
                    - 中文站一般用 GBK 编码，fetch_html 会自动检测
                """.trimIndent()
            ),
        )
    }
}
