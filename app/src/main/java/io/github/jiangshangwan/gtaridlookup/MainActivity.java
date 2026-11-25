/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * grl-main-1F93 · 主界面控制器
 * grl-main-author-6D28 作者信息可点击链接
 * grl-main-v4-B3E9 隐藏 WebView 静默续期与登录态真实反馈
 * grl-main-v6-2C48 移除失效的 WebView 续期，续期改由仓库层纯网络执行
 */
package io.github.jiangshangwan.gtaridlookup;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 输入昵称查询 RID 的主界面。 */
public class MainActivity extends Activity {

    /** grl-pref-6A38 历史记录存储 */
    private static final String PREF = "grl_prefs";
    private static final String KEY_HISTORY = "history";
    private static final String CLUB_HOME = "https://socialclub.rockstargames.com/";

    private EditText input;
    private TextView tvRid;
    private TextView tvName;
    private TextView tvSource;
    private TextView tvMsg;
    private TextView tvLoginState;
    private RidRepository repo;
    private String currentRid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = findViewById(R.id.etNickname);
        tvRid = findViewById(R.id.tvRid);
        tvName = findViewById(R.id.tvName);
        tvSource = findViewById(R.id.tvSource);
        tvMsg = findViewById(R.id.tvMsg);
        tvLoginState = findViewById(R.id.tvLoginState);
        repo = new RidRepository(this);
        restoreSessionCookies();

        findViewById(R.id.btnQuery).setOnClickListener(v -> doQuery());
        findViewById(R.id.btnLogin).setOnClickListener(v ->
                startActivity(new Intent(this, LoginActivity.class)));

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doQuery();
                return true;
            }
            return false;
        });

        tvRid.setOnClickListener(v -> {
            if (currentRid != null) {
                copy(currentRid);
                Toast.makeText(this, "RID 已复制", Toast.LENGTH_SHORT).show();
            }
        });

        setupAuthorLinks();
    }

    /** grl-cookie-restore-2A56 登录页 Cookie 丢失时用快照恢复，保持登录态 */
    private void restoreSessionCookies() {
        try {
            String saved = getSharedPreferences(LoginActivity.PREF, MODE_PRIVATE)
                    .getString(LoginActivity.KEY_COOKIES, "");
            if (saved == null || saved.isEmpty()) { return; }
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String found = cm.getCookie(CLUB_HOME);
            if (found != null && found.contains("BearerToken=")) { return; }
            for (String part : saved.split(";")) {
                String kv = part.trim();
                if (kv.contains("=")) { cm.setCookie(CLUB_HOME, kv); }
            }
        } catch (Throwable t) { /* 恢复失败不影响登录页重新登录 */ }
    }

    /** grl-author-links-9F13 作者信息：名字可点击跳转 */
    private void setupAuthorLinks() {
        TextView tv = findViewById(R.id.tvAuthor);
        String title = getString(R.string.author_title);
        String weibo = getString(R.string.author_weibo);
        String coolapk = getString(R.string.author_coolapk);
        String github = getString(R.string.author_github);
        String full = title + "\n" + weibo + "\n" + coolapk + "\n" + github;
        SpannableString ss = new SpannableString(full);

        linkify(ss, full, weibo, "李十六的日记本", getString(R.string.url_weibo));
        linkify(ss, full, coolapk, "江上晚", getString(R.string.url_coolapk));
        linkify(ss, full, github, "jiangshangwan", getString(R.string.url_github));

        tv.setText(ss);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Color.TRANSPARENT);
    }

    /** grl-linkify-4C57 给指定名字加点击跳转与下划线 */
    private void linkify(SpannableString ss, String full, String line, String name, String url) {
        int lineStart = full.indexOf(line);
        if (lineStart < 0) { return; }
        int nameStart = full.indexOf(name, lineStart);
        if (nameStart < 0) { return; }
        final String target = url;
        int end = nameStart + name.length();
        ss.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        }, nameStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new UnderlineSpan(), nameStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.gold)),
                nameStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** grl-state-7C19 显示当前登录态 */
    @Override
    protected void onResume() {
        super.onResume();
        String token = getSharedPreferences(LoginActivity.PREF, MODE_PRIVATE)
                .getString(LoginActivity.KEY_TOKEN, "");
        if (token != null && !token.isEmpty()) {
            tvLoginState.setText("已登录 · 令牌过期会自动续期");
            tvLoginState.setTextColor(getResources().getColor(R.color.gold));
        } else {
            tvLoginState.setText("未登录 · 查询走公开缓存（可能查不到）");
            tvLoginState.setTextColor(getResources().getColor(R.color.text_sub));
        }
    }

    /** grl-query-9B52 触发查询 */
    private void doQuery() {
        String nickname = input.getText().toString().trim();
        if (nickname.isEmpty()) {
            showMsg("请输入昵称");
            return;
        }
        clearResult();
        showMsg("查询中…（首次可能需要续期，请稍候）");
        repo.query(nickname, result -> {
            if (result.success()) {
                showResult(result);
                saveHistory(result.name != null && !result.name.isEmpty() ? result.name : nickname);
            } else {
                showMsg(result.message);
                if (result.status == RidRepository.Status.AUTH_FAIL) {
                    tvLoginState.setText("登录态已失效 · 请点“登录 R 星账号”重新登录");
                    tvLoginState.setTextColor(getResources().getColor(R.color.error_text));
                }
            }
        });
    }

    /** grl-result-4D07 展示结果 */
    private void showResult(RidRepository.Result r) {
        tvMsg.setVisibility(View.GONE);
        currentRid = r.rid;
        tvRid.setText(r.rid);
        if (r.name != null && !r.name.isEmpty()) {
            tvName.setText(r.name + " · 点击 RID 复制");
            tvName.setVisibility(View.VISIBLE);
        } else {
            tvName.setVisibility(View.GONE);
        }
        tvSource.setText(r.official ? R.string.source_official : R.string.source_cache);
        tvSource.setVisibility(View.VISIBLE);
    }

    private void clearResult() {
        currentRid = null;
        tvRid.setText(R.string.result_placeholder);
        tvName.setVisibility(View.GONE);
        tvSource.setVisibility(View.GONE);
    }

    private void showMsg(String text) {
        tvMsg.setText(text);
        tvMsg.setVisibility(View.VISIBLE);
    }

    /** grl-copy-2E64 复制 RID */
    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("RID", text));
        }
    }

    private Context getSystemContext() { return this; }

    /** grl-history-8F31 保存最近昵称 */
    private void saveHistory(String name) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_HISTORY, name)
                .apply();
    }
}
