package com.baiyun.pet

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置项的集中存取（SharedPreferences）。
 *
 * 只有这一处定义 key 和取值范围，设置页、服务、聊天管理器都从这里读，
 * 避免三个地方各写一份字符串 key 导致「改了没生效」。
 */
object SettingsStore {

    private const val PREFS_NAME = "baiyun_settings"

    /** DeepSeek API Key（设置页填写） */
    const val KEY_API_KEY = "api_key"

    /** 云朵大小，单位 dp */
    const val KEY_CLOUD_SIZE = "cloud_size_dp"

    /** 气泡不透明度，单位 %（50~100） */
    const val KEY_BUBBLE_ALPHA = "bubble_alpha_percent"

    /** 用户是否开启着桌宠：开机自启 / 自愈重启都看它 */
    const val KEY_PET_ENABLED = "pet_enabled"

    /** 厂商白名单引导是否弹过（FLAG_FIRST_TIME，只弹一次） */
    const val KEY_FIRST_TIME = "flag_first_time_guide_shown"

    /** 「使用情况访问」权限引导是否弹过 */
    const val KEY_USAGE_GUIDE = "flag_usage_access_guide_shown"

    /** 无障碍服务引导是否弹过 */
    const val KEY_A11Y_GUIDE = "flag_accessibility_guide_shown"

    // ---------------- 截屏感知（可选模块） ----------------
    const val KEY_CAPTURE_ENABLED = "capture_enabled"
    const val KEY_CAPTURE_MODE = "capture_mode"
    const val KEY_CAPTURE_INTERVAL = "capture_interval_sec"
    const val KEY_CAPTURE_PRIVACY_SHOWN = "flag_capture_privacy_shown"

    const val CAPTURE_MODE_LOCAL = "local"
    const val CAPTURE_MODE_CLOUD = "cloud"

    const val DEFAULT_CAPTURE_INTERVAL_SEC = 5

    // ---------------- 主动搭话 ----------------
    const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
    const val KEY_PROACTIVE_FREQUENCY = "proactive_frequency"
    const val KEY_PROACTIVE_AUTO_HIDE = "proactive_auto_hide"
    const val KEY_PROACTIVE_USE_SCREEN_TEXT = "proactive_use_screen_text"

    const val FREQ_QUIET = "quiet"
    const val FREQ_NORMAL = "normal"
    const val FREQ_CHATTY = "chatty"

    // 云朵大小范围（dp）
    const val MIN_CLOUD_SIZE = 60
    const val MAX_CLOUD_SIZE = 120
    const val DEFAULT_CLOUD_SIZE = 80

    // 气泡不透明度范围（%）
    const val MIN_BUBBLE_ALPHA = 50
    const val MAX_BUBBLE_ALPHA = 100
    const val DEFAULT_BUBBLE_ALPHA = 100

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读 API Key，没填返回空串 */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty().trim()

    /** 读云朵大小（dp），越界自动修正 */
    fun cloudSize(context: Context): Int =
        prefs(context).getInt(KEY_CLOUD_SIZE, DEFAULT_CLOUD_SIZE)
            .coerceIn(MIN_CLOUD_SIZE, MAX_CLOUD_SIZE)

    /** 读气泡不透明度（%），越界自动修正 */
    fun bubbleAlpha(context: Context): Int =
        prefs(context).getInt(KEY_BUBBLE_ALPHA, DEFAULT_BUBBLE_ALPHA)
            .coerceIn(MIN_BUBBLE_ALPHA, MAX_BUBBLE_ALPHA)

    /** 桌宠是否「应该运行」——用户点关闭就置 false */
    fun isPetEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PET_ENABLED, true)

    fun setPetEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PET_ENABLED, enabled).apply()
    }

    /** 白名单引导是否还没弹过 */
    fun shouldShowFirstTimeGuide(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_FIRST_TIME, false)

    fun markFirstTimeGuideShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_TIME, true).apply()
    }

    /** 使用情况访问引导是否还没弹过 */
    fun shouldShowUsageGuide(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_USAGE_GUIDE, false)

    fun markUsageGuideShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_USAGE_GUIDE, true).apply()
    }

    /** 无障碍引导是否还没弹过 */
    fun shouldShowAccessibilityGuide(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_A11Y_GUIDE, false)

    fun markAccessibilityGuideShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_A11Y_GUIDE, true).apply()
    }

    // ---------------- 截屏感知 ----------------

    fun captureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAPTURE_ENABLED, false)

    fun setCaptureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
    }

    fun captureMode(context: Context): String =
        prefs(context).getString(KEY_CAPTURE_MODE, CAPTURE_MODE_LOCAL) ?: CAPTURE_MODE_LOCAL

    fun setCaptureMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_CAPTURE_MODE, mode).apply()
    }

    fun captureIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_CAPTURE_INTERVAL, DEFAULT_CAPTURE_INTERVAL_SEC).coerceIn(3, 10)

    fun setCaptureIntervalSec(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_CAPTURE_INTERVAL, seconds.coerceIn(3, 10)).apply()
    }

    fun captureIntervalMs(context: Context): Long = captureIntervalSec(context) * 1000L

    fun shouldShowCapturePrivacy(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_CAPTURE_PRIVACY_SHOWN, false)

    fun markCapturePrivacyShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_CAPTURE_PRIVACY_SHOWN, true).apply()
    }

    // ---------------- 主动搭话 ----------------

    fun proactiveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROACTIVE_ENABLED, true)

    fun setProactiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }

    fun proactiveFrequency(context: Context): String =
        prefs(context).getString(KEY_PROACTIVE_FREQUENCY, FREQ_NORMAL) ?: FREQ_NORMAL

    fun setProactiveFrequency(context: Context, frequency: String) {
        prefs(context).edit().putString(KEY_PROACTIVE_FREQUENCY, frequency).apply()
    }

    /** 主动搭话后是否自动收起气泡 */
    fun proactiveAutoHide(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROACTIVE_AUTO_HIDE, true)

    fun setProactiveAutoHide(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROACTIVE_AUTO_HIDE, hide).apply()
    }

    /** 是否允许用界面文字（关掉只用 App 名） */
    fun proactiveUseScreenText(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROACTIVE_USE_SCREEN_TEXT, true)

    fun setProactiveUseScreenText(context: Context, use: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROACTIVE_USE_SCREEN_TEXT, use).apply()
    }

    /** 一次性写入三项设置 */
    fun save(context: Context, apiKey: String, cloudSize: Int, bubbleAlpha: Int) {
        prefs(context).edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putInt(KEY_CLOUD_SIZE, cloudSize.coerceIn(MIN_CLOUD_SIZE, MAX_CLOUD_SIZE))
            .putInt(KEY_BUBBLE_ALPHA, bubbleAlpha.coerceIn(MIN_BUBBLE_ALPHA, MAX_BUBBLE_ALPHA))
            .apply()
    }
}
