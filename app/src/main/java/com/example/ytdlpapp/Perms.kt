package com.example.ytdlpapp

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Helper untuk izin notifikasi dan pengecualian optimasi baterai. */
object Perms {

    fun notificationsEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Dialog sistem langsung: "Izinkan aplikasi berjalan tanpa batasan baterai?" */
    @SuppressLint("BatteryLife")
    fun requestBattery(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        } catch (e: ActivityNotFoundException) {
            openBatterySettings(ctx)
        }
    }

    fun openBatterySettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            openAppDetails(ctx)
        }
    }

    fun openNotificationSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            )
        } catch (e: ActivityNotFoundException) {
            openAppDetails(ctx)
        }
    }

    private fun openAppDetails(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }
}
