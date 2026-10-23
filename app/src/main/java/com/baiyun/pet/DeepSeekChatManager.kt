package com.baiyun.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 聊天管理器（单例）。
 *
 * 职责：
 * 1. 用 OkHttp 异步 POST 到 https://api.deepseek.com/chat/completions；
 * 2. 组装 messages：第一条 system prompt + 最近 10 轮对话 + 本次用户输入；
 * 3. 保存对话历史（内存，最多 10 轮 = 20 条，超出丢最早的）；
 * 4. 把结果统一扔回主线程 —— 调用方直接改 UI 即可，不用自己切线程。
 *
 * API Key 现在从 [SettingsStore]（SharedPreferences）读取，每次请求现取，
 * 所以设置页改完 Key 不用重启服务就能生效。
 *
 * 错误处理：15 秒超时、HTTP 非 200、JSON 解析失败、网络异常，都会走 onError。
 */
object DeepSeekChatManager {

    private const val TAG = "DeepSeekChatManager"

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 最多保留 10 轮（一轮 = 用户 + 白云，共 2 条） */
    private const val MAX_ROUNDS = 10

    /** 需求：超时 15 秒 */
    private const val TIMEOUT_SECONDS = 15L

    /** 设置页「测试连接」发的那句话 */
    private const val TEST_MESSAGE = "你好"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 白云的人设。原样保留需求里的设定，改人设就改这一段。
     */
    private const val SYSTEM_PROMPT =
        "你叫白云，是一个住在手机里的云朵状桌宠。你精通所有动漫、游戏、网络梗，是个标准的吐槽役。" +
            "性格毒舌但可爱，傲娇，嘴硬心软。每次回复不超过80字。多用游戏梗（原神/王者/崩铁/鸣潮等）。" +
            "用户打游戏时吐槽他的操作，看小说时催他睡觉。禁止说教、不鸡汤、不提自己是AI。" +
            "示例：用户说'这把队友全是演员'，你回'经典4v6，你这队友是对面派来的卧底吧😂'"

    /** 回调：全部在主线程触发 */
    interface Callback {
        fun onReply(text: String)
        fun onError(msg: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 只记录「成功往返」的对话，失败的那轮不污染上下文 */
    private val history = mutableListOf<Message>()

    /** 同一时间只允许一个请求，避免连点打出一堆并发 */
    @Volatile
    private var inFlight = false

    /** 读 SharedPreferences 需要 Context，服务启动时调一次 init 即可 */
    @Volatile
    private var appContext: Context? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS) // 整个请求最多 15 秒
        .build()

    /** 由 Service / Activity 在启动时调用一次 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 发送一条用户消息（正常聊天用），Key 从设置里读。
     *
     * @param userText 用户输入（会 trim，空串直接忽略）
     * @param callback 结果回调，保证在主线程
     */
    fun sendMessage(userText: String, callback: Callback) {
        val context = appContext
        if (context == null) {
            callback.onError("未初始化：先调用 DeepSeekChatManager.init(context)")
            return
        }
        execute(
            userText = userText,
            apiKey = SettingsStore.apiKey(context),
            includeHistory = true,
            rememberConversation = true,
            callback = callback
        )
    }

    /**
     * 设置页「测试连接」：用指定的 Key 发一条「你好」。
     *
     * 和 [sendMessage] 走同一套请求/错误处理，区别是：
     * - Key 用输入框里还没保存的值，不用先保存再测；
     * - 不带上历史，也不写历史，纯粹验证 Key 能不能用。
     */
    fun testConnection(apiKey: String, callback: Callback) {
        execute(
            userText = TEST_MESSAGE,
            apiKey = apiKey,
            includeHistory = false,
            rememberConversation = false,
            callback = callback
        )
    }

    /** 清空上下文（以后做「清空对话」按钮用） */
    fun reset() {
        history.clear()
        inFlight = false
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private fun execute(
        userText: String,
        apiKey: String,
        includeHistory: Boolean,
        rememberConversation: Boolean,
        callback: Callback
    ) {
        val text = userText.trim()
        if (text.isEmpty()) return

        // 没填 / 还是占位符：不发请求，直接告诉调用方
        if (apiKey.isBlank() || apiKey.contains("REPLACE_WITH")) {
            callback.onError("API Key 未设置")
            return
        }

        if (inFlight) {
            // 正常不会走到这里（按钮已经置灰），兜底一下
            callback.onError("上一条还没回复，别急")
            return
        }
        inFlight = true

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(buildBody(text, includeHistory).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        Log.d(TAG, "发送请求：${text.take(30)}")
        client.newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(call: Call, e: IOException) {
                inFlight = false
                val reason = describe(e)
                Log.w(TAG, "请求失败：$reason", e)
                post { callback.onError(reason) }
            }

            override fun onResponse(call: Call, response: Response) {
                inFlight = false
                response.use { resp ->
                    val bodyText = resp.body?.string().orEmpty()

                    // 1) HTTP 非 200
                    if (!resp.isSuccessful) {
                        val detail = parseErrorMessage(bodyText) ?: bodyText.take(200)
                        Log.w(TAG, "HTTP ${resp.code}：$detail")
                        post { callback.onError("HTTP ${resp.code}：$detail") }
                        return
                    }

                    // 2) JSON 解析
                    val reply = parseReply(bodyText)
                    if (reply.isNullOrBlank()) {
                        Log.w(TAG, "解析失败，原始响应：${bodyText.take(300)}")
                        post { callback.onError("回复解析失败") }
                        return
                    }

                    // 3) 成功：记入历史（主线程统一改，避免并发问题）
                    post {
                        if (rememberConversation) remember(text, reply)
                        callback.onReply(reply)
                    }
                }
            }
        })
    }

    /** 组装请求体：system prompt →（可选）历史 10 轮 → 本次用户消息 */
    private fun buildBody(userText: String, includeHistory: Boolean): JsonObject {
        val messages = JsonArray()

        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", SYSTEM_PROMPT)
        })

        if (includeHistory) {
            history.forEach { message ->
                messages.add(JsonObject().apply {
                    addProperty("role", if (message.isUser) "user" else "assistant")
                    addProperty("content", message.text)
                })
            }
        }

        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userText)
        })

        return JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", messages)
            addProperty("stream", false) // 先不做流式，打字机效果在本地模拟
        }
    }

    /** 记下这一轮，超过 10 轮就按「对」丢掉最早的 */
    private fun remember(userText: String, reply: String) {
        history.add(Message(userText, isUser = true))
        history.add(Message(reply, isUser = false))
        while (history.size > MAX_ROUNDS * 2) {
            history.removeAt(0)
            if (history.isNotEmpty()) history.removeAt(0)
        }
    }

    /** 从 choices[0].message.content 取回复，任何异常都返回 null */
    private fun parseReply(bodyText: String): String? = try {
        val root = JsonParser.parseString(bodyText).asJsonObject
        val choices = root.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            null
        } else {
            val message = choices[0].asJsonObject.getAsJsonObject("message")
            message?.get("content")?.asString?.trim()
        }
    } catch (e: Exception) {
        Log.w(TAG, "解析回复异常", e)
        null
    }

    /** 从错误响应里取 error.message，取不到返回 null */
    private fun parseErrorMessage(bodyText: String): String? = try {
        val root = JsonParser.parseString(bodyText).asJsonObject
        root.getAsJsonObject("error")?.get("message")?.asString
    } catch (e: Exception) {
        null
    }

    /** 把异常翻译成给用户看的原因（真正的展示文案由 Service 决定） */
    private fun describe(e: IOException): String = when (e) {
        is SocketTimeoutException -> "超时（${TIMEOUT_SECONDS} 秒）"
        is UnknownHostException -> "无法连接网络"
        else -> e.message ?: "网络异常"
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
