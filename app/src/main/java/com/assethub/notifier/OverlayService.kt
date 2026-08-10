package com.assethub.notifier

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject

/**
 * 오버레이 확인 팝업을 띄운다. (일반 서비스 — 포그라운드 아님)
 * 팝업 버튼: 수정 / 확인 / 보류. 홈버튼으로 벗어나면 = 보류(큐 유지).
 * 30분 재알림은 RecheckAlarm(AlarmManager)이 담당.
 */
class OverlayService : Service() {

    // 가계부 카테고리 체계 (웹과 동일)
    private val CAT_IN = listOf("급여소득", "투자소득", "기타소득")
    private val CAT_OUT = linkedMapOf(
        "필요변동지출" to listOf("업무필요변동지출", "생활필요변동지출"),
        "선택변동지출" to listOf<String>(),
        "고정지출" to listOf("업무고정지출", "생활고정지출")
    )

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var currentView: View? = null
    private var currentKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val pendingItemStr = intent?.getStringExtra("pending_item")
            val transferJson = intent?.getStringExtra("transfer")
            when {
                pendingItemStr != null -> {
                    // 서버 보류 항목을 팝업으로 표시
                    val item = JSONObject(pendingItemStr)
                    item.put("status", "ready")
                    showPopup(item)
                }
                transferJson != null -> showTransferPopup(JSONObject(transferJson))
                else -> {
                    val key = intent?.getStringExtra("dedupKey")
                    val item = if (key != null)
                        PendingStore.get(this, key)
                    else
                        PendingStore.all(this).firstOrNull { it.optString("status") != "paired" }
                    item?.let { showPopup(it) }
                }
            }
            if (PendingStore.count(this) > 0) RecheckAlarm.schedule(this)
        } catch (e: Exception) {
            try { DebugLog.add(this, "[오류]", "서비스 시작 실패", e.message, false, false) } catch (_: Exception) {}
        }
        return START_NOT_STICKY
    }

    /** 자동 묶인 이체 확인 팝업 (금액 고정, 확인/취소만). */
    private fun showTransferPopup(t: JSONObject) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        try {
            removeCurrent()
            val ctx = this
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(20))
            }
            // 상단 배지
            root.addView(TextView(ctx).apply {
                text = "  내 계좌 이체  "
                textSize = 12f; setTextColor(Color.parseColor("#0E5C4A"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#E3EEE9"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            root.addView(TextView(ctx).apply {
                text = "${java.text.DecimalFormat("#,###").format(t.optLong("amount"))}원"
                textSize = 30f; setTextColor(Color.parseColor("#12352C"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(2))
            })
            root.addView(TextView(ctx).apply {
                text = "${t.optString("fromAccount")}  →  ${t.optString("toAccount")}"
                textSize = 15f; setTextColor(Color.parseColor("#55635C"))
            })
            root.addView(TextView(ctx).apply {
                text = "같은 금액이 10초 내 반대로 감지되어 이체로 묶었습니다. 총자산은 변하지 않습니다."
                textSize = 12f; setTextColor(Color.parseColor("#9AA49E"))
                setPadding(0, dp(10), 0, dp(4)); setLineSpacing(dp(2).toFloat(), 1f)
            })

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0)
            }
            val confirm = pillBtn(ctx, "이체로 기록", "#0E5C4A").apply { layoutParams = rowLp(0) }
            val split = pillBtn(ctx, "따로 기록", "#0E5C4A", fg = "#0E5C4A", filled = false).apply { layoutParams = rowLp() }
            confirm.setOnClickListener {
                val payload = JSONObject().apply {
                    put("source", "transfer"); put("type", "transfer")
                    put("amount", t.optLong("amount"))
                    put("account", t.optString("fromAccount"))
                    put("account_to", t.optString("toAccount"))
                    put("from_digits", t.optString("fromDigits"))
                    put("to_digits", t.optString("toDigits"))
                    put("desc", "${t.optString("fromAccount")} → ${t.optString("toAccount")}")
                    put("cat", "이체")
                    put("dedup_key", t.optString("dedupKey"))
                    put("ts", tsString(t.optLong("ts", System.currentTimeMillis())))
                }
                sendTransferAndFinish(payload, listOf(t.optString("outKey"), t.optString("inKey")))
            }
            // "따로 기록": 묶음 취소하고 두 건을 각각 일반 팝업으로
            split.setOnClickListener {
                removeCurrent()
                startService(Intent(ctx, OverlayService::class.java).putExtra("dedupKey", t.optString("outKey")))
                startService(Intent(ctx, OverlayService::class.java).putExtra("dedupKey", t.optString("inKey")))
            }
            btnRow.addView(split); btnRow.addView(confirm)
            root.addView(btnRow)
            addToWindow(root)
        } catch (e: Exception) {
            try { DebugLog.add(this, "[오류]", "이체 팝업 실패", e.message, false, false) } catch (_: Exception) {}
        }
    }

    private fun sendTransferAndFinish(payload: JSONObject, keys: List<String>) {
        Thread {
            val ok = Api.sendTx(this, payload)
            handler.post {
                if (ok) { PendingStore.removeAll(this, keys); toast("이체로 기록") }
                else toast("전송 실패 — 30분 후 재시도")
                removeCurrent()
                if (PendingStore.count(this) == 0) stopSelf()
            }
        }.start()
    }

    private fun showPopup(item: JSONObject) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try { DebugLog.add(this, "[오류]", "오버레이 권한 없음", "다른 앱 위에 표시 허용 필요", false, false) } catch (_: Exception) {}
            return
        }
        try {
            showPopupInner(item)
        } catch (e: Exception) {
            try { DebugLog.add(this, "[오류]", "팝업 표시 실패", e.message, false, false) } catch (_: Exception) {}
            removeCurrent()
        }
    }

    private fun showPopupInner(item: JSONObject) {
        removeCurrent()
        currentKey = item.optString("dedupKey")

        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        val src = when (item.optString("source")) {
            "kbank" -> "케이뱅크"
            "kakaobank" -> "카카오뱅크"
            else -> "올원뱅크"
        }
        root.addView(TextView(ctx).apply {
            text = "  $src  "
            textSize = 12f; setTextColor(Color.parseColor("#0E5C4A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#E3EEE9"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(TextView(ctx).apply {
            text = "${java.text.DecimalFormat("#,###").format(item.optLong("amount"))}원"
            textSize = 30f; setTextColor(Color.parseColor("#12352C"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(2))
        })

        var type = item.optString("type", "out")
        val typeRow = RadioGroup(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val rbOut = RadioButton(ctx).apply { text = "출금"; textSize = 15f }
        val rbIn = RadioButton(ctx).apply { text = "입금"; textSize = 15f; setPadding(dp(20),0,0,0) }
        typeRow.addView(rbOut); typeRow.addView(rbIn)
        if (type == "in") rbIn.isChecked = true else rbOut.isChecked = true
        root.addView(label(ctx, "구분")); root.addView(typeRow)

        val amtEdit = styledInput(ctx).apply {
            setText(item.optLong("amount").toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(label(ctx, "금액 (원)")); root.addView(amtEdit)

        // 분류 스피너 (대분류 + 세부)
        var catMajor = ""
        var catSub = ""
        val majorSpinner = Spinner(ctx)
        val subSpinner = Spinner(ctx)
        val subLabel = label(ctx, "세부분류")

        fun styleSpinnerBg(v: View) {
            v.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#F2F4F1"))
                setStroke(dp(1), Color.parseColor("#E3E7E2"))
            }
            v.setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        styleSpinnerBg(majorSpinner); styleSpinnerBg(subSpinner)

        fun setSub(list: List<String>) {
            val items = listOf("세부분류 선택") + list
            subSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
            val show = list.isNotEmpty()
            subSpinner.visibility = if (show) View.VISIBLE else View.GONE
            subLabel.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) catSub = ""
        }
        fun loadMajors(t: String) {
            val majors = if (t == "in") CAT_IN else CAT_OUT.keys.toList()
            majorSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("대분류 선택") + majors)
            catMajor = ""; catSub = ""
            setSub(emptyList())
        }
        majorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) { catMajor = ""; setSub(emptyList()); return }
                catMajor = majorSpinner.getItemAtPosition(pos).toString()
                setSub(if (type == "out") (CAT_OUT[catMajor] ?: emptyList()) else emptyList())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        subSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                catSub = if (pos == 0) "" else subSpinner.getItemAtPosition(pos).toString()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        loadMajors(type)
        root.addView(label(ctx, "분류")); root.addView(majorSpinner)
        root.addView(subLabel); root.addView(subSpinner)

        // 명목(내용) 입력 — 알림에서 뽑은 상대/내용을 기본값으로
        val descEdit = styledInput(ctx).apply {
            setText(item.optString("counterparty"))
            hint = "내용 (예: 스타벅스, 점심)"
        }
        root.addView(label(ctx, "내용")); root.addView(descEdit)

        // 구분(입금/출금) 바뀌면 대분류 목록 교체
        typeRow.setOnCheckedChangeListener { _, id ->
            type = if (id == rbIn.id) "in" else "out"
            loadMajors(type)
        }

        val cp = item.optString("counterparty")

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        val confirm = pillBtn(ctx, "확인", "#0E5C4A").apply { layoutParams = rowLp() }
        val edit = pillBtn(ctx, "수정", "#0E5C4A", fg = "#0E5C4A", filled = false).apply { layoutParams = rowLp() }
        val hold = pillBtn(ctx, "보류", "#6C7A74", fg = "#6C7A74", filled = false).apply { layoutParams = rowLp(0) }

        val commit = {
            val amt = amtEdit.text.toString().replace(",", "").toLongOrNull() ?: 0L
            if (amt > 0) {
                val payload = JSONObject().apply {
                    put("source", item.optString("source"))
                    put("account", item.optString("account"))
                    put("digits", item.optString("acctDigits"))
                    put("type", type)
                    put("amount", amt)
                    put("catMajor", catMajor)
                    put("catSub", catSub)
                    put("desc", descEdit.text.toString().trim().ifEmpty { cp.ifEmpty { item.optString("account") } })
                    put("is_card", item.optBoolean("isCard", false))
                    put("dedup_key", currentKey)
                    put("ts", tsString(item.optLong("ts", System.currentTimeMillis())))
                }
                sendAndFinish(payload, currentKey!!)
            }
        }
        confirm.setOnClickListener { commit() }
        edit.setOnClickListener { commit() }
        hold.setOnClickListener {
            // 서버에 보류 항목 전송 (웹에서 나중에 처리 가능)
            val amt = amtEdit.text.toString().replace(",","").toLongOrNull()
                ?: item.optLong("amount", 0L)
            val pendingPayload = JSONObject().apply {
                put("id", currentKey)
                put("source", item.optString("source"))
                put("account", item.optString("account"))
                put("acctDigits", item.optString("acctDigits"))
                put("type", type)
                put("amount", amt)
                put("desc", descEdit.text.toString().trim().ifEmpty { cp })
                put("counterparty", cp)
                put("is_card", item.optBoolean("isCard", false))
                put("dedup_key", currentKey)
                put("date", android.text.format.DateFormat.format("yyyy-MM-dd", java.util.Date()).toString())
                put("time", android.text.format.DateFormat.format("HH:mm:ss", java.util.Date()).toString())
                put("ts", tsString(item.optLong("ts", System.currentTimeMillis())))
            }
            CoroutineScope(Dispatchers.IO).launch {
                try { Api.sendPending(this@OverlayService, pendingPayload) } catch (_: Exception) {}
            }
            removeCurrent()
        }

        btnRow.addView(edit); btnRow.addView(confirm); btnRow.addView(hold)
        root.addView(btnRow)

        // "내 계좌 이동" — 이 건을 이체로 처리(총자산 불변). 상대계좌는 비워 기록만 남김.
        val transferBtn = pillBtn(ctx, "내 계좌 이동 (이체 처리)", "#5B6D8A", fg = "#5B6D8A", filled = false).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        transferBtn.setOnClickListener {
            val amt = amtEdit.text.toString().replace(",", "").toLongOrNull() ?: 0L
            if (amt > 0) {
                val payload = JSONObject().apply {
                    put("source", "transfer"); put("type", "transfer")
                    put("amount", amt)
                    // 출금이면 이 계좌가 from, 입금이면 to
                    if (type == "out") { put("account", item.optString("account")); put("from_digits", item.optString("acctDigits")) }
                    else { put("account_to", item.optString("account")); put("to_digits", item.optString("acctDigits")) }
                    put("desc", "${item.optString("account")} 계좌 이동")
                    put("cat", "이체")
                    put("dedup_key", currentKey)
                    put("ts", tsString(item.optLong("ts", System.currentTimeMillis())))
                }
                sendAndFinish(payload, currentKey!!)
            }
        }
        root.addView(transferBtn)

        addToWindow(root)
    }

    private fun addToWindow(card: View) {
        card.background = cardBg()
        // 카드를 스크롤 가능하게 감싸 키보드가 떠도 내용을 스크롤해 볼 수 있게 함
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP })
        }
        val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            winType,
            // FLAG_NOT_FOCUSABLE 를 빼야 EditText 가 키보드 입력을 받는다.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(40)
            dimAmount = 0.45f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        wm?.addView(wrapper, lp)
        currentView = wrapper
    }

    private fun sendAndFinish(payload: JSONObject, key: String) {
        Thread {
            val ok = Api.sendTx(this, payload)
            handler.post {
                if (ok) {
                    PendingStore.remove(this, key)
                    toast("기록 완료")
                } else {
                    toast("전송 실패 — 30분 후 다시 시도")
                }
                removeCurrent()
                if (PendingStore.count(this) == 0) stopSelf()
            }
        }.start()
    }

    private fun removeCurrent() {
        currentView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        currentView = null; currentKey = null
    }

    private fun label(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#8A968F"))
        letterSpacing = 0.02f
        setPadding(0, dp(12), 0, dp(4))
    }

    // ── 모던 UI 헬퍼 ──
    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(24).toFloat()
    }

    private fun pillBtn(ctx: Context, txt: String, bg: String, fg: String = "#FFFFFF", filled: Boolean = true): TextView =
        TextView(ctx).apply {
            text = txt
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(fg))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (filled) setColor(Color.parseColor(bg))
                else { setColor(Color.parseColor("#FFFFFF")); setStroke(dp(1), Color.parseColor(bg)) }
            }
        }

    private fun styledInput(ctx: Context): EditText = EditText(ctx).apply {
        textSize = 16f
        setTextColor(Color.parseColor("#1C2B26"))
        setHintTextColor(Color.parseColor("#B5BEB8"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#F2F4F1"))
            setStroke(dp(1), Color.parseColor("#E3E7E2"))
        }
    }

    private fun rowLp(marginEnd: Int = 8) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { this.marginEnd = dp(marginEnd) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun tsString(ms: Long): String {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
        return d.format(java.util.Date(ms))
    }

    override fun onDestroy() {
        removeCurrent()
        super.onDestroy()
    }
}
