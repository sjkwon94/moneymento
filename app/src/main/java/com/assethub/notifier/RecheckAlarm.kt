package com.assethub.notifier

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 미확정 건이 있을 때 30분마다 오버레이 재알림을 예약한다. */
object RecheckAlarm {
    private const val REQ = 4100
    private const val INTERVAL = 30 * 60 * 1000L // 30분

    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ, Intent(ctx, RecheckReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val at = System.currentTimeMillis() + INTERVAL
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {}
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ, Intent(ctx, RecheckReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            am.cancel(pi)
        } catch (_: Exception) {}
    }
}

class RecheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (PendingStore.count(context) == 0) return
        try {
            context.startService(Intent(context, OverlayService::class.java))
        } catch (_: Exception) {}
    }
}
