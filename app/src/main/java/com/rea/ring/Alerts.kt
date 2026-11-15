package com.rea.ring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 异常提醒：数值突变 / 心率过高过低 / 血氧过低 / 血压过高时发系统通知。
 * 每个指标独立冷却，避免连发。
 */
object Alerts {
    private const val TAG = "ReaRing"
    private const val CHANNEL = "ring_alerts"
    private const val BASE_ID = 3001
    private const val COOLDOWN = 20 * 60 * 1000L

    private val lastFire = HashMap<String, Long>()

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "健康提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "心率、血氧、血压异常时提醒"
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    private fun fire(ctx: Context, key: String, title: String, text: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(BASE_ID + key.hashCode().and(0x3FF), n)
            Log.d(TAG, "alert fired: " + title + " / " + text)
        } catch (e: Exception) {
            Log.d(TAG, "alert fail " + e.message)
        }
    }

    private fun allowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastFire[key] ?: 0L) < COOLDOWN) return false
        lastFire[key] = now
        return true
    }

    fun onSample(ctx: Context, metric: String, prev: Int?, value: Int) {
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        when (metric) {
            "heart" -> {
                if (value >= 130 && allowed("heart_high"))
                    fire(ctx, "heart_high", "心率过高", value.toString() + " bpm（阈值 130）· " + t)
                else if (value <= 45 && value >= 25 && allowed("heart_low"))
                    fire(ctx, "heart_low", "心率过低", value.toString() + " bpm（阈值 45）· " + t)
                else if (prev != null && prev in 45..190 && value in 45..190 &&
                    kotlin.math.abs(value - prev) >= 40 && allowed("heart_jump"))
                    fire(ctx, "heart_jump", "心率突变", prev.toString() + " → " + value.toString() + " bpm · " + t)
            }
            "spo2" -> {
                if (value <= 89 && value >= 60 && allowed("spo2_low"))
                    fire(ctx, "spo2_low", "血氧偏低", value.toString() + "%（阈值 90%）· " + t)
                else if (prev != null && prev in 90..100 && prev - value >= 6 && allowed("spo2_drop"))
                    fire(ctx, "spo2_drop", "血氧骤降", prev.toString() + "% → " + value.toString() + "% · " + t)
            }
            "bp" -> {
                if (value >= 160 && allowed("bp_high"))
                    fire(ctx, "bp_high", "血压偏高", "收缩压 " + value + " mmHg（阈值 160）· " + t)
                else if (prev != null && prev in 80..200 && kotlin.math.abs(value - prev) >= 30 && allowed("bp_jump"))
                    fire(ctx, "bp_jump", "血压波动", prev.toString() + " → " + value.toString() + " mmHg · " + t)
            }
            "stress" -> {
                if (value >= 85 && allowed("stress_high"))
                    fire(ctx, "stress_high", "压力偏高", value.toString() + "（阈值 85）· " + t)
            }
        }
    }
}