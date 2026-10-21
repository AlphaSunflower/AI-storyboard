package com.moon.storyboard.data

import com.moon.storyboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, override val message: String) : Exception(message)

class ApiClient(private val session: SessionStore) {

    private val baseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        // 后端大量可空字段（error/model/prompt 等）在 JSON 里是 null；
        // 有默认值的非空属性直接按默认值处理，避免一条 null 让整页解析失败。
        coerceInputValues = true
    }

    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(session, refreshClient, json, baseUrl))
        .build()

    suspend inline fun <reified T> get(path: String): T = decode(raw("GET", path, null))

    suspend inline fun <reified T> post(path: String, body: String? = null): T =
        decode(raw("POST", path, body))

    @PublishedApi
    internal suspend fun raw(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val url = if (path.startsWith("http")) path else baseUrl + "/api" + path
            val requestBody = when {
                body != null -> body.toRequestBody(JSON_MEDIA)
                method == "GET" || method == "DELETE" -> null
                else -> ByteArray(0).toRequestBody(null)
            }
            val request = Request.Builder()
                .url(url)
                .method(method, requestBody)
                .apply {
                    if (body != null) header("Content-Type", "application/json")
                    header("Accept", "application/json")
                }
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, errorMessage(text, response.code))
                }
                text
            }
        }

    @PublishedApi
    internal inline fun <reified T> decode(text: String): T {
        val envelope = try {
            json.decodeFromString<ApiEnvelope<T>>(text)
        } catch (e: Exception) {
            throw ApiException(500, "响应解析失败：${e.message}")
        }
        return envelope.data ?: throw ApiException(envelope.code, envelope.message.ifBlank { "响应为空" })
    }

    private fun errorMessage(text: String, code: Int): String = try {
        json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "请求失败（HTTP $code）"
    } catch (_: Exception) {
        "请求失败（HTTP $code）"
    }

    /**
     * 跑一轮 AgentScope turn，逐段回调正文。
     *
     * 协议：`POST /api/agent/conversations/{id}/turns/stream`，每个 SSE `data:` 是
     * `{turnId, seq, type, payload}`；正文在 `agent_event[TEXT_BLOCK_DELTA].data.delta`，
     * 终态正文在 `turn_completed.payload.content`。
     */
    fun streamTurn(
        conversationId: String,
        content: String,
        picUrls: List<String> = emptyList(),
        onDelta: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val payload = json.encodeToString(
            SendTurnRequest(
                content = content,
                clientRequestId = "android-${System.currentTimeMillis()}",
                picUrls = picUrls,
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl/api/agent/conversations/$conversationId/turns/stream")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    onError(errorMessage(text, response.code))
                    return
                }
                val source = response.body?.source() ?: run {
                    onError("连接已断开")
                    return
                }
                val accumulated = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val node = try {
                        json.parseToJsonElement(data).jsonObject
                    } catch (_: Exception) {
                        continue
                    }
                    when (node["type"]?.jsonPrimitive?.contentOrNull) {
                        "agent_event" -> {
                            val event = node["payload"]?.jsonObject ?: continue
                            if (event["eventType"]?.jsonPrimitive?.contentOrNull != "TEXT_BLOCK_DELTA") continue
                            val delta = event["data"]?.jsonObject?.get("delta")?.jsonPrimitive?.contentOrNull
                            if (!delta.isNullOrEmpty()) {
                                accumulated.append(delta)
                                onDelta(accumulated.toString())
                            }
                        }
                        "turn_completed" -> {
                            val finalContent = node["payload"]?.jsonObject
                                ?.get("content")?.jsonPrimitive?.contentOrNull
                            onComplete(finalContent?.takeIf { it.isNotBlank() } ?: accumulated.toString())
                            return
                        }
                        "turn_paused" -> {
                            // MVP：暂停先按当前正文收尾，HITL 卡片后续单独做
                            onComplete(accumulated.toString())
                            return
                        }
                        "turn_failed" -> {
                            val message = node["payload"]?.jsonObject
                                ?.get("message")?.jsonPrimitive?.contentOrNull
                            onError(message ?: "生成失败")
                            return
                        }
                    }
                }
                onComplete(accumulated.toString())
            }
        } catch (e: IOException) {
            onError(e.message ?: "网络连接失败")
        } catch (e: Exception) {
            onError(e.message ?: "生成失败")
        }
    }

    /** 上传参考图到 `/api/agent/upload`，返回可直接放进 picUrls 的相对 URL。 */
    suspend fun uploadImage(bytes: ByteArray, fileName: String, mime: String): UploadResult =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$baseUrl/api/agent/upload")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, errorMessage(text, response.code))
                }
                decode<UploadResult>(text)
            }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 附加 Bearer；遇到 401 时用 refreshToken 换一次新 token 并重放请求。
 * 刷新失败（401/403）才清登录态，网络异常保持现状，避免地铁里一断网就被登出。
 */
private class AuthInterceptor(
    private val session: SessionStore,
    private val refreshClient: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = session.accessToken
        val original = chain.request()
        val authed = original.newBuilder()
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        val response = chain.proceed(authed)
        if (response.code != 401) return response

        val path = original.url.encodedPath
        if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh") || path.endsWith("/auth/register")) {
            return response
        }

        val newToken = synchronized(REFRESH_LOCK) {
            val current = session.accessToken
            if (!current.isNullOrBlank() && current != token) current else refresh()
        }
        if (newToken.isNullOrBlank()) return response

        response.close()
        return chain.proceed(
            original.newBuilder().header("Authorization", "Bearer $newToken").build(),
        )
    }

    private fun refresh(): String? {
        val refreshToken = session.refreshToken ?: return null
        val body = json.encodeToString(RefreshRequest(refreshToken))
        val request = Request.Builder()
            .url("$baseUrl/api/auth/refresh")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    session.clear()
                    return null
                }
                if (!response.isSuccessful) return null
                val text = response.body?.string().orEmpty()
                val envelope = json.decodeFromString<ApiEnvelope<LoginData>>(text)
                val data = envelope.data ?: return null
                session.updateTokens(data.accessToken, data.refreshToken)
                data.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val REFRESH_LOCK = Any()
    }
}
