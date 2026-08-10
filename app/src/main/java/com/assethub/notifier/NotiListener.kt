package com.assethub.notifier

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotiListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        try { DebugLog.add(this, "[시스템]", "리스너 연결됨", "알림 수신 준비 완료", false, false) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            val extras = sbn.notification?.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

            val isBank = TxParser.isBank(pkg)
            val tx = if (isBank) TxParser.parse(pkg, title, text, sbn.postTime) else null
            DebugLog.add(this, pkg, title, text, matched = isBank, parsed = (tx != null))
            if (tx == null) return

            PendingStore.add(this, tx)

            // 즉시 짝(30초 창)이 이미 있는지 확인 → 있으면 바로 이체 팝업
            val pairNow = PendingStore.tryPair(this, tx.dedupKey)
            if (pairNow != null) {
                startOverlay(transfer = pairNow)
                return
            }

            // 없으면 30초 뒤에 다시 확인: 그때도 짝 없으면 일반 팝업
            val key = tx.dedupKey
            handler.postDelayed({
                try {
                    val pair = PendingStore.tryPair(this, key)
                    if (pair != null) {
                        startOverlay(transfer = pair)
                    } else {
                        val still = PendingStore.get(this, key)
                        if (still != null && still.optString("status") != "paired") {
                            startOverlay(singleKey = key)
                        }
                    }
                } catch (e: Exception) {
                    try { DebugLog.add(this, "[오류]", "지연 처리 실패", e.message, false, false) } catch (_: Exception) {}
                }
            }, PendingStore.PAIR_WINDOW_MS)
        } catch (e: Exception) {
            try { DebugLog.add(this, "[오류]", "알림 처리 실패", e.message, false, false) } catch (_: Exception) {}
        }
    }

    private fun startOverlay(singleKey: String? = null, transfer: org.json.JSONObject? = null) {
        try {
            val i = Intent(this, OverlayService::class.java)
            if (transfer != null) i.putExtra("transfer", transfer.toString())
            else if (singleKey != null) i.putExtra("dedupKey", singleKey)
            startService(i)
        } catch (e: Exception) {
            try { DebugLog.add(this, "[오류]", "오버레이 시작 실패", e.message, false, false) } catch (_: Exception) {}
        }
    }
}
