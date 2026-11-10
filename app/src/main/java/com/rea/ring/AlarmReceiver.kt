package com.rea.ring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 30 分钟测量闹钟：Doze / 息屏下 Handler 会被挂起，AlarmManager 不会。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ReaRing", "alarm fire -> measure")
        val app = context.applicationContext
        Ring.attach(app)
        try {
            val svc = Intent(app, RingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
        } catch (e: Exception) {
            Log.d("ReaRing", "start service fail " + e.message)
        }
        Ring.ensureConnected(app)
        Ring.measureAll()
        AlarmScheduler.schedule(app)
    }
}

object AlarmScheduler {
    private const val TAG = "ReaRing"
    private const val REQ = 40961
    const val INTERVAL_MS = 30 * 60 * 1000L

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction("com.rea.ring.MEASURE_ALARM")
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    fun schedule(ctx: Context) = scheduleIn(ctx, INTERVAL_MS)

    fun scheduleIn(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + delayMs
        val pi = pending(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Log.d(TAG, "alarm set (inexact) +30min")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Log.d(TAG, "alarm set (exact) +30min")
            }
        } catch (e: Exception) {
            Log.d(TAG, "alarm set fail " + e.message)
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) {}
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(ctx))
    }
}