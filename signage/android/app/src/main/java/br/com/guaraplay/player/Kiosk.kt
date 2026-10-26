package br.com.guaraplay.player

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings

/**
 * Modo administrador (quiosque). Só funciona se o app for "proprietário do dispositivo",
 * ativado uma vez por ADB (veja o LEIAME). Aí o Android passa a:
 * travar o app na tela (sem Home, sem recentes, sem barra de status), impedir desinstalar
 * e restaurar de fábrica, e manter o Guará Play como tela inicial.
 */
object Kiosk {
    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val RESTRICTIONS = listOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_ADD_USER)

    fun isOwner(c: Context): Boolean = try { dpm(c).isDeviceOwnerApp(c.packageName) } catch (_: Exception) { false }

    fun enforce(a: Activity) {
        if (!isOwner(a)) return
        val d = dpm(a); val n = admin(a)
        runCatching { d.setLockTaskPackages(n, arrayOf(a.packageName)) }
        if (Build.VERSION.SDK_INT >= 28) runCatching { d.setLockTaskFeatures(n, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        runCatching { d.setStatusBarDisabled(n, true) }
        runCatching { d.setKeyguardDisabled(n, true) }
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { d.setUninstallBlocked(n, a.packageName, true) }
            runCatching { d.setGlobalSetting(n, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7") }
        }
        RESTRICTIONS.forEach { r -> runCatching { d.addUserRestriction(n, r) } }
        runCatching {
            val f = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            d.addPersistentPreferredActivity(n, f, ComponentName(a, MainActivity::class.java))
        }
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { a.startLockTask() }
    }

    /** Sai do modo travado até o app ser reaberto (usado na manutenção). */
    fun unlock(a: Activity) { runCatching { a.stopLockTask() } }

    /** Desfaz tudo e devolve o aparelho ao normal. */
    fun disable(a: Activity) {
        if (!isOwner(a)) return
        val d = dpm(a); val n = admin(a)
        runCatching { a.stopLockTask() }
        runCatching { d.clearPackagePersistentPreferredActivities(n, a.packageName) }
        RESTRICTIONS.forEach { r -> runCatching { d.clearUserRestriction(n, r) } }
        runCatching { d.setStatusBarDisabled(n, false) }
        runCatching { d.setKeyguardDisabled(n, false) }
        if (Build.VERSION.SDK_INT >= 24) runCatching { d.setUninstallBlocked(n, a.packageName, false) }
        @Suppress("DEPRECATION")
        runCatching { d.clearDeviceOwnerApp(a.packageName) }
    }
}
