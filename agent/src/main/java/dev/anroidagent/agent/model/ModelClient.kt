package dev.anroidagent.agent.model

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val role: String,
    val content: String,
    /** 视觉兜底时携带的 JPEG 字节；非空时 content 走多模态数组形式 */
    val images: List<ByteArray> = emptyList(),
) {
    companion object {
        fun system(s: String) = ChatMessage("system", s)
        fun user(s: String) = ChatMessage("user", s)
        fun assistant(s: String) = ChatMessage("assistant", s)
    }
}

data class Usage(val promptTokens: Int, val completionTokens: Int) {
    val total: Int get() = promptTokens + completionTokens
}

data class Completion(val text: String, val usage: Usage?)

data class ModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float = 0.1f,
    val maxTokens: Int = 400,
    /** 让服务端强制返回 JSON 对象（OpenAI 兼容的 response_format） */
    val jsonObjectMode: Boolean = true,
    val timeoutMs: Long = 30_000L,
) {
    val chatUrl: String
        get() = baseUrl.trimEnd('/') + "/chat/completions"

    val isValid: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()
}

interface ModelClient {
    suspend fun complete(messages: List<ChatMessage>): Completion
}

class ModelException(message: String, val code: Int? = null) : Exception(message)

/**
 * OpenAI 兼容客户端。
 *
 * 用 OkHttp 同步调用 + [Dispatchers.IO]：比回调链好调试，且天然可取消
 * （协程取消时 OkHttp call 会被 interrupt）。
 *
 * 约束解码说明：多数 OpenAI 兼容服务只保证 `response_format=json_object`
 * 返回**合法 JSON**，不保证符合某个 schema。所以：
 * - 本地用 [ActionDecoder] 做结构与枚举校验（真正的不变式在这里）
 * - 摘要/重试兜底见 [Planner]
 */
class OpenAiCompatibleClient(
    private val config: ModelConfig,
    private val http: OkHttpClient = defaultClient(config.timeoutMs),
    private val maxAttempts: Int = 3,
) : ModelClient {

    override suspend fun complete(messages: List<ChatMessage>): Completion =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null

            for (attempt in 1..maxAttempts) {
                try {
                    return@withContext callOnce(messages)
                } catch (e: ModelException) {
                    lastError = e
                    val retryable = e.code == null || e.code == 429 || e.code >= 500
                    if (!retryable || attempt == maxAttempts) throw e
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == maxAttempts) {
                        throw ModelException("network: ${e.message}")
                    }
                }
                delay(400L * attempt)
            }
            throw ModelException("exhausted: ${lastError?.message}")
        }

    private fun callOnce(messages: List<ChatMessage>): Completion {
        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", config.temperature.toDouble())
            put("max_tokens", config.maxTokens)
            put("stream", false)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("content", contentOf(m))
                    })
                }
            })
            if (config.jsonObjectMode) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        val request = Request.Builder()
            .url(config.chatUrl)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ModelException("http ${resp.code}: ${bodyText.take(300)}", resp.code)
            }
            if (bodyText.isBlank()) throw ModelException("empty body")

            val json = runCatching { JSONObject(bodyText) }.getOrNull()
                ?: throw ModelException("bad json: ${bodyText.take(200)}")

            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw ModelException("no content: ${bodyText.take(200)}")

            val usage = json.optJSONObject("usage")?.let {
                Usage(
                    promptTokens = it.optInt("prompt_tokens", 0),
                    completionTokens = it.optInt("completion_tokens", 0),
                )
            }

            return Completion(content, usage)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 纯文本时用字符串（所有兼容服务都支持）；
         * 带图时用 OpenAI 的多模态数组形式，图片以 data URL 内联。
         */
        private fun contentOf(m: ChatMessage): Any {
            if (m.images.isEmpty()) return m.content

            return JSONArray().apply {
                put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", m.content),
                )
                m.images.forEach { bytes ->
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$b64"),
                            ),
                    )
                }
            }
        }

        fun defaultClient(timeoutMs: Long): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
