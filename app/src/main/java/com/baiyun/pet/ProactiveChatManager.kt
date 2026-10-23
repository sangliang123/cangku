package com.baiyun.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.baiyun.pet.AppNameMapper.Category
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * 主动搭话（看屏精简版，单例，由 [FloatingPetService] 持有）。
 *
 * 每 [intervalMs] 检查一次，五个条件全满足才开口：
 * a. 桌宠可见；b. 气泡收起；c. 距用户上次发言 ≥ 3 分钟；
 * d. 距上次主动搭话 ≥ 5 分钟；e. 屏幕亮着。
 *
 * 触发流程：
 *   取 [ScreenContextBuilder] 摘要（结构化字符串，30 秒缓存）
 *   ├─ 有 App 名 / 有文字 → 带摘要请求模型（看屏模式）
 *   └─ 摘要无效            → 从固定话术池挑一句，仍交给模型润色
 *
 * 模型返回空字符串 = 她这次不想说话 → 不弹气泡。
 * 请求失败（没 Key / 网络）→ 用话术池原句兜底。
 */
class ProactiveChatManager private constructor() {

    /** 给服务看的状态快照 */
    data class State(
        val visible: Boolean,
        val bubbleOpen: Boolean,
        val interactive: Boolean,
        val category: Category
    )

    interface Listener {
        /** 模型想好了台词（非空） */
        fun onProactiveReply(text: String)

        /** 生成失败，用话术池原句兜底 */
        fun onProactiveError(fallbackLine: String)
    }

    private var running = false
    private var appContextRef: WeakReference<Context>? = null
    private var stateProvider: (() -> State)? = null
    private var listener: Listener? = null

    private var lastUserMessageAt = 0L
    private var lastProactiveAt = 0L

    /** 发出后等用户回应的时间点；超时算"没理" */
    private var pendingReplyAt = 0L
    private var ignoredCount = 0
    private var pausedUntil = 0L

    /** 检测循环：持有 manager 弱引用，Handler 本身是静态主线程 Handler */
    private class TickRunnable(manager: ProactiveChatManager) : Runnable {
        private val ref = WeakReference(manager)

        override fun run() {
            ref.get()?.onTick()
        }
    }

    private val tickRunnable = TickRunnable(this)

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    fun start(context: Context, stateProvider: () -> State, listener: Listener) {
        this.appContextRef = WeakReference(context.applicationContext)
        this.stateProvider = stateProvider
        this.listener = listener

        running = true
        lastUserMessageAt = System.currentTimeMillis()
        lastProactiveAt = 0L
        pendingReplyAt = 0L
        ignoredCount = 0
        pausedUntil = 0L

        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, currentInterval(context))
        Log.d(TAG, "主动搭话启动，间隔 ${currentInterval(context)}ms")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        listener = null
        stateProvider = null
        appContextRef = null
        Log.d(TAG, "主动搭话停止")
    }

    /** 用户主动发消息：清空所有冷却 */
    fun notifyUserMessage() {
        lastUserMessageAt = System.currentTimeMillis()
        lastProactiveAt = 0L
        pendingReplyAt = 0L
        ignoredCount = 0
        pausedUntil = 0L
    }

    // ---------------------------------------------------------------------
    // 检测
    // ---------------------------------------------------------------------

    private fun onTick() {
        if (!running) return
        val context = appContextRef?.get()
        if (context == null) {
            stop()
            return
        }

        handler.postDelayed(tickRunnable, currentInterval(context))
        updateIgnoreCount(System.currentTimeMillis())

        if (!SettingsStore.proactiveEnabled(context)) return
        if (!shouldTrigger()) return

        trigger(context)
    }

    private fun shouldTrigger(): Boolean {
        val now = System.currentTimeMillis()
        if (now < pausedUntil) return false

        val state = stateProvider?.invoke() ?: return false
        if (!state.visible) return false
        if (state.bubbleOpen) return false
        if (!state.interactive) return false
        if (now - lastUserMessageAt < USER_IDLE_MS) return false
        if (lastProactiveAt > 0 && now - lastProactiveAt < PROACTIVE_COOLDOWN_MS) return false

        return true
    }

    private fun trigger(context: Context) {
        val state = stateProvider?.invoke() ?: return

        lastProactiveAt = System.currentTimeMillis()
        pendingReplyAt = lastProactiveAt

        val category = state.category
        val fallbackLine = pickLine(category)
        val includeTexts = SettingsStore.proactiveUseScreenText(context)

        val callback = object : DeepSeekChatManager.Callback {
            override fun onReply(text: String) {
                // 需求：模型返回空 = 这次不搭话，不弹气泡
                if (text.isBlank()) {
                    // 这次没说话，不算"用户没理"
                    pendingReplyAt = 0L
                    Log.d(TAG, "模型选择不搭话")
                    return
                }
                listener?.onProactiveReply(text.trim())
            }

            override fun onError(msg: String) {
                Log.w(TAG, "主动搭话生成失败：$msg")
                listener?.onProactiveError(fallbackLine)
            }
        }

        val summary = ScreenContextBuilder.buildMeaningfulSummary(includeTexts)
        if (summary != null) {
            val hint = sceneHint(ScreenContextBuilder.snapshot())
            Log.d(TAG, "看屏搭话：$summary | $hint")
            DeepSeekChatManager.sendProactiveWithScreen(summary, hint, callback)
        } else {
            Log.d(TAG, "无有效上下文，走话术池：$fallbackLine")
            DeepSeekChatManager.sendMessage("[主动搭话]$fallbackLine", callback)
        }
    }

    /** 根据界面文字挑一句场景提示（不是台词，是给模型的语气指引） */
    private fun sceneHint(snapshot: ScreenContextBuilder.Snapshot): String {
        val joined = snapshot.texts.joinToString(" ")

        return when {
            FAIL_WORDS.any { joined.contains(it) } ->
                "用户刚经历失败/阵亡，先吐槽再给点安慰。"

            WIN_WORDS.any { joined.contains(it) } ->
                "用户刚通关/获胜，傲娇地庆祝一下。"

            CHAPTER_REGEX.containsMatchIn(joined) ||
                joined.contains("继续阅读") || joined.contains("下一章") ->
                "用户在读小说，催睡或催更。"

            DAILY_WORDS.any { joined.contains(it) } ->
                "用户在领日常奖励，提醒他别漏了、别太肝。"

            QUEUE_WORDS.any { joined.contains(it) } ->
                "用户在排队匹配，陪他闲聊两句。"

            snapshot.minutes > 60 && snapshot.category == Category.GAME ->
                "用户已经玩了一小时以上，提醒休息。"

            snapshot.minutes > 30 && snapshot.category == Category.READING ->
                "用户看了半小时以上，警告熬夜。"

            else -> "没有特别事件，基于 App 名随口吐槽一句。"
        }
    }

    private fun updateIgnoreCount(now: Long) {
        if (pendingReplyAt == 0L) return
        if (now - pendingReplyAt < IGNORE_WINDOW_MS) return

        pendingReplyAt = 0L
        ignoredCount++
        if (ignoredCount >= MAX_IGNORED) {
            pausedUntil = now + PAUSE_AFTER_IGNORED_MS
            ignoredCount = 0
            Log.d(TAG, "连续 $MAX_IGNORED 次没理，暂停 30 分钟")
        }
    }

    // ---------------------------------------------------------------------
    // 频率 / 话术
    // ---------------------------------------------------------------------

    private fun currentInterval(context: Context): Long {
        val base = when (SettingsStore.proactiveFrequency(context)) {
            SettingsStore.FREQ_QUIET -> 5 * 60_000L
            SettingsStore.FREQ_CHATTY -> 1 * 60_000L
            else -> 3 * 60_000L
        }
        return if (isNight()) base * 2 else base
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 7
    }

    private fun pickLine(category: Category): String = when (category) {
        Category.GAME -> GAME_LINES.random()
        Category.READING -> READING_LINES.random()
        else -> GENERAL_LINES.random()
    }

    companion object {
        private const val TAG = "ProactiveChat"

        val instance: ProactiveChatManager by lazy { ProactiveChatManager() }

        /** 静态主线程 Handler，不持有 Activity/Service */
        private val handler = Handler(Looper.getMainLooper())

        private const val USER_IDLE_MS = 3 * 60_000L
        private const val PROACTIVE_COOLDOWN_MS = 5 * 60_000L
        private const val IGNORE_WINDOW_MS = 5 * 60_000L
        private const val MAX_IGNORED = 3
        private const val PAUSE_AFTER_IGNORED_MS = 30 * 60_000L

        private val FAIL_WORDS = listOf("失败", "阵亡", "挑战失败", "团灭", "被击杀")
        private val WIN_WORDS = listOf("胜利", "通关", "完成", "五杀", "超神", "吃鸡")
        private val DAILY_WORDS = listOf("签到", "领取", "奖励", "每日")
        private val QUEUE_WORDS = listOf("匹配中", "等待", "排队")
        private val CHAPTER_REGEX = Regex("第\\s*[0-9一二三四五六七八九十百千万零两]+\\s*章")

        // ---------------- 话术池（无上下文时兜底，可随时替换） ----------------

        val GAME_LINES: List<String> = listOf(
            "你这把打了多久了？该不会又卡在同一个boss吧",
            "你沉默这么久，是在憋大招还是在发呆",
            "这关又没过？要不要我帮你骂策划",
            "你手速行不行啊，这都多久没点了",
            "连败了吧老实说，我看着都替你急",
            "这操作，你是用手肘在玩吗",
            "复活了吗？我数到三"
        )

        val READING_LINES: List<String> = listOf(
            "看小说别看太晚啊，你昨天也是这个点还在划",
            "这章完了吧？该睡了吧？嗯？",
            "你翻页速度我都能数出来，这么精彩？",
            "又熬夜追更？明天黑眼圈别怪我",
            "这一页你盯了三分钟了，是字太美吗"
        )

        val GENERAL_LINES: List<String> = listOf(
            "你在吗？不会睡着了吧",
            "我闲着也是闲着，你倒是跟我说句话啊",
            "你手机亮着但没人动，是在挂机？",
            "我数了，你 5 分钟没点屏幕了",
            "你知不知道你盯屏幕的时间已经超过推荐值了",
            "喂，别装看不见我",
            "手机发烫了，你是不是又边充边玩",
            "你再不理我，我就要在你屏幕上打滚了",
            "这都几点了，还清醒着？"
        )
    }
}
