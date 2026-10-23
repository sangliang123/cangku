package com.baiyun.pet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.RadioButton
import android.widget.RadioGroup

/**
 * 设置页。
 *
 * 三件事：
 * 1. 填 / 改 DeepSeek API Key（密码模式显示 ●●●●），支持「测试连接」；
 * 2. 调云朵大小（60~120dp）和气泡不透明度（50%~100%），拖动时实时预览；
 * 3. 保存到 SharedPreferences 后自动返回主界面。
 *
 * 保存的一瞬间，正在运行的 FloatingPetService 会收到 SharedPreferences 变更回调，
 * 立刻重建悬浮窗 / 调整气泡透明度，不需要重启服务。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var seekCloudSize: SeekBar
    private lateinit var seekBubbleAlpha: SeekBar
    private lateinit var tvCloudSizeValue: TextView
    private lateinit var tvBubbleAlphaValue: TextView
    private lateinit var preview: TextView
    private lateinit var btnTest: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnUsageAccess: MaterialButton
    private lateinit var btnAccessibility: MaterialButton

    // 截屏感知
    private lateinit var switchCapture: MaterialSwitch
    private lateinit var radioGroupMode: RadioGroup
    private lateinit var radioLocal: RadioButton
    private lateinit var radioCloud: RadioButton
    private lateinit var radioGroupInterval: RadioGroup
    private lateinit var radio3: RadioButton
    private lateinit var radio5: RadioButton
    private lateinit var radio10: RadioButton

    // 主动搭话
    private lateinit var switchProactive: MaterialSwitch
    private lateinit var switchAutoHide: MaterialSwitch
    private lateinit var switchScreenText: MaterialSwitch
    private lateinit var radioGroupProactive: RadioGroup
    private lateinit var radioQuiet: RadioButton
    private lateinit var radioNormal: RadioButton
    private lateinit var radioChatty: RadioButton

    /** 防连点：测试请求还没回来时禁用按钮 */
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etApiKey = findViewById(R.id.etApiKey)
        seekCloudSize = findViewById(R.id.seekCloudSize)
        seekBubbleAlpha = findViewById(R.id.seekBubbleAlpha)
        tvCloudSizeValue = findViewById(R.id.tvCloudSizeValue)
        tvBubbleAlphaValue = findViewById(R.id.tvBubbleAlphaValue)
        preview = findViewById(R.id.viewBubblePreview)
        btnTest = findViewById(R.id.btnTest)
        btnSave = findViewById(R.id.btnSave)
        btnUsageAccess = findViewById(R.id.btnUsageAccess)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        switchCapture = findViewById(R.id.switchCapture)
        radioGroupMode = findViewById(R.id.radioGroupMode)
        radioLocal = findViewById(R.id.radioLocal)
        radioCloud = findViewById(R.id.radioCloud)
        radioGroupInterval = findViewById(R.id.radioGroupInterval)
        radio3 = findViewById(R.id.radio3)
        radio5 = findViewById(R.id.radio5)
        radio10 = findViewById(R.id.radio10)
        switchProactive = findViewById(R.id.switchProactive)
        switchAutoHide = findViewById(R.id.switchAutoHide)
        switchScreenText = findViewById(R.id.switchScreenText)
        radioGroupProactive = findViewById(R.id.radioGroupProactive)
        radioQuiet = findViewById(R.id.radioQuiet)
        radioNormal = findViewById(R.id.radioNormal)
        radioChatty = findViewById(R.id.radioChatty)

        // 回显已保存的设置
        etApiKey.setText(SettingsStore.apiKey(this))
        seekCloudSize.progress = SettingsStore.cloudSize(this) - SettingsStore.MIN_CLOUD_SIZE
        seekBubbleAlpha.progress = SettingsStore.bubbleAlpha(this) - SettingsStore.MIN_BUBBLE_ALPHA

        seekCloudSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCloudSizeLabel()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        seekBubbleAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateBubbleAlphaLabel()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        // 初始化两个数值显示 + 预览
        updateCloudSizeLabel()
        updateBubbleAlphaLabel()

        btnUsageAccess.setOnClickListener { openUsageAccessSettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        setupCaptureSection()
        setupProactiveSection()
        btnTest.setOnClickListener { testConnection() }
        btnSave.setOnClickListener { saveAndFinish() }
    }

    // ---------------------------------------------------------------------
    // 滑块
    // ---------------------------------------------------------------------

    private fun currentCloudSize(): Int =
        SettingsStore.MIN_CLOUD_SIZE + seekCloudSize.progress

    private fun currentBubbleAlpha(): Int =
        SettingsStore.MIN_BUBBLE_ALPHA + seekBubbleAlpha.progress

    private fun updateCloudSizeLabel() {
        tvCloudSizeValue.text = getString(R.string.settings_size_value, currentCloudSize())
    }

    private fun updateBubbleAlphaLabel() {
        val alpha = currentBubbleAlpha()
        tvBubbleAlphaValue.text = getString(R.string.settings_alpha_value, alpha)
        // 预览跟着滑块变，所见即所得
        preview.alpha = alpha / 100f
    }

    // ---------------------------------------------------------------------
    // 主动搭话
    // ---------------------------------------------------------------------

    private fun setupProactiveSection() {
        switchProactive.isChecked = SettingsStore.proactiveEnabled(this)
        switchAutoHide.isChecked = SettingsStore.proactiveAutoHide(this)
        switchScreenText.isChecked = SettingsStore.proactiveUseScreenText(this)

        when (SettingsStore.proactiveFrequency(this)) {
            SettingsStore.FREQ_QUIET -> radioQuiet.isChecked = true
            SettingsStore.FREQ_CHATTY -> radioChatty.isChecked = true
            else -> radioNormal.isChecked = true
        }

        switchProactive.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setProactiveEnabled(this, checked)
        }

        switchAutoHide.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setProactiveAutoHide(this, checked)
        }

        switchScreenText.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setProactiveUseScreenText(this, checked)
        }

        radioGroupProactive.setOnCheckedChangeListener { _, id ->
            val frequency = when (id) {
                R.id.radioQuiet -> SettingsStore.FREQ_QUIET
                R.id.radioChatty -> SettingsStore.FREQ_CHATTY
                else -> SettingsStore.FREQ_NORMAL
            }
            SettingsStore.setProactiveFrequency(this, frequency)
        }
    }

    // ---------------------------------------------------------------------
    // 截屏感知
    // ---------------------------------------------------------------------

    /** 录屏授权结果：OK 才把 resultData 转给桌宠服务去开 MediaProjection */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null &&
                FloatingPetService.isRunning
            ) {
                SettingsStore.setCaptureEnabled(this, true)
                startService(
                    Intent(this, FloatingPetService::class.java).apply {
                        action = FloatingPetService.ACTION_START_CAPTURE
                        putExtra(
                            FloatingPetService.EXTRA_CAPTURE_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(FloatingPetService.EXTRA_CAPTURE_DATA, data)
                    }
                )
                setCaptureSwitchSilently(true)
            } else {
                SettingsStore.setCaptureEnabled(this, false)
                setCaptureSwitchSilently(false)
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private fun setupCaptureSection() {
        switchCapture.isChecked =
            SettingsStore.captureEnabled(this) && FloatingPetService.isRunning

        if (SettingsStore.captureMode(this) == SettingsStore.CAPTURE_MODE_CLOUD) {
            radioCloud.isChecked = true
        } else {
            radioLocal.isChecked = true
        }

        when (SettingsStore.captureIntervalSec(this)) {
            3 -> radio3.isChecked = true
            10 -> radio10.isChecked = true
            else -> radio5.isChecked = true
        }

        switchCapture.setOnCheckedChangeListener { _, checked ->
            if (checked) requestCapturePermission() else disableCapture()
        }

        radioGroupMode.setOnCheckedChangeListener { _, id ->
            SettingsStore.setCaptureMode(
                this,
                if (id == R.id.radioCloud) SettingsStore.CAPTURE_MODE_CLOUD
                else SettingsStore.CAPTURE_MODE_LOCAL
            )
        }

        radioGroupInterval.setOnCheckedChangeListener { _, id ->
            val seconds = when (id) {
                R.id.radio3 -> 3
                R.id.radio10 -> 10
                else -> 5
            }
            SettingsStore.setCaptureIntervalSec(this, seconds)
        }
    }

    /** 打开开关：先弹隐私说明（首次），再申请录屏授权 */
    private fun requestCapturePermission() {
        if (!FloatingPetService.isRunning) {
            setCaptureSwitchSilently(false)
            Toast.makeText(this, R.string.capture_need_service, Toast.LENGTH_SHORT).show()
            return
        }

        if (SettingsStore.shouldShowCapturePrivacy(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.capture_privacy_title)
                .setMessage(R.string.capture_privacy_message)
                .setPositiveButton(R.string.capture_privacy_ok) { _, _ ->
                    SettingsStore.markCapturePrivacyShown(this)
                    launchProjection()
                }
                .setNegativeButton(R.string.capture_privacy_cancel) { _, _ ->
                    setCaptureSwitchSilently(false)
                }
                .setOnCancelListener { setCaptureSwitchSilently(false) }
                .show()
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        try {
            projectionLauncher.launch(ScreenCaptureManager.createRequestIntent(this))
        } catch (e: Exception) {
            setCaptureSwitchSilently(false)
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun disableCapture() {
        SettingsStore.setCaptureEnabled(this, false)
        if (FloatingPetService.isRunning) {
            startService(
                Intent(this, FloatingPetService::class.java)
                    .setAction(FloatingPetService.ACTION_STOP_CAPTURE)
            )
        }
    }

    /** 程序化改开关时不触发监听，避免递归 */
    private fun setCaptureSwitchSilently(checked: Boolean) {
        switchCapture.setOnCheckedChangeListener(null)
        switchCapture.isChecked = checked
        switchCapture.setOnCheckedChangeListener { _, c ->
            if (c) requestCapturePermission() else disableCapture()
        }
    }

    // ---------------------------------------------------------------------
    // 测试连接
    // ---------------------------------------------------------------------

    /**
     * 用输入框里当前的 Key 发一条「你好」，不写对话历史、不影响聊天上下文。
     * 成功 Toast 回复的前 10 个字，失败提示具体原因。
     */
    private fun testConnection() {
        if (testing) return

        val key = etApiKey.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.settings_key_empty, Toast.LENGTH_SHORT).show()
            return
        }

        testing = true
        setTestingUi(true)

        // 等价于 DeepSeekChatManager.sendMessage("你好")，
        // 区别只是用输入框里尚未保存的 Key，并且不写聊天历史
        DeepSeekChatManager.testConnection(key, object : DeepSeekChatManager.Callback {
            override fun onReply(text: String) {
                // 页面可能已经退出，避免拿已销毁的 Activity 弹 Toast
                if (isFinishing || isDestroyed) return
                testing = false
                setTestingUi(false)
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_test_ok, text.take(10)),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onError(msg: String) {
                if (isFinishing || isDestroyed) return
                testing = false
                setTestingUi(false)
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_test_fail, msg),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun setTestingUi(running: Boolean) {
        btnTest.isEnabled = !running
        btnTest.setText(if (running) R.string.settings_testing else R.string.settings_test)
    }

    /** 跳到系统的无障碍服务列表页 */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.a11y_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 跳到系统的「使用情况访问」列表页 */
    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.usage_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------
    // 保存
    // ---------------------------------------------------------------------

    private fun saveAndFinish() {
        SettingsStore.save(
            this,
            apiKey = etApiKey.text?.toString().orEmpty(),
            cloudSize = currentCloudSize(),
            bubbleAlpha = currentBubbleAlpha()
        )
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish() // 自动回到主界面
    }
}
