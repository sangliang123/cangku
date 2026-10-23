package com.baiyun.pet

import com.baiyun.pet.AppNameMapper.Category

/**
 * 屏幕摘要（单例，纯内存）。
 *
 * 只合并两个来源，不截屏、不 OCR、不调视觉：
 * 1. UsageStats（[ScreenDetector] → 服务写入）→ 当前前台 App 包名 + 会话时长；
 * 2. 无障碍服务最近一次收集到的界面文字（[BaiyunAccessibilityService]）。
 *
 * 输出是给模型看的结构化字符串，例如：
 *   app=原神 | 时长=47min | 界面文字=挑战失败,角色血量12%,剩余时间2:34
 *   app=未知 | 时长=5min | 界面文字=无
 *
 * 摘要缓存 30 秒；不保存原文、不写文件、不做历史。
 */
object ScreenContextBuilder {

    private const val CACHE_TTL_MS = 30_000L
    private const val MAX_TEXTS = 5
    private const val MAX_TEXT_LENGTH = 30

    private val AD_WORDS = listOf("广告", "赞助", "推广", "Sponsored")

    @Volatile
    private var packageName: String? = null

    @Volatile
    private var appName: String? = null

    @Volatile
    private var category: Category = Category.OTHER

    /** 当前 App 会话开始时间 */
    @Volatile
    private var sessionStartAt: Long = 0L

    /** 无障碍最近一次收集到的文字（只保留最近一屏） */
    @Volatile
    private var screenTexts: List<String> = emptyList()

    private var cachedSummary: String? = null
    private var cachedAt = 0L
    private var cachedWithTexts = true

    /** 前台 App 变化时调用；换 App 会清掉上一屏的文字 */
    @Synchronized
    fun updateForeground(
        packageName: String,
        appName: String,
        category: Category,
        changedAt: Long
    ) {
        val changed = packageName != this.packageName
        this.packageName = packageName
        this.appName = appName
        this.category = category
        this.sessionStartAt = changedAt
        if (changed) {
            screenTexts = emptyList()
        }
        invalidate()
    }

    /** 无障碍服务每次解析完调用，只留最近一屏 */
    @Synchronized
    fun updateAccessibility(texts: List<String>) {
        screenTexts = texts
        invalidate()
    }

    /**
     * 结构化摘要。始终返回字符串（没有 App 时 app=未知）。
     *
     * @param includeScreenText 设置里关掉"允许读界面文字"时传 false，只给 App 名
     */
    @Synchronized
    fun buildSummary(includeScreenText: Boolean = true): String {
        val now = System.currentTimeMillis()
        val cached = cachedSummary
        if (cached != null && cachedWithTexts == includeScreenText &&
            now - cachedAt < CACHE_TTL_MS
        ) {
            return cached
        }

        val minutes = if (sessionStartAt > 0) {
            ((now - sessionStartAt) / 60_000L).toInt().coerceAtLeast(0)
        } else {
            0
        }

        val name = appName ?: "未知"
        val texts = if (includeScreenText) meaningfulTexts() else emptyList()
        val textPart = if (texts.isEmpty()) "无" else texts.joinToString(",")

        val summary = "app=$name | 时长=${minutes}min | 界面文字=$textPart"
        cachedSummary = summary
        cachedAt = now
        cachedWithTexts = includeScreenText
        return summary
    }

    /**
     * 有意义的摘要：知道 App 名，或者有界面文字。
     * 都没有返回 null，主动搭话就降级到固定话术池。
     */
    @Synchronized
    fun buildMeaningfulSummary(includeScreenText: Boolean = true): String? {
        val hasApp = appName != null
        val hasTexts = includeScreenText && meaningfulTexts().isNotEmpty()
        if (!hasApp && !hasTexts) return null
        return buildSummary(includeScreenText)
    }

    /** 给主动搭话做场景判断用 */
    @Synchronized
    fun snapshot(): Snapshot {
        val minutes = if (sessionStartAt > 0) {
            ((System.currentTimeMillis() - sessionStartAt) / 60_000L).toInt().coerceAtLeast(0)
        } else {
            0
        }
        return Snapshot(
            packageName = packageName,
            appName = appName,
            category = category,
            minutes = minutes,
            texts = meaningfulTexts()
        )
    }

    /** 前 5 条有意义的文字：过滤广告、纯数字、超长文本 */
    private fun meaningfulTexts(): List<String> =
        screenTexts.asSequence()
            .map { it.trim() }
            .filter { it.length in 2..MAX_TEXT_LENGTH }
            .filter { text -> text.any { it.isLetter() } }
            .filter { text -> AD_WORDS.none { text.contains(it, ignoreCase = true) } }
            .distinct()
            .take(MAX_TEXTS)
            .toList()

    private fun invalidate() {
        cachedSummary = null
    }

    data class Snapshot(
        val packageName: String?,
        val appName: String?,
        val category: Category,
        val minutes: Int,
        val texts: List<String>
    ) {
        val isMeaningful: Boolean get() = appName != null || texts.isNotEmpty()
    }
}
