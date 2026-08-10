package com.assethub.notifier

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(col)

        col.addView(TextView(this).apply {
            text = "자산허브 알림 연동"
            textSize = 20f; setTextColor(Color.parseColor("#0E5C4A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // 서버 주소 / 토큰
        val server = EditText(this).apply {
            hint = "서버 주소 (예: http://68.183.181.222)"
            setText(Config.serverUrl(this@MainActivity))
        }
        val token = EditText(this).apply {
            hint = "알림 토큰 (.env 의 NOTIF_TOKEN)"
            setText(Config.token(this@MainActivity))
        }
        col.addView(spacer()); col.addView(label("서버 주소")); col.addView(server)
        col.addView(label("알림 토큰")); col.addView(token)

        col.addView(btn("설정 저장 + 연결 테스트") {
            Config.save(this, server.text.toString(), token.text.toString())
            Thread {
                val ok = Api.ping(this)
                runOnUiThread { toast(if (ok) "연결 성공" else "연결 실패 — 주소/토큰 확인") }
            }.start()
        })

        col.addView(spacer())
        col.addView(TextView(this).apply {
            text = "필수 권한 3가지"; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        col.addView(btn("1. 알림 접근 허용") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        col.addView(btn("2. 다른 앱 위에 표시 허용") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(btn("3. 배터리 최적화 제외 (선택)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        col.addView(spacer())
        status = TextView(this).apply { setTextColor(Color.DKGRAY) }
        col.addView(status)

        col.addView(btnColored("← 대시보드로 돌아가기", "#C17A55") {
            startActivity(Intent(this, WebActivity::class.java))
            finish()
        })
        col.addView(spacer())

        col.addView(btn("오버레이 서비스 시작") {
            val svc = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            toast("시작됨")
        })

        // ── 보류 항목 처리 버튼 ──────────────────────────────
        col.addView(spacer())
        val pendingCountTv = TextView(this).apply {
            setTextColor(Color.parseColor("#C17A55")); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(pendingCountTv)
        col.addView(btnColored("⚠ 보류 항목 처리", "#C17A55") {
            showPendingDialog()
        })

        col.addView(spacer())
        col.addView(TextView(this).apply {
            text = "진단 — 받은 알림 로그"; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val logView = TextView(this).apply {
            setTextColor(Color.DKGRAY); textSize = 12f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setTextIsSelectable(true)
        }
        col.addView(btn("알림 로그 새로고침") {
            logView.text = renderLog()
        })
        col.addView(btn("로그 지우기") {
            DebugLog.clear(this); logView.text = "(비어 있음)"
        })
        col.addView(logView)
        logView.text = renderLog()

        col.addView(TextView(this).apply {
            text = "\n동작: 올원뱅크·케이뱅크 입출금 알림이 오면 확인 팝업(수정/확인/보류)이 뜹니다. " +
                    "보류하거나 홈으로 나가면 30분마다 다시 확인을 요청합니다."
            setTextColor(Color.GRAY); textSize = 13f
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val pendingLocal = PendingStore.count(this)
        status.text = "미확정 대기 건수: ${pendingLocal}건"
        // 서버 보류 건수도 비동기로 확인
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val arr = Api.getPending(this@MainActivity)
            val serverCount = arr.length()
            runOnUiThread {
                if (serverCount > 0)
                    status.text = "미확정 대기: ${pendingLocal}건 · 서버 보류: ${serverCount}건 ⚠"
            }
        }
    }

    private fun showPendingDialog() {
        val ctx = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val arr = Api.getPending(ctx)
            runOnUiThread {
                if (arr.length() == 0) {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("보류 항목").setMessage("처리할 보류 항목이 없습니다.")
                        .setPositiveButton("확인", null).show()
                    return@runOnUiThread
                }
                // 첫 번째 항목을 팝업으로 다시 표시
                val item = arr.getJSONObject(0)
                val svc = Intent(ctx, OverlayService::class.java).apply {
                    action = "SHOW_PENDING"
                    putExtra("pending_item", item.toString())
                }
                startService(svc)
            }
        }
    }

    private fun btnColored(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
        setBackgroundColor(Color.parseColor(color)); setTextColor(Color.WHITE)
    }

    private fun renderLog(): String {
        val logs = DebugLog.all(this)
        if (logs.isEmpty()) return "(아직 받은 알림 없음)\n알림 접근 권한을 켠 뒤 은행에서 1원 이체해 보세요."
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
        return logs.joinToString("\n\n") { o ->
            val t = fmt.format(java.util.Date(o.optLong("ts")))
            val flag = when {
                o.optBoolean("parsed") -> "✅ 은행·파싱성공"
                o.optBoolean("matched") -> "⚠️ 은행이나 파싱실패"
                else -> "· 기타앱"
            }
            "[$t] $flag\npkg: ${o.optString("pkg")}\n제목: ${o.optString("title")}\n본문: ${o.optString("text")}"
        }
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
        setBackgroundColor(Color.parseColor("#0E5C4A")); setTextColor(Color.WHITE)
    }
    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#6C7A74")); setPadding(0, dp(8), 0, dp(2))
    }
    private fun spacer() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(12))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
