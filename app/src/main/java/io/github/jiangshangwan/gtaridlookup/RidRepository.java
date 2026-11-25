/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * grl-repo-2F84 · RID 双通道查询仓库
 * grl-repo-v3-3D91 错误分类 + 令牌过期静默续期重试
 * grl-repo-v5-A3E7 改用 refreshaccess 端点续期 + 官方请求头对齐 + HTTP 诊断
 * grl-repo-v7-F2B6 手动跟随续期重定向并累积 Set-Cookie，持久化新会话 Cookie
 */
package io.github.jiangshangwan.gtaridlookup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RID 查询：官方接口优先（需 BearerToken），sc-cache 缓存兜底（免登录）。
 * 令牌过期时调用 socialclub 的 refreshaccess 端点静默续期后重试。
 */
final class RidRepository {

    /** grl-endpoint-6D19 接口地址 */
    private static final String API_OFFICIAL =
            "https://scapi.rockstargames.com/profile/getprofile?nickname=";
    private static final String API_CACHE = "https://sc-cache.com/n/";
    /** grl-refresh-ep-5C29 R 星令牌续期端点（POST accessToken，Set-Cookie 返回新令牌） */
    private static final String API_REFRESH =
            "https://socialclub.rockstargames.com/connect/refreshaccess";
    private static final String ORIGIN = "https://socialclub.rockstargames.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** grl-ctx-4A62 应用上下文，用于读取持久化令牌 */
    private final Context appContext;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    RidRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** grl-refresh-hook-0E42 续期钩子：续期已改为纯网络，在仓库内部同步执行 */
    interface TokenRefresher {
        void refresh(RefreshCallback cb);
    }

    interface RefreshCallback {
        void onDone(String newToken);
    }

    /** grl-refresh-noop-7D14 兼容旧调用，续期无需外层 WebView 注入 */
    void setTokenRefresher(TokenRefresher r) {
        // 续期在仓库内部完成，此处保留空实现兼容旧调用点。
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
        final int httpCode;

        private Result(String rid, String name, boolean official, String message,
                       Status status, int httpCode) {
            this.rid = rid;
            this.name = name;
            this.official = official;
            this.message = message;
            this.status = status;
            this.httpCode = httpCode;
        }

        static Result ok(String rid, String name, boolean official) {
            return new Result(rid, name, official, null, Status.OK, 200);
        }

        static Result fail(String message) {
            return new Result(null, null, false, message, Status.NOT_FOUND, 0);
        }

        static Result fail(String message, Status status) {
            return new Result(null, null, false, message, status, 0);
        }

        static Result fail(String message, Status status, int httpCode) {
            return new Result(null, null, false, message, status, httpCode);
        }

        boolean success() { return rid != null; }
    }

    /** grl-outcome-2C77 官方请求内部结果 */
    private static final class Outcome {
        final Status status;
        final Result result;
        final int httpCode;
        Outcome(Status status, Result result, int httpCode) {
            this.status = status;
            this.result = result;
            this.httpCode = httpCode;
        }
    }

    /** grl-refresh-result-9B73 续期结果 */
    private static final class RefreshResult {
        final boolean success;
        final String token;
        final int httpCode;
        final String detail;
        RefreshResult(boolean success, String token, int httpCode, String detail) {
            this.success = success;
            this.token = token;
            this.httpCode = httpCode;
            this.detail = detail;
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

            // grl-retry-auth-6F35 401/403：调用 refreshaccess 续期后用新令牌重试
            if (first.status == Status.AUTH_FAIL) {
                RefreshResult refreshed = refreshAccessToken(token);
                if (refreshed.success && refreshed.token != null && !refreshed.token.isEmpty()) {
                    saveToken(refreshed.token);
                    Outcome again = fetchOfficial(normalized, refreshed.token);
                    if (again.status == Status.OK) {
                        deliver(cb, again.result);
                        return;
                    }
                    deliver(cb, withCacheFallback(normalized,
                            Result.fail("续期后官方仍返回 " + again.httpCode
                                    + "，请确认昵称或重新登录", again.status, again.httpCode)));
                    return;
                }
                deliver(cb, withCacheFallback(normalized,
                        Result.fail("登录已过期（官方 " + first.httpCode
                                        + " / 续期 " + refreshed.httpCode
                                        + "），请重新登录 R 星账号",
                                Status.AUTH_FAIL, first.httpCode)));
                return;
            }

            deliver(cb, withCacheFallback(normalized,
                    Result.fail(messageFor(first.status, first.httpCode),
                            first.status, first.httpCode)));
        });
    }

    /** grl-msg-for-4A58 按失败类型给出准确提示；带 HTTP 码便于定位 */
    private String messageFor(Status s, int code) {
        String suffix = code > 0 ? "（HTTP " + code + "）" : "";
        switch (s) {
            case NETWORK:
                return "网络请求失败，请检查网络或加速器（需全局代理）" + suffix;
            case NOT_FOUND:
                return "官方无此昵称，缓存也未收录，请确认是 Social Club 昵称" + suffix;
            case AUTH_FAIL:
                return "登录已过期，请重新登录 R 星账号" + suffix;
            case NO_TOKEN:
                return "未登录，请先登录 R 星账号";
            default:
                return "查询失败" + suffix;
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

    /**
     * grl-refresh-net-2E64 令牌续期：POST refreshaccess（表单 accessToken + 会话 Cookie）。
     * 手动跟随重定向并累积所有 Set-Cookie——新 BearerToken 可能出现在重定向链中间。
     */
    private RefreshResult refreshAccessToken(String token) {
        Map<String, String> jar = loadCookieJar(token);
        String method = "POST";
        URL current;
        try {
            current = new URL(API_REFRESH);
        } catch (Throwable t) {
            return new RefreshResult(false, null, -1, "bad url");
        }
        int lastCode = -1;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) current.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                conn.setRequestProperty("Referer", ORIGIN + "/");
                conn.setRequestProperty("Origin", ORIGIN);
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Cookie", buildCookieHeader(jar, token));

                if ("POST".equals(method)) {
                    conn.setDoOutput(true);
                    String body = "accessToken=" + URLEncoder.encode(token, "UTF-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                lastCode = code;
                String location = conn.getHeaderField("Location");
                List<String> cookies = collectSetCookies(conn);
                String newBearer = null;
                for (String raw : cookies) {
                    Map.Entry<String, String> pair = firstCookiePair(raw);
                    if (pair == null) { continue; }
                    jar.put(pair.getKey(), pair.getValue());
                    if ("BearerToken".equalsIgnoreCase(pair.getKey())) {
                        newBearer = pair.getValue();
                    }
                }
                conn.disconnect();
                conn = null;

                if (newBearer != null && !newBearer.isEmpty() && !newBearer.equals(token)) {
                    persistCookies(jar);
                    return new RefreshResult(true, newBearer, code, "ok");
                }
                if (isRedirect(code) && location != null && !location.isEmpty()) {
                    current = new URL(current, location);
                    method = "GET";
                    continue;
                }
                return new RefreshResult(false, null, code, "no new BearerToken");
            } catch (Throwable t) {
                if (conn != null) { try { conn.disconnect(); } catch (Throwable ignore) {} }
                return new RefreshResult(false, null, lastCode,
                        t.getClass().getSimpleName());
            }
        }
        return new RefreshResult(false, null, lastCode, "too many redirects");
    }

    /** grl-cookie-jar-1E74 从持久化快照构造 Cookie 容器 */
    private Map<String, String> loadCookieJar(String token) {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        String saved = readCookiesString();
        if (!saved.isEmpty()) {
            for (String part : saved.split(";")) {
                Map.Entry<String, String> pair = keyValue(part.trim());
                if (pair != null) { jar.put(pair.getKey(), pair.getValue()); }
            }
        }
        jar.put("BearerToken", token);
        jar.put("AutoLoginCheck", "1");
        return jar;
    }

    /** grl-cookie-header-3A91 由 Cookie 容器拼接请求头 */
    private String buildCookieHeader(Map<String, String> jar, String token) {
        jar.put("BearerToken", token);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) { sb.append("; "); }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** grl-set-cookies-6C28 收集一次响应中的所有 Set-Cookie 头 */
    private List<String> collectSetCookies(HttpURLConnection conn) {
        List<String> out = new ArrayList<String>();
        Map<String, List<String>> headers = conn.getHeaderFields();
        if (headers == null) { return out; }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || !"Set-Cookie".equalsIgnoreCase(key)) { continue; }
            List<String> values = entry.getValue();
            if (values != null) { out.addAll(values); }
        }
        return out;
    }

    /** grl-first-pair-8F35 取 Set-Cookie 行的第一组 name=value（即 Cookie 本体） */
    private Map.Entry<String, String> firstCookiePair(String rawSetCookie) {
        if (rawSetCookie == null) { return null; }
        String body = rawSetCookie.split(";", 2)[0].trim();
        return keyValue(body);
    }

    /** grl-key-value-4B62 解析 name=value；非法片段返回 null */
    private Map.Entry<String, String> keyValue(String kv) {
        if (kv == null) { return null; }
        int pos = kv.indexOf('=');
        if (pos <= 0) { return null; }
        return new java.util.AbstractMap.SimpleEntry<String, String>(
                kv.substring(0, pos).trim(), kv.substring(pos + 1).trim());
    }

    /** grl-redirect-check-0D47 判定 3xx 重定向状态码 */
    private boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** grl-cookies-read-7A15 读取持久化的会话 Cookie 快照 */
    private String readCookiesString() {
        try {
            String saved = appContext.getSharedPreferences(LoginActivity.PREF, 0)
                    .getString(LoginActivity.KEY_COOKIES, "");
            return saved == null ? "" : saved;
        } catch (Throwable t) {
            return "";
        }
    }

    /** grl-cookies-persist-2E90 续期后写回新的会话 Cookie 快照，供下次续期使用 */
    private void persistCookies(Map<String, String> jar) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) { sb.append("; "); }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            appContext.getSharedPreferences(LoginActivity.PREF, 0).edit()
                    .putString(LoginActivity.KEY_COOKIES, sb.toString()).apply();
        } catch (Throwable t) { /* 写回失败不影响本次查询 */ }
    }

    /** grl-official-4C22 官方接口：请求头与社区网页对齐，区分错误类型 */
    private Outcome fetchOfficial(String nickname, String token) {
        try {
            String url = API_OFFICIAL + URLEncoder.encode(nickname, "UTF-8") + "&maxFriends=3";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Referer", ORIGIN + "/");
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", UA);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                conn.disconnect();
                return new Outcome(Status.AUTH_FAIL, null, code);
            }
            if (code == 404) {
                conn.disconnect();
                return new Outcome(Status.NOT_FOUND, null, code);
            }
            if (code != 200) {
                conn.disconnect();
                return new Outcome(Status.NETWORK, null, code);
            }
            String body = readBody(conn);
            conn.disconnect();
            Result parsed = parseOfficial(body);
            if (parsed != null) {
                return new Outcome(Status.OK, parsed, code);
            }
            return new Outcome(Status.NOT_FOUND, null, code);
        } catch (Throwable t) {
            return new Outcome(Status.NETWORK, null, -1);
        }
    }

    /** grl-token-9E63 读取登录令牌：持久化令牌优先，CookieManager 兜底 */
    private String readBearerToken() {
        // grl-token-pref-8C15 持久化令牌为权威来源（续期后同步更新）
        try {
            SharedPreferences sp = appContext.getSharedPreferences(LoginActivity.PREF, 0);
            String saved = sp.getString(LoginActivity.KEY_TOKEN, "");
            if (saved != null && !saved.isEmpty()) { return saved; }
        } catch (Throwable t) { /* 读取失败走 Cookie */ }
        // grl-token-cookie-3F44 Cookie 兜底
        String cookieToken = LoginActivity.scanBearerToken();
        return cookieToken == null ? "" : cookieToken;
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
            conn.setRequestProperty("User-Agent", UA);
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
