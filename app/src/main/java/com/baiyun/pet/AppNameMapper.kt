package com.baiyun.pet

/**
 * 包名 → 可读名称 + 分类。
 *
 * 为什么做映射：UsageStats 只给 `com.tencent.tmgp.sgame` 这种包名，
 * 直接展示给用户没法看，也没法判断是不是游戏。
 *
 * 扩展方式：在 init 里加一行，或者运行时调用 [register]。
 * 未收录的包名原样返回（displayName = packageName，分类 OTHER）。
 */
object AppNameMapper {

    /** 粗分类：目前只有 GAME / READING 会触发白云搭话 */
    enum class Category {
        GAME,
        READING,
        SOCIAL,
        VIDEO,
        NEWS,
        BROWSER,
        SHOPPING,
        OTHER
    }

    data class AppInfo(
        val packageName: String,
        val displayName: String,
        val category: Category
    )

    private val table = mutableMapOf<String, AppInfo>()

    init {
        // ---------------- 游戏 ----------------
        add("com.tencent.tmgp.sgame", "王者荣耀", Category.GAME)
        add("com.tencent.tmgp.pubgmhd", "和平精英", Category.GAME)
        add("com.tencent.lolm", "英雄联盟手游", Category.GAME)
        add("com.tencent.jkchess", "金铲铲之战", Category.GAME)
        add("com.tencent.tmgp.cf", "穿越火线", Category.GAME)
        add("com.tencent.tmgp.dnf", "地下城与勇士", Category.GAME)
        add("com.tencent.tmgp.speedmobile", "QQ飞车", Category.GAME)
        add("com.tencent.tmgp.cod", "使命召唤手游", Category.GAME)
        add("com.miHoYo.Yuanshen", "原神", Category.GAME)
        add("com.miHoYo.hkrpg", "崩坏：星穹铁道", Category.GAME)
        add("com.miHoYo.Nap", "绝区零", Category.GAME)
        add("com.miHoYo.bh3", "崩坏3", Category.GAME)
        add("com.KuroGame.WutheringWaves", "鸣潮", Category.GAME)
        add("com.hypergryph.arknights", "明日方舟", Category.GAME)
        add("com.bilibili.azurlane", "碧蓝航线", Category.GAME)
        add("com.netease.onmyoji", "阴阳师", Category.GAME)
        add("com.netease.mrzh", "明日之后", Category.GAME)
        add("com.netease.g78na.gb", "第五人格", Category.GAME)

        // ---------------- 阅读 ----------------
        add("com.bilibili.comic", "哔哩哔哩漫画", Category.READING)
        add("com.kmxs.reader", "七猫免费小说", Category.READING)
        add("com.kuangxiangciwei.kmxs.reader", "刺猬猫", Category.READING)
        add("com.dragon.read", "番茄免费小说", Category.READING)
        add("com.qidian.QDReader", "起点读书", Category.READING)
        add("com.tencent.weread", "微信读书", Category.READING)
        add("com.qq.reader", "QQ阅读", Category.READING)
        add("com.chaozh.iReaderFree", "掌阅", Category.READING)
        add("com.duokan.reader", "多看阅读", Category.READING)

        // ---------------- 社交 ----------------
        add("com.tencent.mm", "微信", Category.SOCIAL)
        add("com.tencent.mobileqq", "QQ", Category.SOCIAL)
        add("com.sina.weibo", "微博", Category.SOCIAL)
        add("com.zhihu.android", "知乎", Category.SOCIAL)
        add("com.douban.frodo", "豆瓣", Category.SOCIAL)

        // ---------------- 视频 / 音频 ----------------
        add("tv.danmaku.bili", "哔哩哔哩", Category.VIDEO)
        add("com.ss.android.ugc.aweme", "抖音", Category.VIDEO)
        add("com.smile.gifmaker", "快手", Category.VIDEO)
        add("com.tencent.qqlive", "腾讯视频", Category.VIDEO)
        add("com.qiyi.video", "爱奇艺", Category.VIDEO)
        add("com.youku.phone", "优酷", Category.VIDEO)
        add("com.netease.cloudmusic", "网易云音乐", Category.VIDEO)
        add("com.ximalaya.ting.android", "喜马拉雅", Category.VIDEO)

        // ---------------- 资讯 ----------------
        add("com.ss.android.article.news", "今日头条", Category.NEWS)
        add("com.ss.android.article.lite", "今日头条极速版", Category.NEWS)
        add("com.tencent.news", "腾讯新闻", Category.NEWS)
        add("com.netease.newsreader.activity", "网易新闻", Category.NEWS)

        // ---------------- 浏览器 ----------------
        add("com.UCMobile", "UC浏览器", Category.BROWSER)
        add("com.tencent.mtt", "QQ浏览器", Category.BROWSER)
        add("com.android.chrome", "Chrome", Category.BROWSER)
        add("com.baidu.searchbox", "百度", Category.BROWSER)

        // ---------------- 购物 ----------------
        add("com.taobao.taobao", "淘宝", Category.SHOPPING)
        add("com.jingdong.app.mall", "京东", Category.SHOPPING)
        add("com.xunmeng.pinduoduo", "拼多多", Category.SHOPPING)
        add("com.taobao.idlefish", "闲鱼", Category.SHOPPING)

        // ---------------- 其他常用 ----------------
        add("com.alibaba.android.rimet", "钉钉", Category.OTHER)
        add("com.tencent.wework", "企业微信", Category.OTHER)
        add("com.autonavi.minimap", "高德地图", Category.OTHER)
        add("com.baidu.BaiduMap", "百度地图", Category.OTHER)
    }

    /** 追加/覆盖一个映射（运行时可扩展） */
    fun register(packageName: String, displayName: String, category: Category) {
        table[packageName] = AppInfo(packageName, displayName, category)
    }

    /** 查不到就原样返回包名 */
    fun resolve(packageName: String): AppInfo =
        table[packageName] ?: AppInfo(packageName, packageName, Category.OTHER)

    /** 是否属于会触发白云搭话的类型（游戏 / 阅读） */
    fun isGameOrReading(packageName: String): Boolean {
        val category = resolve(packageName).category
        return category == Category.GAME || category == Category.READING
    }

    private fun add(packageName: String, displayName: String, category: Category) {
        table[packageName] = AppInfo(packageName, displayName, category)
    }
}
