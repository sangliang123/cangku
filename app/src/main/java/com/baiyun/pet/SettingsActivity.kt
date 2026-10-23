package com.baiyun.pet

import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

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
