package com.baiyun.pet

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 白云桌宠的悬浮窗服务。
 *
 * 一共两个悬浮窗：
 * 1. 桌宠窗口（80×80dp 形象 + 四周 6dp 留白）：可拖动、单击开气泡、双击躲起来；
 * 2. 聊天气泡窗口（280dp 宽）：从形象旁边弹出，内部是 RecyclerView + 输入框。
 *
 * 为什么拆成两个窗口？因为气泡比形象大得多。如果塞进同一个窗口，
 * 窗口就得覆盖一大片屏幕，用户点别处会被挡住；拆开之后窗口各管各的，互不干扰。
 *
 * 位置规则：形象在屏幕右半边 → 气泡从左边弹出；在左半边 → 从右边弹出。
 */
class FloatingPetService : Service() {

    companion object {
        private const val TAG = "FloatingPetService"

        /**
         * 通知渠道 id。
         * v2：早期版本用的是 LOW，而渠道重要性一旦建好就改不了，
         * 所以这里换一个新 id，把旧的删掉重建。
         */
        private const val CHANNEL_ID = "baiyun_pet_channel_v2"
        private const val LEGACY_CHANNEL_ID = "baiyun_pet_channel"

        /** 服务被销毁后，延迟多久自愈重启 */
        private const val RESTART_DELAY_MS = 1000L

        /** 前台 App 检测间隔（需求：10 秒） */
        private const val SCREEN_POLL_INTERVAL_MS = 10_000L
        private const val NOTIFICATION_ID = 1024

        /** 关闭桌宠 */
        const val ACTION_STOP = "com.baiyun.pet.action.STOP"

        /** 让躲起来的白云重新出现（通知栏、主界面按钮都会发它） */
        const val ACTION_SHOW = "com.baiyun.pet.action.SHOW"

        /** 截屏相关（MediaProjection 授权结果必须从 Activity 传进来） */
        const val ACTION_START_CAPTURE = "com.baiyun.pet.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.baiyun.pet.action.STOP_CAPTURE"
        const val EXTRA_CAPTURE_RESULT_CODE = "extra_capture_result_code"
        const val EXTRA_CAPTURE_DATA = "extra_capture_data"

        /** 打游戏时降频到 10 秒 */
        private const val GAME_CAPTURE_INTERVAL_MS = 10_000L

        /** 主动搭话后气泡自动收起的时间 */
        private const val AUTO_HIDE_DELAY_MS = 10_000L

        /** 窗口比形象大一圈，给「上下浮动」动画留出空间，否则会被窗口边缘裁掉 */
        private const val PET_WINDOW_PADDING_DP = 6f

        /** 吸附时离屏幕边缘的空隙，0 = 完全贴边 */
        private const val EDGE_MARGIN_DP = 0f

        /** 吸附动画时长 */
        private const val SNAP_DURATION_MS = 260L

        /** 气泡宽度 */
        private const val BUBBLE_WIDTH_DP = 280f

        /** 气泡与形象之间的间距 */
        private const val BUBBLE_GAP_DP = 8f

        /** 消息列表最大高度（需求：200dp） */
        private const val BUBBLE_MAX_HEIGHT_DP = 200f

        /** 打字机：每个字之间的间隔（需求：30ms） */
        private const val TYPING_INTERVAL_MS = 30L

        /** 服务是否在运行：MainActivity 用它刷新界面 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 形象是否被双击藏起来了（此时只剩通知栏的一个入口） */
        @Volatile
        var isPetHidden: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var inflater: LayoutInflater
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------------- 桌宠窗口 ----------------

    /** 承载形象的窗口容器（带 padding，允许形象上下浮动不被裁切） */
    private var petContainer: FrameLayout? = null

    /** 窗口里的形象本体，拖动 / 浮动动画都作用在它身上 */
    private var petView: ImageView? = null

    /** 桌宠窗口的布局参数 */
    private lateinit var params: WindowManager.LayoutParams

    /** 云朵大小（dp）：来自设置，改动时重建悬浮窗 */
    private var cloudSizeDp = SettingsStore.DEFAULT_CLOUD_SIZE.toFloat()

    /** 气泡不透明度（50~100%）：来自设置，实时生效 */
    private var bubbleAlphaPercent = SettingsStore.DEFAULT_BUBBLE_ALPHA

    private var petSizePx = 0
    private var windowSizePx = 0

    private var screenWidth = 0
    private var screenHeight = 0
    private var maxX = 0
    private var maxY = 0

    private var snapAnimator: ValueAnimator? = null
    private var tiltAnimator: ValueAnimator? = null

    /** 气泡打开时形象的上下浮动动画 */
    private var floatAnimator: ObjectAnimator? = null

    /** 双击藏起来之前的位置，重新出现时回到原地 */
    private var lastX = -1
    private var lastY = -1

    // ---------------- 气泡窗口 ----------------

    private var bubbleView: View? = null
    private var rvMessages: MaxHeightRecyclerView? = null
    private var etMessage: EditText? = null
    private var chatAdapter: ChatAdapter? = null
    private var btnSend: ImageButton? = null
    private var isBubbleOpen = false

    /** 主动停止（用户点关闭 / 没有权限）时为 true，onDestroy 不再自愈重启 */
    private var suppressRestart = false

    // ---------------- 前台 App 检测 ----------------

    /** 上一次检测到的前台包名，用来判断"切 App 了" */
    private var lastForegroundPackage: String? = null
    private var lastAppName: String? = null
    private var lastAppChangedAt = 0L
    private var warnedNoUsageAccess = false

    /**
     * 前台 App 切换回调。
     * 现在只是 Log.d；下一步接聊天时，把这个实现换成「让白云说一句」即可。
     */
    var appChangeListener: AppChangeListener = AppChangeListener { appName ->
        Log.d(TAG, "onAppChanged: $appName")
    }

    fun interface AppChangeListener {
        fun onAppChanged(appName: String)
    }

    /** 无障碍服务解析出的当前屏幕上下文（供后续聊天拼 prompt 用） */
    @Volatile
    var currentContext: ScreenContext? = null
        private set

    private val screenContextListener = object : BaiyunAccessibilityService.Listener {
        override fun onScreenContext(context: ScreenContext) {
            currentContext = context
            ScreenContextBuilder.updateAccessibility(context.texts)
            Log.d(
                TAG,
                "屏幕上下文：${context.type} / ${context.title} / ${context.packageName}"
            )
        }
    }

    // ---------------- 截屏感知（可选） ----------------

    private var captureManager: ScreenCaptureManager? = null

    /** 上一帧还在识别中，先丢掉新帧，避免堆积 */
    private var ocrBusy = false

    /** 云端视觉最近一次的画面描述 */
    @Volatile
    var lastVisionText: String? = null
        private set

    /** 正在等 DeepSeek 回复（按钮置灰） */
    private var isRequesting = false

    /** 正在逐字显示回复（按钮置灰） */
    private var isTyping = false
    private var typingRunnable: Runnable? = null
    private var typingIndex = -1
    private var typingFullText = ""

    /** 消息历史：关掉气泡再打开还在（不接 AI 时就是本地的一份列表） */
    private val messages = mutableListOf<Message>()

    // ---------------- 拖动状态 ----------------
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var touchSlopPx = 0

    /** 单击 / 双击识别 */
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            /** 单击：展开 / 收起气泡（系统会等 300ms 确认不是双击） */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isDragging) return false
                toggleBubble()
                return true
            }

            /** 双击：把整个形象藏起来，只留通知栏 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDragging) return false
                hidePet()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })
    }

    /**
     * 设置变化监听。
     * SharedPreferences 的回调发生在调用 apply() 的线程（设置页 = 主线程），
     * 这里再 post 一次，保证一定在主线程动 View。
     */
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsStore.KEY_CLOUD_SIZE -> mainHandler.post { applyCloudSize() }
                SettingsStore.KEY_BUBBLE_ALPHA -> mainHandler.post { applyBubbleAlpha() }
                SettingsStore.KEY_CAPTURE_INTERVAL -> mainHandler.post {
                    captureManager?.setInterval(currentCaptureInterval())
                }
                SettingsStore.KEY_CAPTURE_ENABLED -> mainHandler.post {
                    if (!SettingsStore.captureEnabled(this)) stopScreenCapture()
                }
            }
        }

    /** 云朵大小变了：removeView 后用新尺寸重新 addView，位置贴回最近的边 */
    private fun applyCloudSize() {
        val newSize = SettingsStore.cloudSize(this).toFloat()
        if (newSize == cloudSizeDp) return
        cloudSizeDp = newSize

        // 藏起来的时候不用重建，恢复时 showPet() 会按新尺寸创建
        val container = petContainer ?: return

        lastX = params.x
        lastY = params.y
        closeBubble()
        removeViewSafely(container)
        petContainer = null
        petView = null

        showPet()
        petContainer?.let { newContainer ->
            params.x = nearestEdgeX()
            safeUpdateLayout(newContainer, params)
        }
    }

    /** 气泡不透明度：直接改 alpha，气泡开着也能立刻看到效果 */
    private fun applyBubbleAlpha() {
        bubbleAlphaPercent = SettingsStore.bubbleAlpha(this)
        bubbleView?.alpha = bubbleAlphaPercent / 100f
    }

    // ---------------------------------------------------------------------
    // 前台 App 检测
    // ---------------------------------------------------------------------

    /** 每 10 秒跑一次；放在主线程 Handler 上，跟随服务生命周期 */
    private val screenPollRunnable = object : Runnable {
        override fun run() {
            detectForegroundApp()
            mainHandler.postDelayed(this, SCREEN_POLL_INTERVAL_MS)
        }
    }

    private fun startScreenDetection() {
        mainHandler.removeCallbacks(screenPollRunnable)
        mainHandler.postDelayed(screenPollRunnable, SCREEN_POLL_INTERVAL_MS)
    }

    private fun stopScreenDetection() {
        mainHandler.removeCallbacks(screenPollRunnable)
    }

    /**
     * 查一次前台 App。
     * - 没权限：安静跳过（只提醒一次日志），用户授权后下一次轮询自动生效；
     * - 包名没变：什么都不做；
     * - 变了：记下名字/时间戳，游戏或阅读类才回调 onAppChanged。
     */
    private fun detectForegroundApp() {
        if (!ScreenDetector.hasUsageAccess(this)) {
            if (!warnedNoUsageAccess) {
                warnedNoUsageAccess = true
                Log.w(TAG, "没有「使用情况访问」权限，前台 App 检测暂停")
            }
            return
        }
        warnedNoUsageAccess = false

        val packageName = ScreenDetector.currentForegroundPackage(this) ?: return
        if (packageName == lastForegroundPackage) return

        val info = AppNameMapper.resolve(packageName)
        lastForegroundPackage = packageName
        lastAppName = info.displayName
        lastAppChangedAt = System.currentTimeMillis()
        ScreenContextBuilder.updateForeground(
            packageName = packageName,
            appName = info.displayName,
            category = info.category,
            changedAt = lastAppChangedAt
        )
        Log.d(TAG, "前台 App 切换：${info.displayName}（$packageName，${info.category}）")

        if (info.category == AppNameMapper.Category.GAME ||
            info.category == AppNameMapper.Category.READING
        ) {
            appChangeListener.onAppChanged(info.displayName)
        }

        // 切到游戏降频、切回来恢复
        captureManager?.setInterval(currentCaptureInterval())
    }

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inflater = LayoutInflater.from(this)
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop

        // 读一次设置（之后靠 SharedPreferences 监听实时更新）
        cloudSizeDp = SettingsStore.cloudSize(this).toFloat()
        bubbleAlphaPercent = SettingsStore.bubbleAlpha(this)
        // 聊天管理器需要 Context 才能读 API Key
        DeepSeekChatManager.init(this)

        // 服务重启后旧的 MediaProjection 授权已经失效，需要用户重新授权
        if (SettingsStore.captureEnabled(this)) {
            SettingsStore.setCaptureEnabled(this, false)
            Log.d(TAG, "截屏授权已失效，等待用户重新开启")
        }

        createNotificationChannel()
        // 必须先成为前台服务，否则 Android 8.0+ 会直接把服务杀掉
        startForegroundCompat()

        // 没有悬浮窗权限就不可能显示形象，直接退出
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，服务退出")
            suppressRestart = true
            stopSelf()
            return
        }

        // 设置页一保存，这里就能收到通知
        SettingsStore.prefs(this)
            .registerOnSharedPreferenceChangeListener(settingsListener)

        showPet()
        isRunning = true

        startScreenDetection()
        startProactive()
        BaiyunAccessibilityService.addListener(screenContextListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                suppressRestart = true
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(
                    EXTRA_CAPTURE_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CAPTURE_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CAPTURE_DATA)
                }
                if (data != null) startScreenCapture(resultCode, data)
                return START_STICKY
            }

            ACTION_STOP_CAPTURE -> {
                stopScreenCapture()
                return START_STICKY
            }

            ACTION_SHOW -> {
                // 从通知栏点回来：把藏起来的形象放回原位
                if (petContainer == null) {
                    showPet()
                    updateNotification()
                }
                return START_STICKY
            }
        }
        // 进程被系统回收后自动重建，桌宠仍然在
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 转屏 / 分屏时重新计算边界，气泡直接收起（位置已经失效） */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closeBubble()
        val container = petContainer ?: return
        updateScreenBounds()
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        if (!isDragging) {
            params.x = nearestEdgeX()
        }
        safeUpdateLayout(container, params)
    }

    override fun onDestroy() {
        isRunning = false
        isPetHidden = false
        stopProactive()
        cancelAutoHide()
        stopScreenCapture()
        BaiyunAccessibilityService.removeListener(screenContextListener)
        stopScreenDetection()
        stopFloating()
        finishTyping()
        snapAnimator?.cancel()
        tiltAnimator?.cancel()

        try {
            SettingsStore.prefs(this)
                .unregisterOnSharedPreferenceChangeListener(settingsListener)
        } catch (e: Exception) {
            Log.w(TAG, "反注册设置监听失败", e)
        }

        // 服务要结束了，气泡必须立即拆掉（等不了收拢动画）
        isBubbleOpen = false
        bubbleView?.let { removeViewSafely(it) }
        bubbleView = null
        rvMessages = null
        etMessage = null
        chatAdapter = null

        petContainer?.let { removeViewSafely(it) }
        petContainer = null
        petView = null

        // 被系统清掉时拉自己一把；用户主动关闭 / 没权限时不会走这里
        scheduleRestartIfNeeded()

        super.onDestroy()
    }

    /**
     * 保活策略之二：服务被销毁后延迟 1 秒把自己重新拉起来。
     *
     * 只做这一层，不搞互相唤醒、1px Activity、双进程那些黑科技——
     * 前台服务 + START_STICKY + 这一层重启，配合通知和厂商白名单已经够用。
     * 失败（比如 Android 12+ 的后台启动限制）就安静放弃，等用户打开 App。
     */
    private fun scheduleRestartIfNeeded() {
        if (suppressRestart) return
        if (!SettingsStore.isPetEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return

        // 服务实例马上要被回收，用 Application Context 更稳妥
        val context = applicationContext
        mainHandler.postDelayed({
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingPetService::class.java)
                )
                Log.d(TAG, "服务已自愈重启")
            } catch (e: Exception) {
                Log.w(TAG, "自愈重启失败（可能被后台启动限制）", e)
            }
        }, RESTART_DELAY_MS)
    }

    // ---------------------------------------------------------------------
    // 主动搭话
    // ---------------------------------------------------------------------

    private val proactiveListener = object : ProactiveChatManager.Listener {
        override fun onProactiveReply(text: String) {
            mainHandler.post {
                if (!isBubbleOpen) openBubble()
                shakePet()
                typeOutReply(text)
                scheduleAutoHide()
            }
        }

        override fun onProactiveError(fallbackLine: String) {
            mainHandler.post {
                if (!isBubbleOpen) openBubble()
                shakePet()
                typeOutReply(fallbackLine)
                scheduleAutoHide()
            }
        }
    }

    /** 主动搭话后 10 秒没人理就自动收起气泡（可在设置里关） */
    private val autoHideRunnable = object : Runnable {
        override fun run() {
            if (!isBubbleOpen) return
            if (isTyping) {
                mainHandler.postDelayed(this, 2_000L)
                return
            }
            closeBubble()
        }
    }

    private fun scheduleAutoHide() {
        if (!SettingsStore.proactiveAutoHide(this)) return
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    /** 云朵左右抖三下（±8°，每下 100ms） */
    private fun shakePet() {
        val view = petView ?: return
        ObjectAnimator.ofFloat(view, "rotation", 0f, 8f, -8f, 8f, -8f, 8f, 0f).apply {
            duration = 600L
            start()
        }
    }

    private fun startProactive() {
        ProactiveChatManager.instance.start(
            context = this,
            stateProvider = {
                ProactiveChatManager.State(
                    visible = !isPetHidden && petContainer != null,
                    bubbleOpen = isBubbleOpen,
                    interactive = getSystemService(PowerManager::class.java)?.isInteractive == true,
                    category = lastForegroundPackage?.let {
                        AppNameMapper.resolve(it).category
                    } ?: AppNameMapper.Category.OTHER
                )
            },
            listener = proactiveListener
        )
    }

    private fun stopProactive() {
        ProactiveChatManager.instance.stop()
    }

    // ---------------------------------------------------------------------
    // 截屏感知
    // ---------------------------------------------------------------------

    /**
     * 启动截屏。
     * Android 14 要求应用有 mediaProjection 类型的前台服务，所以先升级 FGS 类型再创建投影。
     */
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        stopScreenCapture()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } catch (e: Exception) {
                Log.w(TAG, "升级前台服务类型失败", e)
            }
        }

        val manager = ScreenCaptureManager(
            context = applicationContext,
            canCapture = { shouldCaptureFrame() },
            onFrame = { bitmap -> handleCapturedFrame(bitmap) },
            onStopped = {
                // 用户在通知栏停掉了投屏
                mainHandler.post {
                    captureManager = null
                    SettingsStore.setCaptureEnabled(this, false)
                    Log.d(TAG, "投屏被用户停止，截屏已关闭")
                }
            }
        )

        if (!manager.start(resultCode, data)) {
            Log.w(TAG, "截屏启动失败")
            SettingsStore.setCaptureEnabled(this, false)
            return
        }

        manager.setInterval(currentCaptureInterval())
        captureManager = manager
        SettingsStore.setCaptureEnabled(this, true)
    }

    private fun stopScreenCapture() {
        val manager = captureManager ?: return
        captureManager = null
        ocrBusy = false
        manager.stop()

        // 恢复成普通前台服务，避免一直占着 mediaProjection 类型
        if (isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                Log.w(TAG, "恢复前台服务类型失败", e)
            }
        }
    }

    /** 是否该截这一帧：开关、亮屏、电量、前台 App 类型 */
    private fun shouldCaptureFrame(): Boolean {
        if (!SettingsStore.captureEnabled(this)) return false

        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return false

        if (isBatteryLow()) return false

        val packageName = lastForegroundPackage ?: return false
        val category = AppNameMapper.resolve(packageName).category
        return category == AppNameMapper.Category.GAME ||
            category == AppNameMapper.Category.READING
    }

    /** 电量低于 20% 自动暂停 */
    private fun isBatteryLow(): Boolean {
        val battery = getSystemService(BatteryManager::class.java) ?: return false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0..19
    }

    /** 打游戏时至少 10 秒一帧，其它情况按设置 */
    private fun currentCaptureInterval(): Long {
        val base = SettingsStore.captureIntervalMs(this)
        val isGame = AppNameMapper.resolve(lastForegroundPackage ?: "").category ==
            AppNameMapper.Category.GAME
        return if (isGame) maxOf(base, GAME_CAPTURE_INTERVAL_MS) else base
    }

    /** 拿到一帧：按设置走本地 OCR 或云端视觉 */
    private fun handleCapturedFrame(bitmap: Bitmap) {
        if (ocrBusy) {
            bitmap.recycle()
            return
        }
        ocrBusy = true

        if (SettingsStore.captureMode(this) == SettingsStore.CAPTURE_MODE_CLOUD) {
            // 压缩后 bitmap 会被回收（内部处理）
            val base64 = ScreenOcrProcessor.encodeJpegBase64(bitmap)
            DeepSeekChatManager.describeScreenshot(base64, object : DeepSeekChatManager.Callback {
                override fun onReply(text: String) {
                    ocrBusy = false
                    lastVisionText = text
                    Log.d(TAG, "云端视觉：$text")
                }

                override fun onError(msg: String) {
                    ocrBusy = false
                    Log.w(TAG, "云端视觉失败：$msg")
                }
            })
            return
        }

        ScreenOcrProcessor.recognize(
            bitmap = bitmap,
            onResult = { lines ->
                bitmap.recycle()
                ocrBusy = false
                applyOcrText(lines)
            },
            onError = { msg ->
                bitmap.recycle()
                ocrBusy = false
                Log.w(TAG, "OCR 失败：$msg")
            }
        )
    }

    /** 本地 OCR 的文字走一遍规则解析，存进 currentContext */
    private fun applyOcrText(lines: List<String>) {
        if (lines.isEmpty()) return
        val context = ScreenContentParser.parse(
            packageName = lastForegroundPackage ?: "unknown",
            activityName = null,
            texts = lines
        )
        currentContext = context
        Log.d(TAG, "OCR 上下文：${context.type} / ${context.title}")
    }

    // ---------------------------------------------------------------------
    // 前台通知
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        // 清掉旧的低优先级渠道，避免用户看到两个「白云桌宠」
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // 需求：HIGH。通知会弹一下、更显眼，也更不容易被厂商当普通后台通知折叠
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 两种状态：
     * - 正常：通知标题「白云正在陪你」，点一下回 App；
     * - 已隐藏：标题「白云躲起来了」，点一下直接让白云重新出现（需求要求）。
     */
    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isPetHidden) {
            // 点通知本体 = 让白云出来
            val showIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, FloatingPetService::class.java).setAction(ACTION_SHOW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentTitle(getString(R.string.notification_hidden_title))
                .setContentText(getString(R.string.notification_hidden_text))
                .setContentIntent(showIntent)
                // 顺便留个回到 App 的入口
                .addAction(
                    R.drawable.ic_pet_notification,
                    getString(R.string.notification_action_open_app),
                    openAppIntent
                )
        } else {
            builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_body))
                .setContentIntent(openAppIntent)
        }

        return builder.build()
    }

    /** 刷新常驻通知（藏起来 / 恢复时调用） */
    private fun updateNotification() {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    /** Android 14（API 34）要求前台服务在 startForeground 时声明类型 */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // 桌宠窗口
    // ---------------------------------------------------------------------

    @Suppress("DEPRECATION") // Android 8.0 以下只能用 TYPE_PHONE
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 计算屏幕尺寸（包含状态栏/导航栏的整块屏幕） */
    @Suppress("DEPRECATION") // API 30 以下要用 defaultDisplay.getRealMetrics
    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        maxX = (screenWidth - windowSizePx).coerceAtLeast(0)
        maxY = (screenHeight - windowSizePx).coerceAtLeast(0)
    }

    /** 把形象加到屏幕上（首次启动，或双击藏起来后恢复） */
    private fun showPet() {
        if (petContainer != null) return

        petSizePx = dpToPx(cloudSizeDp)
        windowSizePx = petSizePx + dpToPx(PET_WINDOW_PADDING_DP) * 2
        updateScreenBounds()

        params = WindowManager.LayoutParams(
            windowSizePx,
            windowSizePx,
            overlayWindowType(),
            // 不抢焦点（否则会挡住输入法/按键），并让坐标从屏幕左上角算起
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 恢复时回到双击前的位置，首次启动则贴右上角
            x = if (lastX >= 0) lastX.coerceIn(0, maxX) else maxX
            y = if (lastY >= 0) lastY.coerceIn(0, maxY) else dpToPx(96f)
        }

        // 外层容器只负责给浮动动画留 padding，形象本身仍然是 80×80dp
        val container = FrameLayout(this)
        val image = ImageView(this).apply {
            setImageResource(R.drawable.pet_baiyun)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.pet_content_description)
            setOnTouchListener(touchListener)
        }
        container.addView(
            image,
            FrameLayout.LayoutParams(petSizePx, petSizePx, Gravity.CENTER)
        )

        try {
            windowManager.addView(container, params)
            petContainer = container
            petView = image
            isPetHidden = false
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗添加失败", e)
            suppressRestart = true
            stopSelf()
        }
    }

    /** 手指拖动 + 单击/双击识别 */
    private val touchListener = View.OnTouchListener { _, event ->
        // 先交给手势识别（单击 / 双击），拖动逻辑自己算
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 正在吸附时按下，先停下动画，从当前位置继续拖
                cancelSnap()
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                isDragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // 超过系统触摸阈值才算拖动，避免手抖被判定为拖动
                if (!isDragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                    isDragging = true
                    // 拖动时先把气泡收掉，免得两个窗口互相挡
                    closeBubble()
                }
                if (isDragging) {
                    moveTo(startX + dx, startY + dy)
                    applyTilt(dx)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    snapToNearestEdge()
                }
                resetTilt()
                true
            }

            else -> false
        }
    }

    /** 移动桌宠窗口（自动限制在屏幕内） */
    private fun moveTo(x: Float, y: Float) {
        val container = petContainer ?: return
        params.x = x.roundToInt().coerceIn(0, maxX)
        params.y = y.roundToInt().coerceIn(0, maxY)
        safeUpdateLayout(container, params)
    }

    /** 吸附：离哪边近就贴哪边，用 ValueAnimator 做缓动动画 */
    private fun snapToNearestEdge() {
        val container = petContainer ?: return
        val targetX = nearestEdgeX()
        val fromX = params.x
        if (fromX == targetX) return

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator(1.8f) // 先快后慢，贴上去很自然
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                safeUpdateLayout(container, params)
            }
            start()
        }
    }

    private fun nearestEdgeX(): Int {
        val center = params.x + windowSizePx / 2
        return if (center < screenWidth / 2) {
            dpToPx(EDGE_MARGIN_DP)
        } else {
            maxX - dpToPx(EDGE_MARGIN_DP)
        }
    }

    private fun cancelSnap() {
        snapAnimator?.cancel()
        snapAnimator = null
    }

    /** 拖动时让形象轻微歪头，跟手的感觉更好 */
    private fun applyTilt(totalDx: Float) {
        val view = petView ?: return
        view.rotation = (totalDx * 0.06f).coerceIn(-12f, 12f)
    }

    /** 松手后角度回正 */
    private fun resetTilt() {
        val view = petView ?: return
        tiltAnimator?.cancel()
        tiltAnimator = ValueAnimator.ofFloat(view.rotation, 0f).apply {
            duration = 200L
            addUpdateListener { animation ->
                view.rotation = animation.animatedValue as Float
            }
            start()
        }
    }

    /**
     * 双击：把形象藏起来。
     * 位置记在 lastX/lastY，通知栏点一下（ACTION_SHOW）就从原位回来。
     */
    private fun hidePet() {
        closeBubble()
        stopFloating()
        lastX = params.x
        lastY = params.y

        petContainer?.let { removeViewSafely(it) }
        petContainer = null
        petView = null
        isPetHidden = true
        updateNotification()
    }

    /** 移除窗口：View 可能已经被移除 / 窗口已经销毁，统一兜底 */
    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "移除视图失败", e)
        }
    }

    /** updateViewLayout 在极端情况下（View 已被移除）会抛异常，这里统一兜底 */
    private fun safeUpdateLayout(view: View, layoutParams: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "更新悬浮窗位置失败", e)
        }
    }

    // ---------------------------------------------------------------------
    // 聊天气泡
    // ---------------------------------------------------------------------

    /** 单击形象：开 / 关气泡 */
    private fun toggleBubble() {
        if (isBubbleOpen) closeBubble() else openBubble()
    }

    /**
     * 打开气泡：
     * 1. 形象开始上下浮动（ObjectAnimator 无限循环）；
     * 2. 根据形象在左半屏还是右半屏，决定气泡往右弹还是往左弹；
     * 3. 缩放动画 scaleX/scaleY 0 → 1，以靠近形象的那个角为轴。
     */
    private fun openBubble() {
        if (isBubbleOpen || petContainer == null) return
        // 上一个气泡可能还在收拢动画里，先强制清掉
        bubbleView?.let { removeViewSafely(it) }
        bubbleView = null
        isBubbleOpen = true
        startFloating()

        // 打开时按设置里的不透明度来
        bubbleAlphaPercent = SettingsStore.bubbleAlpha(this)

        val view = inflater.inflate(R.layout.chat_bubble_layout, null)
        view.visibility = View.VISIBLE
        view.alpha = bubbleAlphaPercent / 100f
        // 加进窗口前先缩到 0，否则第一帧会以原始尺寸闪一下
        view.scaleX = 0f
        view.scaleY = 0f
        view.setOnTouchListener { _, _ ->
            // 用户碰了气泡，就别自动收了
            cancelAutoHide()
            false
        }
        bindBubbleViews(view)

        val widthPx = dpToPx(BUBBLE_WIDTH_DP)
        val gapPx = dpToPx(BUBBLE_GAP_DP)
        val paddingPx = dpToPx(PET_WINDOW_PADDING_DP)
        val petLeft = params.x + paddingPx

        // 形象偏右 → 气泡从左边弹；偏左 → 从右边弹
        val onLeftSide = petLeft + petSizePx / 2 > screenWidth / 2
        var x = if (onLeftSide) {
            petLeft - gapPx - widthPx
        } else {
            petLeft + petSizePx + gapPx
        }
        x = x.coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0))

        val layoutParams = WindowManager.LayoutParams(
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // 不抢外部触摸（点气泡以外的地方照常操作手机），但窗口本身可获取焦点以便输入法工作
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            y = params.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        // 返回键收起气泡（根布局是可聚焦的，能收到按键）
        view.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == KeyEvent.KEYCODE_BACK && keyEvent.action == KeyEvent.ACTION_UP) {
                closeBubble()
                true
            } else {
                false
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            bubbleView = view
        } catch (e: Exception) {
            Log.e(TAG, "气泡添加失败", e)
            isBubbleOpen = false
            stopFloating()
            return
        }

        // 布局完成后才知道气泡真实高度：把底边对齐形象，再播缩放动画
        view.post {
            val height = view.height
            if (height > 0) {
                layoutParams.y = (params.y + windowSizePx - height)
                    .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
                safeUpdateLayout(view, layoutParams)
            }
            // 以靠近形象的那个角为缩放轴心
            view.pivotX = if (onLeftSide) view.width.toFloat() else 0f
            view.pivotY = view.height.toFloat()
            view.scaleX = 0f
            view.scaleY = 0f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    /** 关闭气泡（带 120ms 收拢动画，动画结束后才真正移除窗口） */
    private fun closeBubble() {
        if (!isBubbleOpen) return
        cancelAutoHide()
        isBubbleOpen = false
        stopFloating()
        finishTyping()

        val view = bubbleView ?: return
        rvMessages = null
        etMessage = null
        chatAdapter = null
        btnSend = null

        hideIme(view)
        view.animate()
            .scaleX(0f)
            .scaleY(0f)
            .setDuration(120L)
            .withEndAction {
                // 动画结束后才真正移除窗口，视觉上像气泡缩回去
                removeViewSafely(view)
                if (bubbleView === view) bubbleView = null
            }
            .start()
    }

    /** 绑定气泡里的 RecyclerView / 输入框 / 发送按钮 */
    private fun bindBubbleViews(root: View) {
        val rv = root.findViewById<MaxHeightRecyclerView>(R.id.rvMessages)
        val editText = root.findViewById<EditText>(R.id.etMessage)
        val sendButton = root.findViewById<ImageButton>(R.id.btnSend)

        rv.maxHeightPx = dpToPx(BUBBLE_MAX_HEIGHT_DP)
        rv.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages)
        rv.adapter = chatAdapter
        rv.itemAnimator = null // 关掉默认动画，连续发消息时不会乱跳

        // 键盘上的「发送」键
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
        sendButton.setOnClickListener { sendMessage() }

        btnSend = sendButton
        // 气泡是关掉重开的，如果上一轮还在等回复，按钮要继续保持置灰
        setSendEnabled(!isRequesting && !isTyping)

        if (messages.isNotEmpty()) {
            rv.post { rv.scrollToPosition(messages.size - 1) }
        }

        rvMessages = rv
        etMessage = editText
    }

    /**
     * 发送：用户消息立刻上屏，然后交给 [DeepSeekChatManager]。
     *
     * 等网络 + 打字期间按钮置灰（isRequesting / isTyping），
     * 从根上避免连点造成并发请求和消息错位。
     */
    private fun sendMessage() {
        val editText = etMessage ?: return
        if (isRequesting || isTyping) return

        val text = editText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        editText.setText("")
        appendMessage(Message(text, isUser = true))
        cancelAutoHide()
        ProactiveChatManager.instance.notifyUserMessage()

        isRequesting = true
        setSendEnabled(false)

        DeepSeekChatManager.sendMessage(text, object : DeepSeekChatManager.Callback {

            override fun onReply(text: String) {
                // 管理器已经切回主线程，这里可以直接动 UI
                isRequesting = false
                typeOutReply(text)
            }

            override fun onError(msg: String) {
                isRequesting = false
                Log.w(TAG, "DeepSeek 请求失败：$msg")
                if (msg.contains("401") || msg.contains("API Key")) {
                    // Key 没填 / 填错时给个明确提示，免得误以为是自己网络差
                    Toast.makeText(
                        this@FloatingPetService,
                        getString(R.string.chat_error_auth),
                        Toast.LENGTH_LONG
                    ).show()
                }
                // 需求：出错就回这一句
                appendMessage(Message(getString(R.string.chat_error_fallback), isUser = false))
                setSendEnabled(true)
            }
        })
    }

    /**
     * 打字机效果：每 30ms 显示一个字。
     * 用 codePoint 切片，emoji（代理对）不会被拆成两个乱码字符。
     */
    private fun typeOutReply(fullText: String) {
        finishTyping()

        if (!isBubbleOpen) {
            // 气泡已关闭：直接存完整回复，下次打开就能看到
            messages.add(Message(fullText, isUser = false))
            return
        }

        typingFullText = fullText
        appendMessage(Message("", isUser = false)) // 先占位，逐字填内容
        typingIndex = messages.size - 1
        isTyping = true
        setSendEnabled(false)

        val shown = StringBuilder()
        var cursor = 0
        val runnable = object : Runnable {
            override fun run() {
                if (cursor >= typingFullText.length) {
                    finishTyping()
                    return
                }
                val codePoint = typingFullText.codePointAt(cursor)
                val charCount = if (codePoint > 0xFFFF) 2 else 1 // emoji 占两个 char
                shown.append(typingFullText, cursor, cursor + charCount)
                cursor += charCount

                messages[typingIndex] = Message(shown.toString(), isUser = false)
                val holder = rvMessages?.findViewHolderForAdapterPosition(typingIndex)
                    as? ChatAdapter.MessageViewHolder
                if (holder != null) {
                    // 正在屏幕上：直接改 TextView，比 notifyItemChanged 轻得多
                    holder.content.text = shown.toString()
                } else {
                    chatAdapter?.notifyItemChanged(typingIndex)
                }
                if (shown.length % 6 == 0) {
                    rvMessages?.scrollToPosition(typingIndex)
                }
                mainHandler.postDelayed(this, TYPING_INTERVAL_MS)
            }
        }
        typingRunnable = runnable
        mainHandler.postDelayed(runnable, TYPING_INTERVAL_MS)
    }

    /** 立刻结束打字（气泡关闭 / 服务销毁时用），避免回调还在跑 */
    private fun finishTyping() {
        typingRunnable?.let { mainHandler.removeCallbacks(it) }
        typingRunnable = null
        if (!isTyping) return

        isTyping = false
        if (typingIndex in messages.indices) {
            messages[typingIndex] = Message(typingFullText, isUser = false)
            val holder = rvMessages?.findViewHolderForAdapterPosition(typingIndex)
                as? ChatAdapter.MessageViewHolder
            if (holder != null) {
                holder.content.text = typingFullText
            } else {
                chatAdapter?.notifyItemChanged(typingIndex)
            }
            rvMessages?.scrollToPosition(typingIndex)
        }
        setSendEnabled(true)
    }

    /** 发送按钮置灰 / 恢复 */
    private fun setSendEnabled(enabled: Boolean) {
        val button = btnSend ?: return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    /** 往列表里加一条消息并滚到底部 */
    private fun appendMessage(message: Message) {
        messages.add(message)
        chatAdapter?.notifyItemInserted(messages.size - 1)
        rvMessages?.post {
            rvMessages?.smoothScrollToPosition(messages.size - 1)
            // 列表变高之后把气泡底边重新对齐形象，让它往上长而不是溢出屏幕
            repositionBubble()
        }
    }

    /** 气泡高度变化后重新贴住形象（底边对齐） */
    private fun repositionBubble() {
        val view = bubbleView ?: return
        val height = view.height
        if (height <= 0) return
        val layoutParams = view.layoutParams as? WindowManager.LayoutParams ?: return
        layoutParams.y = (params.y + windowSizePx - height)
            .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        safeUpdateLayout(view, layoutParams)
    }

    /** 气泡打开期间，形象轻轻上下浮动 */
    private fun startFloating() {
        val view = petView ?: return
        floatAnimator?.cancel()
        floatAnimator = ObjectAnimator.ofFloat(view, "translationY", 0f, -dpToPx(5f).toFloat(), 0f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** 气泡关闭：停止浮动并把形象放回原位 */
    private fun stopFloating() {
        floatAnimator?.cancel()
        floatAnimator = null
        petView?.translationY = 0f
    }

    private fun hideIme(view: View) {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (e: Exception) {
            Log.w(TAG, "收起输入法失败", e)
        }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).roundToInt()
}
