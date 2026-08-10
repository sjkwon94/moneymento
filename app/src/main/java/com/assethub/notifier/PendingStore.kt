package com.assethub.notifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 사용자가 아직 확정하지 않은(보류/미응답) 거래 큐. 폰 로컬에만 저장. */
object PendingStore {
    private const val PREF = "pending"
    private const val KEY = "queue"
    const val PAIR_WINDOW_MS = 10_000L  // 내 계좌 이체 자동 묶음 시간창(10초)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<JSONObject> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 새 거래를 큐에 넣는다. dedup 중복이면 무시. */
    fun add(ctx: Context, tx: Tx) {
        val list = all(ctx).toMutableList()
        if (list.any { it.optString("dedupKey") == tx.dedupKey }) return
        val o = JSONObject().apply {
            put("source", tx.source)
            put("account", tx.account)
            put("type", tx.type)
            put("amount", tx.amount)
            put("cat", "")
            put("counterparty", tx.counterparty)
            put("acctDigits", tx.acctDigits)
            put("dedupKey", tx.dedupKey)
            put("isCard", tx.isCard)
            put("ts", if (tx.postTime > 0) tx.postTime else System.currentTimeMillis())
            put("status", "waiting")
        }
        list.add(o)
        write(ctx, list)
    }

    /**
     * dedupKey 건에 대해 30초 창 안의 반대방향·동일금액 짝을 찾는다.
     * 찾으면 두 건을 하나의 이체(transfer)로 묶어 표시용 JSON을 반환. 없으면 null.
     */
    fun tryPair(ctx: Context, dedupKey: String): JSONObject? {
        val list = all(ctx).toMutableList()
        val me = list.firstOrNull { it.optString("dedupKey") == dedupKey } ?: return null
        if (me.optString("status") == "paired") return null
        val mate = list.firstOrNull {
            it.optString("dedupKey") != dedupKey &&
            it.optString("status") != "paired" &&
            it.optLong("amount") == me.optLong("amount") &&
            it.optString("type") != me.optString("type") &&      // 반대 방향
            kotlin.math.abs(it.optLong("ts") - me.optLong("ts")) <= PAIR_WINDOW_MS
        } ?: return null

        // 방향 정리: out=보낸계좌(from), in=받은계좌(to)
        val outSide = if (me.optString("type") == "out") me else mate
        val inSide = if (me.optString("type") == "out") mate else me

        // 두 건 모두 paired 로 표시(개별 팝업 안 뜨게)
        me.put("status", "paired"); mate.put("status", "paired")
        write(ctx, list)

        return JSONObject().apply {
            put("kind", "transfer")
            put("amount", me.optLong("amount"))
            put("fromAccount", outSide.optString("account"))
            put("toAccount", inSide.optString("account"))
            put("fromDigits", outSide.optString("acctDigits"))
            put("toDigits", inSide.optString("acctDigits"))
            put("dedupKey", "pair_${outSide.optString("dedupKey")}_${inSide.optString("dedupKey")}")
            put("outKey", outSide.optString("dedupKey"))
            put("inKey", inSide.optString("dedupKey"))
            put("ts", me.optLong("ts"))
        }
    }

    fun get(ctx: Context, dedupKey: String): JSONObject? =
        all(ctx).firstOrNull { it.optString("dedupKey") == dedupKey }

    fun remove(ctx: Context, dedupKey: String) {
        write(ctx, all(ctx).filter { it.optString("dedupKey") != dedupKey })
    }

    /** 여러 건 한 번에 제거 (이체 확정 시 양쪽 제거용). */
    fun removeAll(ctx: Context, keys: List<String>) {
        write(ctx, all(ctx).filter { it.optString("dedupKey") !in keys })
    }

    fun count(ctx: Context) = all(ctx).count { it.optString("status") != "paired" }

    private fun write(ctx: Context, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
