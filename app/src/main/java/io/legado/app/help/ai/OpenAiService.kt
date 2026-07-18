package io.legado.app.help.ai

import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
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
        temperature: Double,
        maxTokens: Int
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildRequestBody(provider, systemPrompt, messages, temperature, maxTokens, stream = false)
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
                val json = JSONObject(text)
                val choice = json.getJSONArray("choices").getJSONObject(0)
                val msg = choice.getJSONObject("message")
                val content = msg.optString("content")
                val usage = json.optJSONObject("usage")
                ChatResult(
                    content = content,
                    promptTokens = usage?.optInt("prompt_tokens") ?: 0,
                    completionTokens = usage?.optInt("completion_tokens") ?: 0,
                )
            }
        }
    }

    override suspend fun chatStream(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        stream: ChatStream,
        temperature: Double,
        maxTokens: Int
    ) {
        val body = buildRequestBody(provider, systemPrompt, messages, temperature, maxTokens, stream = true)
        val request = Request.Builder()
            .url(completionsUrl(provider))
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        withContext(Dispatchers.IO) {
            val accumulated = StringBuilder()
            EventSources.createFactory(okHttpClient)
                .newEventSource(request, object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (data == "[DONE]") return
                        runCatching {
                            val obj = JSONObject(data)
                            val delta = obj.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("delta")
                                .optString("content", "")
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                stream.onDelta(delta, isFinal = false)
                            }
                        }
                    }
                    override fun onClosed(eventSource: EventSource) {
                        stream.onDelta("", isFinal = true)
                        stream.onComplete(ChatResult(content = accumulated.toString()))
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

    private fun buildRequestBody(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
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
        }
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
