package com.baiyun.pet

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 无障碍服务：读取当前界面的文字，解析成 [ScreenContext] 交给桌宠。
 *
 * 只做「读文字」这一件事：
 * - 监听窗口切换 / 内容变化；
 * - 在 IO 线程递归遍历节点树，只取 TextView / Button 的文字；
 * - 跳过输入框（EditText / 可编辑 / 密码框），不碰网页 HTML；
 * - 去重、限流（同一界面 3 秒内不重复处理），最多 20 条。
 *
 * 未开启时服务不会被系统启动，桌宠其它功能不受影响。
 */
class BaiyunAccessibilityService : AccessibilityService() {

    /** 接收解析结果的回调（目前只有桌宠服务注册） */
    fun interface Listener {
        fun onScreenContext(context: ScreenContext)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** TYPE_WINDOW_STATE_CHANGED 里拿到的 Activity 类名 */
    private var currentActivity: String? = null

    /** 上一次处理的界面 key + 时间，用来做 3 秒限流 */
    private var lastKey: String? = null
    private var lastProcessedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = e.packageName?.toString() ?: return
        // 不解析白云自己，避免自己触发自己
        if (packageName == this.packageName) return
        // 聊天/支付类 App 整体跳过：里面的文字再有用也不读
        if (packageName in SENSITIVE_PACKAGES) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            e.className?.toString()?.takeIf { it.contains('.') }?.let { currentActivity = it }
        }

        // 同一界面 3 秒内不重复处理（需求）
        val now = System.currentTimeMillis()
        val key = "$packageName/${currentActivity.orEmpty()}"
        if (key == lastKey && now - lastProcessedAt < THROTTLE_MS) return
        lastKey = key
        lastProcessedAt = now

        val source = e.source ?: rootInActiveWindow ?: return
        val activity = currentActivity

        // 节点遍历放到 IO 线程，别卡主线程（无障碍事件本身在主线程派发）
        scope.launch {
            val texts = runCatching { collectTexts(source) }
                .onFailure { Log.w(TAG, "遍历节点失败", it) }
                .getOrDefault(emptyList())
            if (texts.isEmpty()) return@launch

            val context = ScreenContentParser.parse(packageName, activity, texts)
            Log.d(TAG, "解析：${context.type} / ${context.title}（${texts.size} 条文字）")
            dispatch(context)
        }
    }

    override fun onInterrupt() {
        // 没有需要中断的任务
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // 节点遍历
    // ---------------------------------------------------------------------

    /**
     * 广度优先遍历（用栈实现、限制节点数），返回去重后的文字。
     *
     * 隐私：
     * - `isEditable` / `isPassword` / EditText 直接跳过，输入内容一个字都不读；
     * - 只认 TextView / Button 这类文本控件，不解析 WebView 的 HTML。
     */
    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val result = LinkedHashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        var visited = 0
        while (stack.isNotEmpty() && result.size < MAX_TEXTS && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++

            val className = node.className?.toString().orEmpty()
            if (node.isEditable || node.isPassword || className.contains("EditText")) {
                continue // 输入框及子节点一律不读
            }

            if (isTextNode(className)) {
                val text = node.text?.toString()?.trim()
                if (text != null && isUseful(text)) result.add(text)
            }

            val childCount = node.childCount
            for (i in childCount - 1 downTo 0) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                stack.addLast(child)
                if (stack.size > MAX_QUEUE) break
            }
        }

        return result.toList()
    }

    private fun isTextNode(className: String): Boolean =
        className.contains("TextView") || className.contains("Button")

    private fun isUseful(text: String): Boolean {
        if (text.length !in 2..MAX_TEXT_LENGTH) return false
        // 纯数字、纯符号（时间、比分、页码）不要
        if (text.none { it.isLetter() }) return false
        if (text.startsWith("http", ignoreCase = true)) return false
        return AD_KEYWORDS.none { text.contains(it, ignoreCase = true) }
    }

    private fun dispatch(context: ScreenContext) {
        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener.onScreenContext(context) }
                    .onFailure { Log.w(TAG, "回调失败", it) }
            }
        }
    }

    companion object {
        private const val TAG = "BaiyunA11y"

        /** 同一界面 3 秒内不重复处理 */
        private const val THROTTLE_MS = 5_000L

        /** 单个界面最多收 20 条文字 */
        private const val MAX_TEXTS = 10

        /** 单条文字超过 50 字不要（正文、广告长文） */
        private const val MAX_TEXT_LENGTH = 50

        /** 遍历上限，防止极端页面卡死 */
        private const val MAX_NODES = 800
        private const val MAX_QUEUE = 500

        /** 明显的广告/推广文案直接丢弃 */
        private val AD_KEYWORDS = listOf("广告", "赞助", "推广", "Sponsored")

        /**
         * 隐私名单：聊天、支付类 App 直接不遍历节点。
         * 这些界面里即使不是输入框，也全是别人的对话和账号信息，没必要碰。
         */
        private val SENSITIVE_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.wework",
            "com.alibaba.android.rimet",
            "com.eg.android.AlipayGphone"
        )

        private val listeners = CopyOnWriteArrayList<Listener>()

        fun addListener(listener: Listener) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        /** 服务是否已在系统设置里开启（MainActivity 用来引导） */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, BaiyunAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val name = splitter.next()
                if (ComponentName.unflattenFromString(name) == expected) return true
            }
            return false
        }
    }
}
