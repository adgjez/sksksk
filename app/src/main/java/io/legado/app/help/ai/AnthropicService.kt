package io.legado.app.help.ai

import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.tool.AiTool
import io.legado.app.help.ai.tool.AiToolCall
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * Anthropic Claude Messages API 实现。
 *
 * Claude 的 API 与 OpenAI 不兼容：
 * - URL: /v1/messages
 * - Auth: x-api-key + anthropic-version header
 * - System prompt 是顶层字段，不是 message
 * - Tool calling 格式不同（content blocks 模型）
 * - 响应格式不同（content 数组）
 */
class AnthropicService : AiService {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val anthropicVersion = "2023-06-01"

    override suspend fun testConnection(provider: AiProvider): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("model", provider.model)
                    put("max_tokens", 5)
                    put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", "ping")
                    ))
                }.toString().toRequestBody(jsonMedia)

                val request = Request.Builder()
                    .url(messagesUrl(provider))
                    .header("x-api-key", provider.apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    }
                }
            }
        }

    override suspend fun chat(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<AiTool>,
        temperature: Double,
        maxTokens: Int
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildRequestBody(provider, systemPrompt, messages, tools, temperature, maxTokens, stream = false)
            val request = Request.Builder()
                .url(messagesUrl(provider))
                .header("x-api-key", provider.apiKey)
                .header("anthropic-version", anthropicVersion)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${resp.body?.string()?.take(500)}")
                }
                val text = resp.body?.string().orEmpty()
                parseChatResponse(text)
            }
        }
    }

    override suspend fun chatStream(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<AiTool>,
        stream: ChatStream,
        temperature: Double,
        maxTokens: Int
    ) {
        val body = buildRequestBody(provider, systemPrompt, messages, tools, temperature, maxTokens, stream = true)
        val request = Request.Builder()
            .url(messagesUrl(provider))
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", anthropicVersion)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        withContext(Dispatchers.IO) {
            val accumulated = StringBuilder()
            val toolCallBuffers = mutableMapOf<Int, JSONObject>()

            EventSources.createFactory(okHttpClient)
                .newEventSource(request, object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        runCatching {
                            val obj = JSONObject(data)
                            when (obj.optString("type")) {
                                "content_block_start" -> {
                                    val block = obj.optJSONObject("content_block")
                                    if (block?.optString("type") == "tool_use") {
                                        val index = obj.optInt("index")
                                        toolCallBuffers[index] = JSONObject().apply {
                                            put("id", block.optString("id"))
                                            put("name", block.optString("name"))
                                            put("input_str", "")
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = obj.optJSONObject("delta")
                                    when (delta?.optString("type")) {
                                        "text_delta" -> {
                                            val text = delta.optString("text", "")
                                            if (text.isNotEmpty()) {
                                                accumulated.append(text)
                                                stream.onDelta(text, isFinal = false)
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val index = obj.optInt("index")
                                            toolCallBuffers[index]?.let { buf ->
                                                buf.put("input_str", buf.optString("input_str") + delta.optString("partial_json", ""))
                                            }
                                        }
                                    }
                                }
                                "message_stop" -> {
                                    stream.onDelta("", isFinal = true)
                                    val toolCalls = toolCallBuffers.values.map { buf ->
                                        val argsRaw = buf.optString("input_str").ifBlank { "{}" }
                                        AiToolCall(
                                            id = buf.optString("id"),
                                            name = buf.optString("name"),
                                            arguments = AiToolCall.fromJson(argsRaw),
                                        )
                                    }
                                    stream.onComplete(ChatResult(
                                        content = accumulated.toString(),
                                        toolCalls = toolCalls,
                                    ))
                                }
                            }
                        }
                    }
                    override fun onClosed(eventSource: EventSource) {
                        // onComplete is called in message_stop
                    }
                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                        stream.onError(t ?: RuntimeException("SSE failed: ${response?.code}"))
                    }
                })
        }
    }

    override suspend fun generateImage(
        provider: AiProvider,
        prompt: String,
        size: String,
        n: Int
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // Claude 不原生支持图片生成，走 OpenAI 兼容 URL 作为 fallback
            val body = JSONObject().apply {
                put("model", "dall-e-3")
                put("prompt", prompt)
                put("size", size)
                put("n", n)
                put("response_format", "url")
            }.toString().toRequestBody(jsonMedia)

            val base = provider.baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/images/generations")
                .header("Authorization", "Bearer ${provider.apiKey}")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${resp.body?.string()?.take(500)}")
                }
                val data = JSONObject(resp.body!!.string())
                val arr = data.getJSONArray("data")
                val outDir = File(appCtx.cacheDir, "ai_images").apply { mkdirs() }
                val paths = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val url = arr.getJSONObject(i).optString("url", "")
                    if (url.isBlank()) continue
                    val file = File(outDir, "img_${System.currentTimeMillis()}_$i.png")
                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { r2 ->
                        if (r2.isSuccessful) {
                            r2.body?.byteStream()?.use { input ->
                                file.outputStream().use { input.copyTo(it) }
                            }
                            paths.add(file.absolutePath)
                        }
                    }
                }
                paths
            }
        }
    }

    // --- helpers ---

    private fun buildRequestBody(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<AiTool>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): JSONObject {
        // Claude: system is top-level, messages only contain user/assistant
        val msgArr = JSONArray()
        for (m in messages) {
            when (m.role) {
                "system" -> { /* skip, handled by systemPrompt */ }
                "tool" -> {
                    // Claude: tool results go as user message with tool_result content block
                    msgArr.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", m.toolCallId.ifBlank { "unknown" })
                            put("content", m.content)
                        }))
                    })
                }
                "assistant" -> {
                    val msgObj = JSONObject().put("role", "assistant")
                    if (m.toolCallsJson.isNotBlank()) {
                        // assistant 消息含 tool_use blocks
                        val contentArr = JSONArray()
                        if (m.content.isNotBlank()) {
                            contentArr.put(JSONObject().put("type", "text").put("text", m.content))
                        }
                        val callsArr = JSONArray(m.toolCallsJson)
                        for (i in 0 until callsArr.length()) {
                            val call = callsArr.getJSONObject(i)
                            contentArr.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", call.optString("id"))
                                put("name", call.optString("name"))
                                put("input", call.optJSONObject("arguments") ?: JSONObject())
                            })
                        }
                        msgObj.put("content", contentArr)
                    } else {
                        msgObj.put("content", m.content)
                    }
                    msgArr.put(msgObj)
                }
                else -> {
                    msgArr.put(JSONObject().put("role", m.role).put("content", m.content))
                }
            }
        }

        return JSONObject().apply {
            put("model", provider.model)
            put("messages", msgArr)
            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (stream) put("stream", true)
            if (tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                for (t in tools) {
                    toolsArr.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", JSONObject(t.parametersSchema))
                    })
                }
                put("tools", toolsArr)
            }
        }
    }

    private fun parseChatResponse(text: String): ChatResult {
        val json = JSONObject(text)
        val contentArr = json.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()

        for (i in 0 until contentArr.length()) {
            val block = contentArr.getJSONObject(i)
            when (block.optString("type")) {
                "text" -> sb.append(block.optString("text"))
                "tool_use" -> {
                    val argsRaw = block.optJSONObject("input")?.toString() ?: "{}"
                    toolCalls.add(AiToolCall(
                        id = block.optString("id"),
                        name = block.optString("name"),
                        arguments = AiToolCall.fromJson(argsRaw),
                    ))
                }
            }
        }

        val usage = json.optJSONObject("usage")
        return ChatResult(
            content = sb.toString(),
            promptTokens = usage?.optInt("input_tokens") ?: 0,
            completionTokens = usage?.optInt("output_tokens") ?: 0,
            toolCalls = toolCalls,
        )
    }

    private fun messagesUrl(provider: AiProvider): String {
        val base = provider.baseUrl.trimEnd('/')
        return "$base/v1/messages"
    }
}
