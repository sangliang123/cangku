package com.baiyun.pet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

/**
 * 白云桌宠的主界面。
 *
 * 这里只做三件事：
 * 1. 检查 / 申请悬浮窗权限（SYSTEM_ALERT_WINDOW）—— 没开就跳系统设置页；
 * 2. 检查 / 申请通知权限（Android 13+ 的 POST_NOTIFICATIONS）；
 * 3. 启动 / 关闭 [FloatingPetService]。
 *
 * 桌宠本身的一切（悬浮窗、拖动、吸附）都在 Service 里，退出 App 也不会消失。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayState: TextView
    private lateinit var tvServiceState: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnPermission: MaterialButton
    private lateinit var btnSettings: MaterialButton

    /** 用户点了「开启桌宠」但权限不齐：授权回来之后自动继续启动 */
    private var pendingStart = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 从「悬浮窗权限」设置页返回时回调。
     * 注意：部分国产 ROM 在设置页里授权后不会带 result，所以 onResume 里也会补一次检查。
     */
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateUi()
            if (pendingStart && Settings.canDrawOverlays(this)) {
                pendingStart = false
                continueStart()
            }
        }

    /** 正在弹通知权限框，避免 onResume 的自检重复触发授权流程 */
    private var requestingPermission = false

    /** 通知权限结果：无论允许与否，桌宠都能跑（只是通知栏可能看不到） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestingPermission = false
            startPetService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlayState = findViewById(R.id.tvOverlayState)
        tvServiceState = findViewById(R.id.tvServiceState)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnPermission = findViewById(R.id.btnPermission)
        btnSettings = findViewById(R.id.btnSettings)

        btnStart.setOnClickListener { requestPermissionsThenStart() }
        btnStop.setOnClickListener { stopPetService() }
        btnPermission.setOnClickListener { openOverlaySettings() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页回来，用户可能刚刚打开了悬浮窗权限
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false
            continueStart()
        }
        // 权限有了、用户没关、服务却没跑（被系统清了 / 刚更新过）→ 自动拉起来
        autoStartPetIfNeeded()
        updateUi()
        maybeShowWhitelistGuide()
        maybeShowUsageAccessGuide()
        maybeShowAccessibilityGuide()
    }

    /**
     * 启动自检。
     * 注意要先看 isPetEnabled：用户点过「关闭桌宠」的话，回到前台也不能偷偷拉起来。
     */
    private fun autoStartPetIfNeeded() {
        if (!Settings.canDrawOverlays(this)) return
        if (!SettingsStore.isPetEnabled(this)) return
        if (FloatingPetService.isRunning) return
        // 正在走权限流程就别插队，否则会连弹两次授权框
        if (requestingPermission) return
        startPetService()
    }

    /** 第一次开启桌宠后，按品牌提示一次后台白名单（FLAG_FIRST_TIME 保证只弹一次） */
    private fun maybeShowWhitelistGuide() {
        if (!Settings.canDrawOverlays(this)) return
        mainHandler.postDelayed({
            // 别和系统权限弹窗抢，让位一次，下次 onResume 再提示
            if (!isFinishing && !isDestroyed && !requestingPermission) {
                ManufacturerHelper.showGuideIfNeeded(this)
            }
        }, 500L)
    }

    /**
     * 引导「使用情况访问」权限（只弹一次）。
     * 没有它白云就不知道你切到了哪个 App，但只读包名，不截屏、不读内容。
     */
    private fun maybeShowUsageAccessGuide() {
        if (!Settings.canDrawOverlays(this)) return
        if (ScreenDetector.hasUsageAccess(this)) return
        if (!SettingsStore.shouldShowUsageGuide(this)) return

        mainHandler.postDelayed({
            if (isFinishing || isDestroyed || requestingPermission) return@postDelayed
            // 用户可能已经在系统设置里开好了
            if (ScreenDetector.hasUsageAccess(this)) return@postDelayed
            SettingsStore.markUsageGuideShown(this)

            AlertDialog.Builder(this)
                .setTitle(R.string.usage_title)
                .setMessage(R.string.usage_message)
                .setPositiveButton(R.string.usage_go) { _, _ -> openUsageAccessSettings() }
                .setNegativeButton(R.string.usage_later, null)
                .show()
        }, 900L)
    }

    /**
     * 引导开启无障碍服务（只弹一次）。
     * 如果「使用情况访问」的引导还没处理，就先让位，避免两个对话框叠在一起。
     */
    private fun maybeShowAccessibilityGuide() {
        if (!Settings.canDrawOverlays(this)) return
        if (BaiyunAccessibilityService.isEnabled(this)) return
        if (!SettingsStore.shouldShowAccessibilityGuide(this)) return
        if (!ScreenDetector.hasUsageAccess(this) && SettingsStore.shouldShowUsageGuide(this)) return

        mainHandler.postDelayed({
            if (isFinishing || isDestroyed || requestingPermission) return@postDelayed
            if (BaiyunAccessibilityService.isEnabled(this)) return@postDelayed
            SettingsStore.markAccessibilityGuideShown(this)

            AlertDialog.Builder(this)
                .setTitle(R.string.a11y_title)
                .setMessage(R.string.a11y_message)
                .setPositiveButton(R.string.a11y_go) { _, _ -> openAccessibilitySettings() }
                .setNegativeButton(R.string.a11y_later, null)
                .show()
        }, 1_500L)
    }

    /** 跳到系统的无障碍服务列表页 */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.a11y_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 跳到系统的「使用情况访问」列表页，让用户给白云授权 */
    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.usage_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------
    // 启动流程
    // ---------------------------------------------------------------------

    /** 第一步：悬浮窗权限 → 第二步：通知权限 → 第三步：启动服务 */
    private fun requestPermissionsThenStart() {
        // 服务已经在跑：多半是双击藏起来了，直接叫回来，不必再走一遍权限流程
        if (FloatingPetService.isRunning) {
            startPetService()
            return
        }
        pendingStart = true
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        continueStart()
    }

    private fun continueStart() {
        // Android 13+ 才有通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestingPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startPetService()
    }

    private fun startPetService() {
        if (!Settings.canDrawOverlays(this)) {
            // 极端情况：权限在流程中间被关掉了
            pendingStart = true
            openOverlaySettings()
            return
        }
        if (FloatingPetService.isRunning) {
            // 服务在跑，但可能被双击藏起来了：发个指令把她叫回来
            startService(
                Intent(this, FloatingPetService::class.java)
                    .setAction(FloatingPetService.ACTION_SHOW)
            )
            mainHandler.postDelayed({ updateUi() }, 300)
            return
        }

        // 记下「用户希望桌宠运行」，开机自启 / 自愈重启都靠它判断
        SettingsStore.setPetEnabled(this, true)

        val intent = Intent(this, FloatingPetService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Toast.makeText(this, "桌宠启动失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 服务是异步起来的，稍等一下再刷新状态
        mainHandler.postDelayed({ updateUi() }, 400)
    }

    private fun stopPetService() {
        // 先记「用户主动关闭」，否则服务 onDestroy 里的自愈逻辑会立刻把她拉回来
        SettingsStore.setPetEnabled(this, false)
        stopService(Intent(this, FloatingPetService::class.java))
        mainHandler.postDelayed({ updateUi() }, 300)
    }

    /** 跳转到本应用的悬浮窗权限设置页 */
    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            // 极少数 ROM 不支持带 package 的跳转，退回总列表页
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    // ---------------------------------------------------------------------
    // 界面刷新
    // ---------------------------------------------------------------------

    private fun updateUi() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val running = FloatingPetService.isRunning
        val hidden = running && FloatingPetService.isPetHidden

        tvOverlayState.text = getString(
            if (hasOverlayPermission) R.string.state_overlay_on else R.string.state_overlay_off
        )
        tvServiceState.text = getString(
            when {
                !running -> R.string.state_service_off
                hidden -> R.string.state_service_hidden
                else -> R.string.state_service_on
            }
        )

        // 没授权时把「去授权」按钮亮出来
        btnPermission.isVisible = !hasOverlayPermission
        // 被双击藏起来之后，这个按钮变成「叫白云出来」
        btnStart.isEnabled = !running || hidden
        btnStart.setText(if (hidden) R.string.btn_show else R.string.btn_start)
        btnStop.isEnabled = running

        // 还没填 Key 时，按钮上直接提示，省得进去才发现聊不了
        btnSettings.setText(
            if (SettingsStore.apiKey(this).isEmpty()) R.string.btn_settings_missing_key
            else R.string.btn_settings
        )
    }
}
