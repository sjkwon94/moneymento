package com.assethub.notifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 리스너가 받은 알림 로그 (진단용). 최근 30건만 유지. */
object DebugLog {
    private const val PREF = "debuglog"
    private const val KEY = "log"
    private const val MAX = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun add(ctx: Context, pkg: String, title: String?, text: String?, matched: Boolean, parsed: Boolean) {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        val o = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("pkg", pkg)
            put("title", title ?: "")
            put("text", text ?: "")
            put("matched", matched)   // 은행 패키지 일치 여부
            put("parsed", parsed)     // 금액 파싱 성공 여부
        }
        val list = mutableListOf(o)
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        while (list.size > MAX) list.removeAt(list.size - 1)
        val out = JSONArray()
        list.forEach { out.put(it) }
        prefs(ctx).edit().putString(KEY, out.toString()).apply()
    }

    fun all(ctx: Context): List<JSONObject> {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
