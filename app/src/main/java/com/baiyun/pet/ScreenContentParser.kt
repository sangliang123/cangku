package com.baiyun.pet

/**
 * 界面语义类型。
 *
 * NOVEL      小说阅读（"第 12 章"）
 * GAME_MATCH 游戏对局（胜利/失败/击杀/死亡/匹配）
 * DAILY      日常界面（签到/领取/奖励）
 * UPDATE     更新/维护弹窗
 * GENERIC    其它（只知道大致标题）
 */
enum class ScreenType {
    NOVEL,
    GAME_MATCH,
    DAILY,
    UPDATE,
    GENERIC
}

/**
 * 无障碍服务解析出来的「当前屏幕在干什么」。
 *
 * 这个对象会被 [FloatingPetService] 存起来（currentContext），
 * 以后接聊天时作为 prompt 的补充上下文，比如：
 * "用户正在看《第 12 章 风起云涌》，可以催他睡觉"。
 *
 * @param texts 收集到的文字（已去重、最多 20 条），留给后续能力用
 */
data class ScreenContext(
    val type: ScreenType,
    val packageName: String,
    val activityName: String?,
    val title: String?,
    val extra: String?,
    val texts: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 把无障碍服务收集到的一堆文字，按规则归类成 [ScreenContext]。
 *
 * 纯函数、无副作用，方便单测和后续加规则。
 * 规则有优先级：小说 > 游戏 > 日常 > 更新 > 其它。
 */
object ScreenContentParser {

    /** "第12章" / "第 十二 章" 都算 */
    private val CHAPTER_REGEX = Regex("第\\s*[0-9一二三四五六七八九十百千万零两]+\\s*章")

    private val GAME_KEYWORDS = listOf("胜利", "失败", "击杀", "死亡", "匹配")
    private val DAILY_KEYWORDS = listOf("签到", "领取", "奖励")
    private val UPDATE_KEYWORDS = listOf("更新", "版本", "维护")

    /** 单条标题最多这么长，太长的多半是正文/广告 */
    private const val MAX_TITLE_LENGTH = 40

    fun parse(packageName: String, activityName: String?, texts: List<String>): ScreenContext {
        val cleaned = texts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        // 1) 小说：出现"第 X 章"
        cleaned.firstOrNull { CHAPTER_REGEX.containsMatchIn(it) }?.let { line ->
            return ScreenContext(
                type = ScreenType.NOVEL,
                packageName = packageName,
                activityName = activityName,
                title = line.take(MAX_TITLE_LENGTH),
                extra = CHAPTER_REGEX.find(line)?.value,
                texts = cleaned
            )
        }

        // 2) 游戏对局
        findKeyword(cleaned, GAME_KEYWORDS)?.let { (line, keyword) ->
            return ScreenContext(
                type = ScreenType.GAME_MATCH,
                packageName = packageName,
                activityName = activityName,
                title = line.take(MAX_TITLE_LENGTH),
                extra = keyword,
                texts = cleaned
            )
        }

        // 3) 日常界面
        findKeyword(cleaned, DAILY_KEYWORDS)?.let { (line, keyword) ->
            return ScreenContext(
                type = ScreenType.DAILY,
                packageName = packageName,
                activityName = activityName,
                title = line.take(MAX_TITLE_LENGTH),
                extra = keyword,
                texts = cleaned
            )
        }

        // 4) 更新 / 维护
        findKeyword(cleaned, UPDATE_KEYWORDS)?.let { (line, keyword) ->
            return ScreenContext(
                type = ScreenType.UPDATE,
                packageName = packageName,
                activityName = activityName,
                title = line.take(MAX_TITLE_LENGTH),
                extra = keyword,
                texts = cleaned
            )
        }

        // 5) 兜底：拿第一条当标题
        return ScreenContext(
            type = ScreenType.GENERIC,
            packageName = packageName,
            activityName = activityName,
            title = cleaned.firstOrNull()?.take(MAX_TITLE_LENGTH),
            extra = null,
            texts = cleaned
        )
    }

    private fun findKeyword(texts: List<String>, keywords: List<String>): Pair<String, String>? {
        for (text in texts) {
            for (keyword in keywords) {
                if (text.contains(keyword)) return text to keyword
            }
        }
        return null
    }
}
