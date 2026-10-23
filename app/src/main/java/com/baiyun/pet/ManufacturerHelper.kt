package com.baiyun.pet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 国产 ROM 的「后台白名单」适配。
 *
 * 小米 / 华为 / OPPO / vivo / 三星这几个品牌的系统会主动清理后台，
 * 光有前台服务+通知也不一定能活。这里做两件事：
 * 1. 第一次成功开启桌宠后弹一次引导对话框（SharedPreferences 的 FLAG_FIRST_TIME 控制只弹一次）；
 * 2. 点「去设置」时按品牌跳对应的自启动 / 电池设置页，全部失败就 Toast 让用户手动找。
 *
 * 跳转用的是「显式组件 + 逐个尝试」：厂商页面在不同系统版本里类名会变，
 * 所以每个品牌准备多个候选，哪个存在用哪个，最后兜底到系统的电池优化列表。
 */
object ManufacturerHelper {

    private const val TAG = "ManufacturerHelper"

    /** 需要额外提醒的品牌；OTHER 表示原生/其他系统，不打扰 */
    enum class Brand(val label: String) {
        XIAOMI("小米 / Redmi"),
        HUAWEI("华为 / 荣耀"),
        OPPO("OPPO / 一加 / realme"),
        VIVO("vivo / iQOO"),
        SAMSUNG("三星"),
        OTHER("")
    }

    /** 读 Build.MANUFACTURER / BRAND 判断品牌 */
    fun detect(): Brand {
        val raw = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            raw.contains("xiaomi") || raw.contains("redmi") -> Brand.XIAOMI
            raw.contains("huawei") || raw.contains("honor") -> Brand.HUAWEI
            raw.contains("oppo") || raw.contains("oneplus") || raw.contains("realme") -> Brand.OPPO
            raw.contains("vivo") || raw.contains("iqoo") -> Brand.VIVO
            raw.contains("samsung") -> Brand.SAMSUNG
            else -> Brand.OTHER
        }
    }

    /**
     * 第一次开启桌宠后调用：弹白名单引导（每个设备只弹一次）。
     * 内部会自己判断品牌和 FLAG_FIRST_TIME，重复调用没有副作用。
     */
    fun showGuideIfNeeded(activity: Activity) {
        val brand = detect()
        if (brand == Brand.OTHER) return
        if (!SettingsStore.shouldShowFirstTimeGuide(activity)) return

        // 先记已弹过，避免用户点「去设置」时 Activity 重建又弹一次
        SettingsStore.markFirstTimeGuideShown(activity)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.whitelist_title, brand.label))
            .setMessage(
                activity.getString(
                    R.string.whitelist_message,
                    activity.getString(stepsRes(brand))
                )
            )
            .setPositiveButton(R.string.whitelist_go) { _, _ ->
                if (!openAutoStartSettings(activity)) {
                    // 所有候选页面都不存在时的兜底
                    Toast.makeText(activity, R.string.whitelist_manual, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.whitelist_later, null)
            .show()
    }

    /**
     * 按当前品牌打开「自启动 / 电池优化」设置页。
     *
     * @return true = 成功打开某个设置页；false = 全都打不开，调用方自行提示
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = brandIntents(detect()) + listOf(
            // 系统兜底的「电池优化」列表，几乎所有设备都有
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        for (intent in candidates) {
            // 先查有没有这个页面，避免每次都抛异常（也避免跳到不存在的 Activity）
            val resolved = try {
                context.packageManager.resolveActivity(intent, 0) != null
            } catch (e: Exception) {
                false
            }
            if (!resolved) continue

            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // SecurityException / ActivityNotFoundException / 厂商拦截，继续试下一个
                Log.w(TAG, "打开设置页失败：${intent.component}", e)
            }
        }
        return false
    }

    /** 每个品牌准备若干个候选页面（不同 ROM 版本类名不一样） */
    private fun brandIntents(brand: Brand): List<Intent> = when (brand) {
        Brand.XIAOMI -> listOf(
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )

        Brand.HUAWEI -> listOf(
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
        )

        Brand.OPPO -> listOf(
            component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            component(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            component(
                "com.coloros.oppoguardelf",
                "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
            )
        )

        Brand.VIVO -> listOf(
            component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            component(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
        )

        Brand.SAMSUNG -> listOf(
            component(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            ),
            component(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )

        Brand.OTHER -> emptyList()
    }

    private fun component(pkg: String, cls: String): Intent = Intent().apply {
        component = ComponentName(pkg, cls)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 每个品牌对应的手动步骤文案（对话框正文用） */
    private fun stepsRes(brand: Brand): Int = when (brand) {
        Brand.XIAOMI -> R.string.whitelist_steps_xiaomi
        Brand.HUAWEI -> R.string.whitelist_steps_huawei
        Brand.OPPO -> R.string.whitelist_steps_oppo
        Brand.VIVO -> R.string.whitelist_steps_vivo
        Brand.SAMSUNG -> R.string.whitelist_steps_samsung
        Brand.OTHER -> R.string.whitelist_manual
    }
}
