/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * grl-login-3E71 · Social Club 登录页
 * grl-login-v2-6D14 令牌捕获与持久化改造
 * grl-login-v3-A1F7 会话 Cookie 持久化，供令牌过期后静默续期
 */
package io.github.jiangshangwan.gtaridlookup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** 内嵌 WebView 登录，捕获 BearerToken 与会话 Cookie 并持久化。 */
public class LoginActivity extends Activity {

    /** grl-login-url-8D02 登录入口 */
    private static final String HOME = "https://socialclub.rockstargames.com/";

    /** grl-login-pref-2B59 与主页共享的令牌存储 */
    static final String PREF = "grl_prefs";
    static final String KEY_TOKEN = "bearer_token";
    /** grl-login-cookie-5C38 会话 Cookie 快照，用于 WebView 数据被清后恢复 */
    static final String KEY_COOKIES = "session_cookies";

    private WebView web;
    private TextView status;
    private ProgressBar progress;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        web = findViewById(R.id.webView);
        status = findViewById(R.id.tvLoginStatus);
        progress = findViewById(R.id.progress);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        applyCookieSettings(web);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                status.setText("正在加载：" + shorten(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                String token = scanBearerToken();
                if (token != null) {
                    persist(token);
                    status.setText("登录成功，登录态已保存，请按返回键");
                } else {
                    status.setText("页面已加载，但未检测到令牌。请确认已登录；若已登录仍无令牌，请点右上角手动填入。");
                }
            }

            // grl-err-fix-1C63 采用三参数重载，兼容 WebViewClient 契约
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                progress.setVisibility(View.GONE);
                status.setText("加载失败：" + err.getDescription()
                        + "（国内网络需挂全局代理；若代理只加速浏览器，本 APP 无法联网）");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
                status.setText("证书校验提示已放行，继续加载中…");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progress.setVisibility(View.VISIBLE);
                }
            }
        });

        // grl-manual-btn-5E22 手动填入令牌入口
        findViewById(R.id.btnManualToken).setOnClickListener(v -> showTokenDialog());
        web.loadUrl(HOME);
    }

    /** grl-cookie-setup-7A45 统一的 Cookie 开关，登录页与静默续期页共用 */
    static void applyCookieSettings(WebView web) {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
    }

    /** grl-token-capture-7E31 多域名扫描 BearerToken */
    static String scanBearerToken() {
        String[] domains = {
                "https://socialclub.rockstargames.com/",
                "https://www.rockstargames.com/",
                "https://scapi.rockstargames.com/",
                "https://*.rockstargames.com/"
        };
        for (String d : domains) {
            String raw = CookieManager.getInstance().getCookie(d);
            if (raw == null) { continue; }
            String[] parts = raw.split(";");
            for (String p : parts) {
                String kv = p.trim();
                if (kv.startsWith("BearerToken=")) {
                    return kv.substring("BearerToken=".length());
                }
            }
        }
        return null;
    }

    /** grl-cookie-snapshot-9B74 读取 Social Club 会话 Cookie 快照 */
    static String currentCookies() {
        try {
            return CookieManager.getInstance().getCookie(HOME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** grl-token-save-9A46 持久化令牌与会话 Cookie */
    private void persist(String token) {
        android.content.SharedPreferences.Editor ed =
                getSharedPreferences(PREF, MODE_PRIVATE).edit();
        ed.putString(KEY_TOKEN, token);
        String cookies = currentCookies();
        if (cookies != null && !cookies.isEmpty()) {
            ed.putString(KEY_COOKIES, cookies);
        }
        ed.apply();
    }

    /** grl-token-manual-0D37 手动输入令牌对话框 */
    private void showTokenDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("粘贴 BearerToken");
        et.setSingleLine(true);
        et.setTextColor(getResources().getColor(R.color.text_main));
        et.setHintTextColor(getResources().getColor(R.color.text_sub));
        new android.app.AlertDialog.Builder(this)
                .setTitle("手动填入令牌")
                .setMessage("从浏览器 Cookie 中复制 BearerToken 的值粘贴到此处")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) {
                        persist(t.startsWith("Bearer ") ? t.substring(7) : t);
                        status.setText("令牌已保存，请按返回键查询");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String shorten(String url) {
        return url != null && url.length() > 60 ? url.substring(0, 60) + "…" : url;
    }

    @Override
    public void onBackPressed() {
        String token = scanBearerToken();
        if (token != null) { persist(token); }
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean("login_touched", true).apply();
        Toast.makeText(this, token != null ? "已保存登录态" : "未检测到令牌，可继续查询缓存",
                Toast.LENGTH_SHORT).show();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); }
        super.onDestroy();
    }
}
