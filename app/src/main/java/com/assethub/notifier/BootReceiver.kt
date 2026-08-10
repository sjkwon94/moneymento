package com.assethub.notifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅 후 미확정 큐가 남아 있으면 30분 재알림을 다시 예약한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (PendingStore.count(context) == 0) return
        RecheckAlarm.schedule(context)
    }
}
