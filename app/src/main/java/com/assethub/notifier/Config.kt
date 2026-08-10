package com.assethub.notifier

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Config {
    private const val PREF = "config"
    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun serverUrl(ctx: Context) = prefs(ctx).getString("server", "http://68.183.181.222") ?: ""
    fun token(ctx: Context) = prefs(ctx).getString("token", "") ?: ""

    fun save(ctx: Context, server: String, token: String) {
        prefs(ctx).edit().putString("server", server.trimEnd('/')).putString("token", token).apply()
    }
}

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 확정된 거래 1건 전송. 성공 시 true. */
    fun sendTx(ctx: Context, tx: JSONObject): Boolean {
        val url = Config.serverUrl(ctx) + "/api/notif"
        val token = Config.token(ctx)
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Notif-Token", token)
                .post(tx.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** 서버 연결 + 토큰 확인. */
    fun ping(ctx: Context): Boolean {
        val url = Config.serverUrl(ctx) + "/api/notif/ping"
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Notif-Token", Config.token(ctx))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    /** 보류 항목 서버에 저장. */
    fun sendPending(ctx: Context, item: JSONObject): Boolean {
        val url = Config.serverUrl(ctx) + "/api/pending"
        val token = Config.token(ctx)
        return try {
            val req = Request.Builder().url(url)
                .addHeader("X-Notif-Token", token)
                .post(item.toString().toRequestBody(JSON)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    /** 보류 항목 목록 조회. */
    fun getPending(ctx: Context): org.json.JSONArray {
        val url = Config.serverUrl(ctx) + "/api/pending"
        val token = Config.token(ctx)
        return try {
            val req = Request.Builder().url(url)
                .addHeader("X-Notif-Token", token).build()
            client.newCall(req).execute().use {
                if (it.isSuccessful) org.json.JSONArray(it.body?.string() ?: "[]")
                else org.json.JSONArray()
            }
        } catch (e: Exception) { org.json.JSONArray() }
    }

    /** 보류 항목 처리(가계부 기록). */
    fun resolvePending(ctx: Context, id: String, patch: JSONObject): Boolean {
        val url = Config.serverUrl(ctx) + "/api/pending/$id"
        val token = Config.token(ctx)
        return try {
            val req = Request.Builder().url(url)
                .addHeader("X-Notif-Token", token)
                .patch(patch.toString().toRequestBody(JSON)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    /** 보류 항목 삭제. */
    fun deletePending(ctx: Context, id: String): Boolean {
        val url = Config.serverUrl(ctx) + "/api/pending/$id"
        val token = Config.token(ctx)
        return try {
            val req = Request.Builder().url(url)
                .addHeader("X-Notif-Token", token)
                .delete().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }
}
