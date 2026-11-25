<!--
Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
SPDX-License-Identifier: GPL-3.0-only
grl-app-doc-3B86 · 安卓端 RID 查询工具说明
grl-doc-hub-2F17 仓库首页增强：徽章 / 构建 / 许可证 / 作者
-->

# GTA5 RID 查询（Android APP）

![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Version](https://img.shields.io/badge/version-1.0.0-orange.svg)

装在手机上，输入 Social Club 昵称直接查 Rockstar ID（RID），不用开电脑、不用 F12 抓包。

## 功能特性

- 输入昵称即可查询 RID，点击数字一键复制
- 官方接口 + 公开缓存双通道，优先返回实时准确结果
- 登录一次长期有效：令牌过期自动静默续期，无需反复登录
- 错误原因精确分类，不再用"缓存没数据"笼统提示
- 适配系统深浅色主题，内容垂直居中
- 作者信息可点击跳转微博 / 酷安 / GitHub

## 查询原理：双通道

| 通道 | 条件 | 准确性 | 说明 |
|---|---|---|---|
| 官方接口 | 已在 APP 内登录 R 星账号 | 高 | `scapi.rockstargames.com/profile/getprofile`，实时数据 |
| 公开缓存 | 无需登录 | 中 | `sc-cache.com/n/昵称`，约 1100 万账号缓存，可能不完整或过时 |

APP 查询时**优先走官方接口**：检测到有效登录令牌就用官方数据；令牌过期会自动续期后重试；确实拿不到官方结果才回退到缓存。结果卡片底部会标注来源。

## 安装

从本仓库 [Releases](https://github.com/jiangshangwan/gtaridlookup/releases) 下载 `GtaRidLookup-v1.0.apk`，传到手机安装（需允许安装未知来源应用）。同签名旧版可直接覆盖。

## 更新记录

**令牌续期机制修复**

- 修复"已登录却一直提示登录过期"：续期改用 R 星官方 `POST /connect/refreshaccess` 端点（携带会话 Cookie 与旧令牌，从响应 `Set-Cookie` 换取新令牌），替代此前无效的隐藏网页重载方式
- 官方查询请求头与社区网页对齐（补 `Referer` / `Origin` / `maxFriends` 参数），避免被误判为登录过期
- 续期成功后持久化新令牌与新会话 Cookie，并自动跟随续期重定向、累积 `Set-Cookie`，延长免登录周期
- 失败提示附带 HTTP 状态码，便于定位是认证问题还是网络问题
- 注意：R 星轻量续期通常在拿令牌后约 10 小时内有效；超出该窗口需走完整重认证流程，届时 APP 会提示重新登录一次

**登录持久化修复**

- 登录时连同会话 Cookie 一起持久化；令牌过期时用隐藏 WebView 自动重载社区页换取新令牌并重试查询，无需手动重新登录
- 区分「令牌过期」「昵称未收录」「网络失败」「未登录」四类失败，给出准确原因
- 续期失败时主界面状态更新为"登录态已失效，请重新登录"，与查询结果保持一致

**界面与主题**

- 主界面内容垂直居中，不再贴顶（超出屏幕时仍可滚动）
- 适配系统深浅色主题，含状态栏图标明暗
- 界面底部作者信息，名字可点击跳转对应主页

**其他修复**

- 根布局加 `fitsSystemWindows`，标题不再被状态栏遮挡
- 登录页加入加载进度、错误回调，不再无提示黑屏
- 令牌持久化到 SharedPreferences + 多域名扫描 `BearerToken`
- 登录页新增「手动填入令牌」后备入口
- 昵称查询前自动转小写（与官方脚本行为一致）

## 使用

### 方式一：登录后查询（推荐，最准确）

1. 打开 APP，点 **「登录 R 星账号」**
2. 在内置浏览器里登录你的 Social Club 账号
3. 登录成功后按返回键回到主界面（会提示"已保存登录态"）
4. 输入昵称，点 **「查询 RID」** 或键盘搜索键
5. RID 显示在结果卡片里，点击数字即复制

登录令牌与会话 Cookie 会持久保存。令牌过期时 APP 会自动静默续期；只有当会话也彻底失效（如长时间未用或 R 星强制下线）才需要重新登录一次。

### 方式二：直接查询（无需登录）

不登录直接输入昵称查询，走公开缓存。适合快速试查，但很多账号查不到（缓存覆盖不全）。

## 网络要求

- 官方接口和缓存接口都在境外，国内使用需要挂**全局代理/加速器**
- APP 本身不内置代理，请用系统级 VPN 或加速器让 APP 走代理

## 常见问题

**查不到昵称**

先确认是 Social Club 昵称（不是 Steam 名）。若确认无误仍查不到，说明缓存没有该账号——点「登录 R 星账号」登录后走官方接口再查。

**结果标注"来源：缓存（可能过时）"**

说明当前未登录、令牌过期且续期失败，或官方接口失败。缓存数据可能过时，若要准确结果请登录后再查。

**登录后查询仍走缓存**

看主界面「已登录/未登录」提示：若显示未登录，说明令牌没拿到。点「登录 R 星账号」，观察登录页顶部的状态文字——它会显示加载失败原因或"已保存登录态"。若页面加载失败，说明本 APP 没有走代理（国内必须全局代理，仅加速浏览器无效）。若页面加载成功但无令牌，用「手动填入令牌」按钮从浏览器 Cookie 复制 `BearerToken` 粘贴。

**网络请求失败**

检查代理/加速器是否让本 APP 走代理。APP 需要联网权限（已在清单声明）。

## 编译构建

```bash
git clone https://github.com/jiangshangwan/gtaridlookup.git
cd gtaridlookup
# 在 local.properties 中写入本机 Android SDK 路径：sdk.dir=/path/to/Android/Sdk
./gradlew assembleRelease
```

产出位于 `app/build/outputs/apk/release/`。工程使用 Java 17 + Gradle Wrapper，无需额外配置第三方网络库。

## 技术信息

- 包名：`io.github.jiangshangwan.gtaridlookup`
- 版本：1.0.0（versionCode 100）
- 最低系统：Android 7.0（API 24）
- 目标系统：Android 15（API 35）
- 技术栈：Java 17 + Gradle 8.13 + AGP 8.9.2，无第三方网络库（HttpURLConnection）+ WebView 登录

## 目录结构

```
app/src/main/java/io/github/jiangshangwan/gtaridlookup/
├── RidRepository.java   双通道查询、错误分类、令牌续期
├── MainActivity.java    主界面、静默续期、复制与跳转
└── LoginActivity.java   内嵌浏览器登录、令牌与 Cookie 持久化
app/src/main/res/        布局、颜色、字符串资源（含 values-night）
```

## 许可证

本项目采用 [GPL-3.0](LICENSE) 许可证开源。

## 作者

江上晚（李十六）

- 微博：[李十六的日记本](https://weibo.com/u/3725737792)
- 酷安：[江上晚](https://www.coolapk.com/u/3019478)
- GitHub：[jiangshangwan](https://github.com/jiangshangwan)

<!-- grl-app-doc-end-3B86 -->
