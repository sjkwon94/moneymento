package com.assethub.notifier

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 웹 대시보드를 그대로 띄우는 메인 화면.
 * 웹에 기능이 추가되면 앱도 자동으로 같이 업데이트된다.
 */
class WebActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var errorView: LinearLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FAF7F2"))
        }

        // ── WebView ──────────────────────────────────────────
        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            overScrollMode = View.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    return false   // 앱 안에서 계속 열기
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    refresh.isRefreshing = false
                    errorView.visibility = View.GONE
                    web.visibility = View.VISIBLE
                }
                override fun onReceivedError(
                    v: WebView?, req: WebResourceRequest?,
                    err: android.webkit.WebResourceError?
                ) {
                    if (req?.isForMainFrame == true) showError()
                }
            }
            webChromeClient = WebChromeClient()
        }

        // 쿠키 유지 (로그인 세션)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // ── 당겨서 새로고침 ──────────────────────────────────
        refresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#C17A55"))
            addView(web, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { web.reload() }
        }
        root.addView(refresh, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 오류 화면 ────────────────────────────────────────
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#FAF7F2"))
            addView(TextView(context).apply {
                text = "서버에 연결할 수 없습니다"
                textSize = 16f
                setTextColor(Color.parseColor("#3D3A35"))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "\n서버 주소를 확인하거나\n네트워크 상태를 점검하세요"
                textSize = 13f
                setTextColor(Color.parseColor("#8F8778"))
                gravity = Gravity.CENTER
            })
            addView(android.widget.Button(context).apply {
                text = "다시 시도"
                setBackgroundColor(Color.parseColor("#C17A55"))
                setTextColor(Color.WHITE)
                setOnClickListener { web.reload() }
            })
            addView(android.widget.Button(context).apply {
                text = "설정 열기"
                setBackgroundColor(Color.parseColor("#8F8778"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    startActivity(Intent(this@WebActivity, MainActivity::class.java))
                }
            })
        }
        root.addView(errorView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 설정 진입 버튼 (우하단 작게) ─────────────────────
        val settingsBtn = TextView(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(Color.parseColor("#8F8778"))
            setBackgroundColor(Color.parseColor("#EBE4D8"))
            setPadding(28, 18, 28, 18)
            setOnClickListener {
                startActivity(Intent(this@WebActivity, MainActivity::class.java))
            }
        }
        root.addView(settingsBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 40; rightMargin = 30
        })

        setContentView(root)
        web.loadUrl(Config.serverUrl(this))
    }

    private fun showError() {
        refresh.isRefreshing = false
        web.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 설정에서 돌아왔을 때 서버 주소가 바뀌었을 수 있음
        val current = web.url ?: ""
        val target = Config.serverUrl(this)
        if (current.isEmpty() || !current.startsWith(target)) {
            web.loadUrl(target)
        }
    }
}
