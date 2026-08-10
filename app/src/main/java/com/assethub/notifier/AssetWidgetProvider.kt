package com.assethub.notifier

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.widget.RemoteViews
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class AssetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AssetWidgetProvider::class.java))
            ids.forEach { updateWidget(ctx, mgr, it) }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.assethub.notifier.WIDGET_REFRESH"

        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private val nf = NumberFormat.getInstance(Locale.KOREA)

        fun comma(v: Long): String = nf.format(v)
        fun comma(v: Double): String = nf.format(v.toLong())

        /** 위젯 본체 탭 → 앱 열기, 새로고침 버튼 → 갱신 */
        private fun attachClicks(ctx: Context, rv: RemoteViews) {
            // 새로고침 버튼
            val refreshIntent = Intent(ctx, AssetWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(ctx, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.btn_refresh, refreshPi)

            // 위젯 본체 → 앱(웹 대시보드) 열기
            val openIntent = Intent(ctx, WebActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(ctx, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.widget_root, openPi)
        }

        fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            // 로딩 상태 먼저 표시
            val rv = RemoteViews(ctx.packageName, R.layout.widget_asset)
            rv.setTextViewText(R.id.tv_updated, "불러오는 중...")
            attachClicks(ctx, rv)
            mgr.updateAppWidget(id, rv)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val serverUrl = Config.serverUrl(ctx)
                    val token = Config.token(ctx)

                    // 통합 요약 API — 주식 조회까지 끝난 뒤 응답
                    val summary = fetchJson("$serverUrl/api/summary", token)

                    if (summary == null) {
                        // 서버 실패 → 마지막으로 성공한 값으로 렌더 (빈 화면 방지)
                        val cached = loadCache(ctx)
                        if (cached != null) {
                            withContext(Dispatchers.Main) { renderWidget(ctx, mgr, id, cached, stale = true) }
                        } else {
                            showError(ctx, mgr, id, "서버 연결 실패")
                        }
                        return@launch
                    }

                    // 주식 조회 실패면 이전 캐시의 주식 값을 살려 총액 왜곡 방지
                    if (!summary.optBoolean("stock_ok", true)) {
                        val cached = loadCache(ctx)
                        if (cached != null) {
                            val prevStock = cached.optDouble("stock", 0.0)
                            if (prevStock > 0) {
                                summary.put("stock", prevStock)
                                summary.put("total",
                                    summary.optDouble("cash", 0.0) +
                                    summary.optDouble("others", 0.0) +
                                    summary.optDouble("estate", 0.0) + prevStock)
                            }
                        }
                    } else {
                        saveCache(ctx, summary)
                    }

                    withContext(Dispatchers.Main) {
                        renderWidget(ctx, mgr, id, summary,
                            stale = !summary.optBoolean("stock_ok", true))
                    }
                } catch (e: Exception) {
                    val cached = loadCache(ctx)
                    if (cached != null) {
                        withContext(Dispatchers.Main) { renderWidget(ctx, mgr, id, cached, stale = true) }
                    } else {
                        showError(ctx, mgr, id, e.message ?: "오류")
                    }
                }
            }
        }

        /** 마지막 성공 데이터 캐시 — 조회 실패 시 이전 값 유지용 */
        private fun saveCache(ctx: Context, obj: JSONObject) {
            try {
                ctx.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
                    .edit().putString("last", obj.toString()).apply()
            } catch (_: Exception) {}
        }

        private fun loadCache(ctx: Context): JSONObject? {
            return try {
                val s = ctx.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
                    .getString("last", null) ?: return null
                JSONObject(s)
            } catch (e: Exception) { null }
        }

        private fun fetchJson(url: String, token: String): JSONObject? {
            return try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("X-Notif-Token", token)
                    .build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) null
                else JSONObject(resp.body?.string() ?: "{}")
            } catch (e: Exception) { null }
        }

        private fun showError(ctx: Context, mgr: AppWidgetManager, id: Int, msg: String) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_asset)
            rv.setTextViewText(R.id.tv_updated, "오류: $msg")
            attachClicks(ctx, rv)
            mgr.updateAppWidget(id, rv)
        }

        private fun renderWidget(ctx: Context, mgr: AppWidgetManager, id: Int,
                                 s: JSONObject, stale: Boolean = false) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_asset)

            val cash   = s.optDouble("cash", 0.0)
            val stock  = s.optDouble("stock", 0.0)
            val others = s.optDouble("others", 0.0)
            val estate = s.optDouble("estate", 0.0)
            val grandTotal = s.optDouble("total", cash + stock + others + estate)

            // 전월 스냅샷 대비 증감
            val snaps = s.optJSONArray("snapshots")
            val prevTotal = if (snaps != null && snaps.length() > 0)
                snaps.getJSONObject(snaps.length() - 1).optDouble("total", 0.0) else 0.0
            val delta = grandTotal - prevTotal

            // 목표
            val goal = s.optJSONObject("goal")
            val goalTarget = goal?.optDouble("target", 0.0) ?: 0.0
            val progress = if (goalTarget > 0)
                (grandTotal / goalTarget * 10000).toInt().coerceIn(0, 10000) else 0

            rv.setTextViewText(R.id.tv_total, "\u20A9${comma(grandTotal)}")
            val deltaStr = if (delta >= 0) "\u25B2 +\u20A9${comma(delta)}" else "\u25BC -\u20A9${comma(-delta)}"
            rv.setTextViewText(R.id.tv_delta, if (prevTotal > 0) deltaStr else "")
            rv.setTextColor(R.id.tv_delta,
                if (delta >= 0) Color.parseColor("#C17A55") else Color.parseColor("#8A9270"))
            rv.setProgressBar(R.id.progress_goal, 10000, progress, false)
            rv.setTextViewText(R.id.tv_progress_pct, String.format("%.2f%%", progress / 100.0))
            rv.setTextViewText(R.id.tv_goal, "\uBAA9\uD45C \u20A9${comma(goalTarget)}")

            // 위젯 가로 픽셀 (넉넉하게)
            val w = 760

            // 자산 구성 도넛
            val assetPairs = listOf(
                Triple("\uD604\uAE08", cash,   Color.parseColor("#C17A55")),
                Triple("\uC8FC\uC2DD", stock,  Color.parseColor("#8A9270")),
                Triple("\uBD80\uB3D9\uC0B0", estate, Color.parseColor("#8A6D3B")),
                Triple("\uAE30\uD0C0", others, Color.parseColor("#C0B9AE"))
            ).filter { it.second > 0 }

            rv.setImageViewBitmap(R.id.img_asset_donut,
                drawDonut(w,
                    assetPairs.map { Pair(it.second.toFloat(), it.third) },
                    "\u20A9${comma(grandTotal)}",
                    assetPairs.map { Pair(it.first, it.second) },
                    assetPairs.map { it.third }))

            // 지출 구성 도넛
            val expObj = s.optJSONObject("expense")
            val expPairs = mutableListOf<Triple<String, Double, Int>>()
            if (expObj != null) {
                val order = listOf(
                    Triple("\uACE0\uC815\uC9C0\uCD9C", "\uACE0\uC815", Color.parseColor("#C17A55")),
                    Triple("\uD544\uC694\uBCC0\uB3D9\uC9C0\uCD9C", "\uD544\uC694\uBCC0\uB3D9", Color.parseColor("#8A6D3B")),
                    Triple("\uC120\uD0DD\uBCC0\uB3D9\uC9C0\uCD9C", "\uC120\uD0DD\uBCC0\uB3D9", Color.parseColor("#B8735C")),
                    Triple("\uBBF8\uBD84\uB958", "\uBBF8\uBD84\uB958", Color.parseColor("#C0B9AE"))
                )
                order.forEach { (key, label, color) ->
                    val v = expObj.optDouble(key, 0.0)
                    if (v > 0) expPairs.add(Triple(label, v, color))
                }
            }
            val totalExp = expPairs.sumOf { it.second }

            rv.setImageViewBitmap(R.id.img_expense_donut,
                drawDonut(w,
                    expPairs.map { Pair(it.second.toFloat(), it.third) },
                    "\u20A9${comma(totalExp)}",
                    expPairs.map { Pair(it.first, it.second) },
                    expPairs.map { it.third }))

            val fmt = java.text.SimpleDateFormat("HH:mm", Locale.KOREA)
            rv.setTextViewText(R.id.tv_updated,
                if (stale) "${fmt.format(java.util.Date())} (\uC8FC\uC2DD \uC870\uD68C \uC2E4\uD328 \u2014 \uC774\uC804\uAC12)"
                else "${fmt.format(java.util.Date())} \uAC31\uC2E0")

            attachClicks(ctx, rv)

            mgr.updateAppWidget(id, rv)
        }


        /**
         * 도넛 + 범례 비트맵. 위젯이 상하 배치이므로 가로로 넓은 비율(2:1)로 그린다.
         * 범례에 금액과 비율을 함께 표시하고 행간을 넉넉히 둔다.
         */
        private fun drawDonut(
            w: Int,
            slices: List<Pair<Float, Int>>,
            centerLabel: String,
            legends: List<Pair<String, Double>>,
            legendColors: List<Int>
        ): Bitmap {
            val h = (w * 0.52f).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            if (slices.isEmpty()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#EBE4D8")
                    style = Paint.Style.STROKE
                    strokeWidth = h * 0.11f
                }
                canvas.drawCircle(h / 2f, h / 2f, h * 0.32f, p)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#8F8778")
                    textSize = h * 0.11f
                    textAlign = Paint.Align.LEFT
                }
                canvas.drawText("데이터 없음", h * 1.05f, h / 2f, tp)
                return bmp
            }

            // 왼쪽 도넛 영역: 정사각 h × h
            val cx = h / 2f
            val cy = h / 2f
            val radius = h * 0.36f
            val strokeW = radius * 0.42f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
            }
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            val total = slices.sumOf { it.first.toDouble() }.toFloat()
            var startAngle = -90f
            slices.forEach { (value, color) ->
                val sweep = value / total * 360f
                paint.color = color
                canvas.drawArc(oval, startAngle, sweep - 0.6f, false, paint)
                startAngle += sweep
            }

            // 도넛 가운데 총액
            val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3D3A35")
                textSize = h * 0.085f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(centerLabel, cx, cy + centerPaint.textSize / 3, centerPaint)

            // 오른쪽 범례 — 항목명 / 금액 / 비율
            val legendX = h * 1.02f
            val dotR = h * 0.028f
            val nameSize = h * 0.085f
            val amtSize = h * 0.078f
            val lineH = h * 0.235f      // 항목 간 간격 (행간 넉넉히)

            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = nameSize; textAlign = Paint.Align.LEFT
                typeface = Typeface.DEFAULT_BOLD
                color = Color.parseColor("#3D3A35")
            }
            val amtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = amtSize; textAlign = Paint.Align.LEFT
                color = Color.parseColor("#8F8778")
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

            val blockH = legends.size * lineH
            var y = (h - blockH) / 2f + lineH * 0.42f

            legends.forEachIndexed { i, (label, value) ->
                dotPaint.color = if (i < legendColors.size) legendColors[i] else Color.GRAY
                canvas.drawCircle(legendX + dotR, y - nameSize * 0.30f, dotR, dotPaint)

                val pct = if (total > 0) "%.0f%%".format(value / total * 100) else "-"
                // 항목명 + 비율
                canvas.drawText("$label  $pct", legendX + dotR * 2.8f, y, namePaint)
                // 금액 (아래 줄)
                canvas.drawText("₩${comma(value)}", legendX + dotR * 2.8f, y + amtSize * 1.25f, amtPaint)
                y += lineH
            }

            return bmp
        }
    }
}
