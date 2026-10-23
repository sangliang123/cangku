package com.baiyun.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 开机自启。
 *
 * 收到 BOOT_COMPLETED（以及应用更新完成 MY_PACKAGE_REPLACED）后，
 * 把桌宠的前台服务拉起来。
 *
 * 三个前置条件，缺一不可：
 * 1. 用户给过「悬浮窗」权限 —— 没有权限起来也没用；
 * 2. 用户没有在 App 里点过「关闭桌宠」（SettingsStore.isPetEnabled）；
 * 3. 系统允许后台启动前台服务（Android 12+ 对 BOOT_COMPLETED 有豁免，
 *    但个别 ROM 会自己拦，所以外面套 try-catch，失败就安静放弃，
 *    用户打开 App 时 MainActivity 会自动再拉一次）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val app = context.applicationContext

        if (!Settings.canDrawOverlays(app)) {
            Log.d(TAG, "没有悬浮窗权限，跳过开机自启")
            return
        }
        if (!SettingsStore.isPetEnabled(app)) {
            Log.d(TAG, "用户已关闭桌宠，跳过开机自启")
            return
        }

        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, FloatingPetService::class.java)
            )
            Log.d(TAG, "开机自启：已请求启动桌宠服务")
        } catch (e: Exception) {
            // 例如 Android 12+ 的 ForegroundServiceStartNotAllowedException
            Log.w(TAG, "开机自启失败，等用户打开 App 再拉起", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
