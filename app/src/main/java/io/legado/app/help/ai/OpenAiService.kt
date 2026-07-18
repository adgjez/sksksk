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
 * OpenAI Chat Completions 兼容实现。
 *
 * 兼容：OpenAI / 通义千问 / DeepSeek / 智谱 GLM / Ollama（OpenAI 模式）/ vLLM。
 * 流式接口走 SSE（text/event-stream），非流式走普通 JSON。
 *
 * 支持 tool calling（function calling）：请求里拼 tools 数组，响应里读 tool_calls。
 */
class OpenAiService : AiService {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun testConnection(provider: AiProvider): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("model", provider.model)
                    put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", "ping")
                    ))
                    put("max_tokens", 5)
                }.toString().toRequestBody(jsonMedia)

                val request = Request.Builder()
                    .url(completionsUrl(provider))
                    .header("Authorization", "Bearer ${provider.apiKey}")
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
                .url(completionsUrl(provider))
                .header("Authorization", "Bearer ${provider.apiKey}")
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
            .url(completionsUrl(provider))
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        withContext(Dispatchers.IO) {
            val accumulated = StringBuilder()
            // 整个流跑完后，从完整响应里解析 tool_calls
            // 简化：流里只取 content delta，tool 在 onClosed 时由 caller 重新发起非流式请求拿 tool_calls
            // 这里用更简单方案：所有 chunk 收集，最后 parse 整个流
            val allChunks = StringBuilder()
            EventSources.createFactory(okHttpClient)
                .newEventSource(request, object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (data == "[DONE]") return
                        allChunks.append(data).append("\n")
                        runCatching {
                            val obj = JSONObject(data)
                            val delta: String = obj.getJSONArray("choices")
                                .optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "")
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                stream.onDelta(delta, isFinal = false)
                            }
                        }
                    }
                    override fun onClosed(eventSource: EventSource) {
                        stream.onDelta("", isFinal = true)
                        // 从流中所有 chunk 找 tool_calls（最后一个 chunk 通常是完整的）
                        val toolCalls = parseToolCallsFromChunks(allChunks.toString())
                        stream.onComplete(ChatResult(content = accumulated.toString(), toolCalls = toolCalls))
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
            val body = JSONObject().apply {
                put("model", provider.model.ifBlank { "dall-e-3" })
                put("prompt", prompt)
                put("size", size)
                put("n", n)
                put("response_format", "url")
            }.toString().toRequestBody(jsonMedia)

            val request = Request.Builder()
                .url(imagesUrl(provider))
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
        val arr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        return JSONObject().apply {
            put("model", provider.model)
            put("messages", arr)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (stream) put("stream", true)
            if (tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                for (t in tools) {
                    val fn = JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", org.json.JSONObject(t.parametersSchema))
                    }
                    toolsArr.put(JSONObject().put("type", "function").put("function", fn))
                }
                put("tools", toolsArr)
            }
        }
    }

    private fun parseChatResponse(text: String): ChatResult {
        val json = JSONObject(text)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        val msg = choice.getJSONObject("message")
        val content = msg.optString("content")
        val usage = json.optJSONObject("usage")
        val toolCalls = parseToolCallsFromMessage(msg)
        return ChatResult(
            content = content,
            promptTokens = usage?.optInt("prompt_tokens") ?: 0,
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            toolCalls = toolCalls,
        )
    }

    private fun parseToolCallsFromMessage(msg: JSONObject): List<AiToolCall> {
        val arr = msg.optJSONArray("tool_calls") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val out = mutableListOf<AiToolCall>()
        for (i in 0 until arr.length()) {
            val tc = arr.getJSONObject(i)
            val id = tc.optString("id", "call_$i")
            val fn = tc.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            val argsRaw = fn.optString("arguments", "{}")
            out.add(AiToolCall(id = id, name = name, arguments = AiToolCall.fromJson(argsRaw)))
        }
        return out
    }

    private fun parseToolCallsFromChunks(chunks: String): List<AiToolCall> {
        // 找最后一个含 tool_calls 的 chunk
        for (line in chunks.lines().asReversed()) {
            val data = line.trim()
            if (!data.startsWith("{")) continue
            runCatching {
                val obj = JSONObject(data)
                val msg = obj.getJSONArray("choices").optJSONObject(0)?.optJSONObject("message")
                    ?: obj.getJSONArray("choices").optJSONObject(0)?.optJSONObject("delta")
                if (msg?.has("tool_calls") == true) {
                    return parseToolCallsFromMessage(msg)
                }
            }
        }
        return emptyList()
    }

    private fun completionsUrl(provider: AiProvider): String {
        val base = provider.baseUrl.trimEnd('/')
        return when (provider.type) {
            AiProvider.TYPE_OLLAMA -> "$base/api/chat"
            else -> "$base/chat/completions"
        }
    }

    private fun imagesUrl(provider: AiProvider): String {
        val base = provider.baseUrl.trimEnd('/')
        return "$base/images/generations"
    }
}
