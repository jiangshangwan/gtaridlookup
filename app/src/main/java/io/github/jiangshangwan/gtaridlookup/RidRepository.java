/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * grl-repo-2F84 · RID 双通道查询仓库
 * grl-repo-v3-3D91 错误分类 + 令牌过期静默续期重试
 */
package io.github.jiangshangwan.gtaridlookup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RID 查询：官方接口优先（需 BearerToken），sc-cache 缓存兜底（免登录）。
 * 令牌过期时通过会话 Cookie 静默续期后重试，避免"显示已登录却走缓存"。
 */
final class RidRepository {

    /** grl-endpoint-6D19 接口地址 */
    private static final String API_OFFICIAL =
            "https://scapi.rockstargames.com/profile/getprofile?nickname=";
    private static final String API_CACHE = "https://sc-cache.com/n/";

    /** grl-ctx-4A62 应用上下文，用于读取持久化令牌 */
    private final Context appContext;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TokenRefresher refresher;

    RidRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** grl-refresh-hook-0E42 由 Activity 注入静默续期实现（需主线程 WebView） */
    interface TokenRefresher {
        void refresh(RefreshCallback cb);
    }

    interface RefreshCallback {
        void onDone(String newToken);
    }

    void setTokenRefresher(TokenRefresher r) {
        this.refresher = r;
    }

    interface Callback {
        void onResult(Result result);
    }

    /** grl-status-8E26 官方接口结果分类 */
    enum Status { OK, AUTH_FAIL, NOT_FOUND, NETWORK, NO_TOKEN }

    /** grl-model-8A47 查询结果 */
    static final class Result {
        final String rid;
        final String name;
        final boolean official;
        final String message;
        final Status status;

        private Result(String rid, String name, boolean official, String message, Status status) {
            this.rid = rid;
            this.name = name;
            this.official = official;
            this.message = message;
            this.status = status;
        }

        static Result ok(String rid, String name, boolean official) {
            return new Result(rid, name, official, null, Status.OK);
        }

        static Result fail(String message) {
            return new Result(null, null, false, message, Status.NOT_FOUND);
        }

        static Result fail(String message, Status status) {
            return new Result(null, null, false, message, status);
        }

        boolean success() { return rid != null; }
    }

    /** grl-outcome-2C77 官方请求内部结果 */
    private static final class Outcome {
        final Status status;
        final Result result;
        Outcome(Status status, Result result) {
            this.status = status;
            this.result = result;
        }
    }

    /** grl-query-lower-1E83 昵称统一小写（与官方脚本行为一致） */
    void query(final String nickname, final Callback cb) {
        final String normalized = nickname == null ? "" : nickname.trim().toLowerCase();
        pool.execute(() -> {
            String token = readBearerToken();
            if (token == null || token.isEmpty()) {
                deliver(cb, withCacheFallback(normalized,
                        Result.fail("未登录，请先登录 R 星账号", Status.NO_TOKEN)));
                return;
            }

            Outcome first = fetchOfficial(normalized, token);
            if (first.status == Status.OK) {
                deliver(cb, first.result);
                return;
            }

            // grl-retry-auth-6F35 令牌失效：主线程静默续期后用新令牌重试
            if (first.status == Status.AUTH_FAIL && refresher != null) {
                String fresh = refreshOnMainThread();
                if (fresh != null && !fresh.isEmpty()) {
                    saveToken(fresh);
                    Outcome again = fetchOfficial(normalized, fresh);
                    if (again.status == Status.OK) {
                        deliver(cb, again.result);
                        return;
                    }
                }
                deliver(cb, withCacheFallback(normalized,
                        Result.fail("登录已过期，请重新登录 R 星账号（或检查网络代理）",
                                Status.AUTH_FAIL)));
                return;
            }

            deliver(cb, withCacheFallback(normalized,
                    Result.fail(messageFor(first.status), first.status)));
        });
    }

    /** grl-msg-for-4A58 按失败类型给出准确提示，避免误导为"需要登录" */
    private String messageFor(Status s) {
        switch (s) {
            case NETWORK:
                return "网络请求失败，请检查网络或加速器（需全局代理）";
            case NOT_FOUND:
                return "官方无此昵称，缓存也未收录，请确认是 Social Club 昵称";
            case AUTH_FAIL:
                return "登录已过期，请重新登录 R 星账号";
            case NO_TOKEN:
                return "未登录，请先登录 R 星账号";
            default:
                return "查询失败";
        }
    }

    /** grl-cache-fallback-7C12 缓存兜底：官方结果失败时再试 sc-cache */
    private Result withCacheFallback(String nickname, Result fallback) {
        Result cache = queryCache(nickname);
        return cache.success() ? cache : fallback;
    }

    /** grl-deliver-5D34 统一回传主线程 */
    private void deliver(Callback cb, Result r) {
        final Result out = r;
        main.post(() -> cb.onResult(out));
    }

    /** grl-refresh-sync-9E08 工作线程等待主线程 WebView 续期完成（带超时） */
    private String refreshOnMainThread() {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] holder = new String[1];
        main.post(() -> {
            try {
                refresher.refresh(token -> {
                    holder[0] = token;
                    latch.countDown();
                });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(18, TimeUnit.SECONDS)) { return null; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder[0];
    }

    /** grl-official-4C22 官方接口：区分认证失败/未收录/网络错误 */
    private Outcome fetchOfficial(String nickname, String token) {
        try {
            String url = API_OFFICIAL + URLEncoder.encode(nickname, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                conn.disconnect();
                return new Outcome(Status.AUTH_FAIL, null);
            }
            if (code == 404) {
                conn.disconnect();
                return new Outcome(Status.NOT_FOUND, null);
            }
            if (code != 200) {
                conn.disconnect();
                return new Outcome(Status.NETWORK, null);
            }
            String body = readBody(conn);
            conn.disconnect();
            Result parsed = parseOfficial(body);
            if (parsed != null) {
                return new Outcome(Status.OK, parsed);
            }
            return new Outcome(Status.NOT_FOUND, null);
        } catch (Throwable t) {
            return new Outcome(Status.NETWORK, null);
        }
    }

    /** grl-token-9E63 读取登录令牌：CookieManager 优先（最新），SharedPreferences 兜底 */
    private String readBearerToken() {
        // grl-token-cookie-3F44 Cookie 中通常是当前最新令牌
        String cookieToken = LoginActivity.scanBearerToken();
        if (cookieToken != null && !cookieToken.isEmpty()) { return cookieToken; }

        // grl-token-pref-8C15 持久化令牌兜底（WebView 数据被清时仍可用）
        try {
            SharedPreferences sp = appContext.getSharedPreferences(LoginActivity.PREF, 0);
            String saved = sp.getString(LoginActivity.KEY_TOKEN, "");
            if (saved != null && !saved.isEmpty()) { return saved; }
        } catch (Throwable t) { /* 读取失败视为未登录 */ }
        return "";
    }

    /** grl-token-save-v3-1B62 续期成功后写回新令牌 */
    private void saveToken(String token) {
        try {
            appContext.getSharedPreferences(LoginActivity.PREF, 0).edit()
                    .putString(LoginActivity.KEY_TOKEN, token).apply();
        } catch (Throwable t) { /* 写回失败不影响本次查询 */ }
    }

    /** grl-parse-official-7B15 解析官方响应 */
    private Result parseOfficial(String body) {
        if (body == null || body.isEmpty()) { return null; }
        try {
            JSONObject root = new JSONObject(body);
            JSONArray accs = root.optJSONArray("accounts");
            if (accs == null && root.has("data")) {
                JSONObject data = root.optJSONObject("data");
                if (data != null) { accs = data.optJSONArray("accounts"); }
            }
            if (accs == null || accs.length() == 0) { return null; }
            JSONObject ra = accs.optJSONObject(0);
            if (ra == null) { return null; }
            JSONObject account = ra.optJSONObject("rockstarAccount");
            if (account == null) { account = ra; }
            Object id = account.opt("rockstarId");
            if (id == null) { return null; }
            return Result.ok(String.valueOf(id), account.optString("name", ""), true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** grl-cache-3A90 sc-cache 兜底查询 */
    private Result queryCache(String nickname) {
        try {
            String url = API_CACHE + URLEncoder.encode(nickname, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return Result.fail("未查到该昵称（官方需登录，缓存也无记录）");
            }
            String body = readBody(conn);
            conn.disconnect();
            Result r = parseCache(body, nickname);
            return r != null ? r : Result.fail("未查到该昵称，请确认是 Social Club 昵称");
        } catch (Throwable t) {
            return Result.fail("网络请求失败，请检查网络或加速器");
        }
    }

    /** grl-parse-cache-5F27 解析缓存响应 */
    private Result parseCache(String body, String fallbackName) {
        if (body == null || body.isEmpty()) { return null; }
        try {
            JSONArray arr = new JSONArray(body);
            if (arr.length() == 0) { return null; }
            JSONObject o = arr.optJSONObject(0);
            if (o == null) { return null; }
            Object id = o.opt("id");
            if (id == null) { return null; }
            String name = o.optString("name", fallbackName);
            return Result.ok(String.valueOf(id), name, false);
        } catch (Throwable t) {
            return null;
        }
    }

    /** grl-readbody-1D48 读取响应体 */
    private String readBody(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) { sb.append(line); }
        br.close();
        return sb.toString();
    }
}
