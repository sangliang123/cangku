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

    /**
     * 云端视觉用的模型。
     *
     * 注意：DeepSeek 公开 API 目前以文本为主，图片请求可能返回 400。
     * 这里的协议（content 数组 + image_url + data:image/jpeg;base64）是 OpenAI 兼容标准，
     * 换成任何支持视觉的 OpenAI 兼容端点/模型即可直接复用。
     */
    private const val VISION_MODEL = "deepseek-chat"

    private const val VISION_PROMPT =
        "描述这张手机截图，用户在做什么（游戏/阅读/聊天），画面里有什么关键信息"

    /** 拼在 system prompt 末尾的上下文要求 */
    private const val SCREEN_CONTEXT_RULE =
        "[要求] 根据以上上下文自然吐槽，不要生硬提及\"我看到你在玩xx\"，像朋友随口一瞥那样自然。"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 白云的人设。原样保留需求里的设定，改人设就改这一段。
     */
    private const val SYSTEM_PROMPT =
        "你叫白云，是一个住在手机里的云朵状桌宠。你精通所有动漫、游戏、网络梗，是个标准的吐槽役。" +
            "性格毒舌但可爱，傲娇，嘴硬心软。每次回复不超过80字。多用游戏梗（原神/王者/崩铁/鸣潮等）。" +
            "用户打游戏时吐槽他的操作，看小说时催他睡觉。禁止说教、不鸡汤、不提自己是AI。" +
            "示例：用户说'这把队友全是演员'，你回'经典4v6，你这队友是对面派来的卧底吧😂'" +
            "你能感知用户当前屏幕内容（App、文字、画面），据此自然吐槽。规则：" +
            "不要每次都提'我在看你屏幕'，像你本来就知道一样；" +
            "游戏残血时：'你这血条是装饰吗'；" +
            "小说熬夜时：'你眼睛不要可以捐'；" +
            "刷视频时：'这视频你看了三遍了吧'；" +
            "上下文感知要自然，别像播报员。" +
            "[主动搭话模式] 当消息以'[主动搭话]'开头时，说明是白云自己主动找用户聊的。此时：" +
            "语气要像突然拍用户肩膀：随意、突然、带点欠；" +
            "不要问'有什么可以帮你的'这种客服语；" +
            "直接开吐槽/开聊，像朋友突然发微信给你；" +
            "长度控制在 30~60 字，比平时更短。" +
            "示例：用户消息='[主动搭话]你这把打了多久了'，你回'你那维莱特都死了三回了还没过？你跟这boss有仇？'"

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

    /**
     * 把截屏（base64 JPEG）发给视觉接口，让模型描述画面。
     * 不写对话历史，也不影响正常聊天上下文。
     */
    fun describeScreenshot(jpegBase64: String, callback: Callback) {
        val context = appContext
        if (context == null) {
            callback.onError("未初始化：先调用 DeepSeekChatManager.init(context)")
            return
        }
        val apiKey = SettingsStore.apiKey(context)
        if (apiKey.isBlank() || apiKey.contains("REPLACE_WITH")) {
            callback.onError("API Key 未设置")
            return
        }
        if (inFlight) {
            callback.onError("上一条还没回复，别急")
            return
        }
        inFlight = true

        val body = JsonObject().apply {
            addProperty("model", VISION_MODEL)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", VISION_PROMPT)
                        })
                        add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply {
                                addProperty("url", "data:image/jpeg;base64,$jpegBase64")
                            })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        Log.d(TAG, "发送视觉请求，base64 长度 ${jpegBase64.length}")
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                inFlight = false
                val reason = describe(e)
                Log.w(TAG, "视觉请求失败：$reason", e)
                post { callback.onError(reason) }
            }

            override fun onResponse(call: Call, response: Response) {
                inFlight = false
                response.use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val detail = parseErrorMessage(bodyText) ?: bodyText.take(200)
                        Log.w(TAG, "视觉 HTTP ${resp.code}：$detail")
                        post { callback.onError("HTTP ${resp.code}：$detail") }
                        return
                    }
                    val reply = parseReply(bodyText)
                    if (reply.isNullOrBlank()) {
                        post { callback.onError("回复解析失败") }
                        return
                    }
                    post { callback.onReply(reply) }
                }
            }
        })
    }

    /** 清空上下文（以后做「清空对话」按钮用） */
    fun reset() {
        history.clear()
        inFlight = false
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    /**
     * 每条请求现拼 system prompt：人设 + 当前屏幕上下文（30 秒缓存，过期重建）。
     * 没有屏幕数据时就是原始人设，聊天不受影响。
     */
    private fun buildSystemPrompt(): String {
        // 没有有效上下文（无 App、无文字）就不注入，保持原人设
        val summary = ScreenContextBuilder.buildMeaningfulSummary()
        if (summary.isNullOrBlank()) return SYSTEM_PROMPT
        return buildString {
            append(SYSTEM_PROMPT)
            append("\n\n[当前屏幕上下文]\n")
            append(summary)
            append("\n")
            append(SCREEN_CONTEXT_RULE)
        }
    }

    /**
     * 主动搭话·看屏模式：system prompt 里塞入结构化屏幕摘要与场景提示，
     * 用户侧只发一句内部标记 "[主动搭话] 说一句"。
     * 模型返回空字符串表示这次不搭话，调用方负责跳过。
     */
    fun sendProactiveWithScreen(summary: String, sceneHint: String, callback: Callback) {
        val context = appContext
        if (context == null) {
            callback.onError("未初始化")
            return
        }
        val apiKey = SettingsStore.apiKey(context)
        if (apiKey.isBlank() || apiKey.contains("REPLACE_WITH")) {
            callback.onError("API Key 未设置")
            return
        }

        val prompt = buildString {
            append(SYSTEM_PROMPT)
            append("\n\n[主动搭话-看屏模式]\n")
            append("白云主动找用户聊（用户没先说话）。已知屏幕摘要：\n")
            append(summary).append("\n")
            append("场景提示：").append(sceneHint).append("\n")
            append("规则：\n")
            append("- 像朋友瞥了一眼你手机屏幕后随口吐槽，自然、突然、带点欠\n")
            append("- 基于摘要内容说 1 句（30~60字），不要泛泛而谈\n")
            append("- 不播报数据（别说「我看到你在玩原神47分钟」），要像本来就看到了\n")
            append("- 示例：\n")
            append("  摘要=app=原神 | 时长=47min | 界面文字=挑战失败,角色血量12% → 「又死了？那维莱特血条都快没了你还转圈？」\n")
            append("  摘要=app=微信读书 | 时长=32min | 界面文字=第248章 → 「248章了，你眼睛不要可以捐，明天还要上班吧」\n")
            append("  摘要=app=王者荣耀 | 界面文字=匹配中 → 「排多久了？这匹配速度比我的代码编译还慢」\n")
            append("- 没啥好说的就返回空字符串（不搭话）\n")
            append("- 不提隐私、不读敏感信息")
        }

        execute(
            userText = "[主动搭话] 说一句",
            apiKey = apiKey,
            includeHistory = true,
            rememberConversation = true,
            callback = callback,
            systemPromptOverride = prompt
        )
    }

    private fun execute(
        userText: String,
        apiKey: String,
        includeHistory: Boolean,
        rememberConversation: Boolean,
        callback: Callback,
        systemPromptOverride: String? = null
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
            .post(
                buildBody(
                    text,
                    includeHistory,
                    systemPromptOverride ?: buildSystemPrompt()
                ).toString().toRequestBody(JSON_MEDIA_TYPE)
            )
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
    private fun buildBody(
        userText: String,
        includeHistory: Boolean,
        systemPrompt: String = buildSystemPrompt()
    ): JsonObject {
        val messages = JsonArray()

        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt)
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
