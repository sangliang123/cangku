package com.baiyun.pet

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 前台 App 识别（单例）。
 *
 * 只做一件事：通过 UsageStatsManager 拿到「当前前台应用的包名」。
 *
 * 隐私说明：
 * - 不截屏、不读屏幕内容、不读通知，只拿包名字符串；
 * - 需要用户在系统里手动打开「使用情况访问」（PACKAGE_USAGE_STATS，属于特殊权限，无法用弹窗申请）。
 *
 * 局限：UsageStats 是系统聚合数据，有 1~5 秒延迟，切 App 后不一定立刻反映出来，
 * 所以服务里每 10 秒查一次，而不是实时监听。
 */
object ScreenDetector {

    private const val TAG = "ScreenDetector"

    /** 查询最近 5 秒内的使用记录（需求指定） */
    private const val QUERY_WINDOW_MS = 5_000L

    /** 最近一条记录超过这个时间就认为已经过期（比如息屏了） */
    private const val MAX_STALE_MS = 12_000L

    /**
     * 是否已授予「使用情况访问」权限。
     *
     * 用 AppOpsManager 查最准：直接看 UsageStatsManager 有没有数据会误判
     * （没权限时它通常返回空列表，而不是抛异常）。
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "检查使用情况权限失败", e)
            false
        }
    }

    /**
     * 当前前台应用的包名，拿不到返回 null。
     *
     * 做法：查最近 5 秒的 UsageStats，取 lastTimeUsed 最大（最接近当前）的一条。
     */
    fun currentForegroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val stats: List<UsageStats> = try {
            manager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - QUERY_WINDOW_MS,
                now
            ).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "查询 UsageStats 失败", e)
            return null
        }
        if (stats.isEmpty()) return null

        val latest = stats.asSequence()
            .filter { it.lastTimeUsed > 0L }
            .maxByOrNull { it.lastTimeUsed }
            ?: return null

        // 记录太旧（息屏 / 长时间没操作）就不算当前前台
        if (now - latest.lastTimeUsed > MAX_STALE_MS) return null

        // 最前面就是白云自己（比如你正在主界面/设置页）→ 不返回，免得误判成还在上一个 App
        if (latest.packageName == context.packageName) return null

        return latest.packageName
    }
}
