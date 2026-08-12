## 实施计划：修复 issue#7 闪退 + 移植 EXO 自适应降速网络保护（最小可验证范围）

基于用户选择的最小范围，本次实施两部分：① 修复 issue#7 的 WebView 渲染进程崩溃闪退；② 从 webhtv-main 移植 EXO 自适应降速网络保护。16KB librtmp 问题仅记录为后续项。

### 一、闪退修复（issue#7）
**文件**: `app/src/main/java/com/fongmi/android/tv/ui/custom/CustomWebView.java`
- 在 `webViewClient()` 的匿名 WebViewClient 中新增 `onRenderProcessGone(WebView, RenderProcessGoneDetail)` 重写（镜像 `HomeWebController.java:448` 的模式，返回 `true` 消费事件避免整进程被杀）。
- 处理逻辑：日志记录 `didCrash/priority` → 触发 `onParseError()`（由 ParseJob 走 `stop()` + `destroy()` 正常收尾）→ 返回 `true`。
- 补充 `import android.webkit.RenderProcessGoneDetail`（该文件当前未导入）。
- 说明：CustomWebView 仅被 `player/ParseJob.java` 使用（解析页嗅探视频），不在 Activity 上，无法重建视图，故正确兜底是"记日志 + 正常结束解析"，避免渲染进程崩溃拖垮整个 App。

### 二、移植 EXO 自适应降速网络保护（从 webhtv-main，最小依赖集）
**新增 5 个自包含文件**（均只依赖 java.util/标准库，从 webhtv-main 原样移植到 `app/src/main/java/com/fongmi/android/tv/player/exo/`）：
1. `ExoNetworkGuardController.java`（核心算法：safeBuffer/timeToReserve/期限驱动斜坡/轻量0.97-1.00x+深度0.85-0.97x双层）
2. `ExoNetworkProtectionPolicy.java`（AUTO_MIN_SPEED=0.85 / PREFERRED_MIN_SPEED=0.97 常量）
3. `ForwardBufferTrend.java`（缓冲水位快慢趋势 EWMA 检测）
4. `ExoNetworkGuardEligibility.java`（资格判断）
5. `ExoNetworkGuardBufferPolicy.java`（安全缓冲下限）

**改动文件**：
- `app/src/main/java/com/fongmi/android/tv/setting/ExoPerformanceSetting.java`：新增网络保护开关偏好 `KEY_NETWORK_PROTECTION`（默认开）+ `getNetworkProtectionEnabled()` / `getNetworkProtectionMinimumSpeed()`（镜像 webhtv-main lines 134-146 的偏好风格，复用现有 `Prefers` 模式）。
- `app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java`：
  - 新增字段：`networkProtectionController/trend/state/tier/reason/speed/supportedSpeed/mediaBitrate` + `networkProtectionRunnable`。
  - `setSpeed(float)`（行297）：用户手动改速时 `resetNetworkProtectionSession("user-speed")`，回到 1.0 时 `scheduleNetworkProtection(0)`。
  - 新增方法：`applyEffectiveSpeed`（复用行299 的 `setPlaybackParameters(...withSpeed(...))`）、`resetNetworkProtectionSession`、`scheduleNetworkProtection`、`evaluateNetworkProtection`、`getNetworkProtectionEvaluationDelayMs`、`getNetworkProtectionSafeBufferMs`。
  - 资格判断简化为：`playerType == EXO` + `spec` 非空（VOD 非直播）+ 用户速度=1.0 + `STATE_READY && isPlaying()`；跳过 telemetry/experiment/analytics，采用**纯 buffer 模式**（`networkEstimateKnown=false`，算法自带回退，见 webhtv 注释行72/258-263）。
  - 生命周期：`release()` 与 `switchPlayer()` 中 `App.removeCallbacks(networkProtectionRunnable)` + reset，防止换内核/释放后残留调度。

### 三、版本号 / 文档 / 发布
- `app/build.gradle`：versionCode 611→**612**，versionName 5.5.61→**5.5.62**。
- `CHANGELOG.md`：新增 `## 5.5.62 — EXO 自适应降速 + WebView 闪退修复 (2026-08-11)`（沿用现格式：无 v 前缀、em-dash、日期）；同时记录 16KB librtmp 已知问题为后续项。
- `README.md`：`最新版本：**v5.5.62**`；顺带修正"JDK 17"→"JDK 21"过时描述（工作流与 gradle 均用 21）。

### 四、验证
- 本仓库无单元测试（CI 也只跑 assemble）。验证方式：`bash gradlew :app:assembleMobileUniversalRelease`（及 leanback variant）编译通过 + 人工 diff 审查。
- 注意：release 构建要求 `local.properties` 提供签名（`app/build.gradle:12-15` 否则抛 GradleException）；若本地缺签名则记录并交由 CI 完成签名构建。

### 五、提交与发布
- `git add` + 提交（信息如 `feat: port EXO adaptive-speed network protection + fix webview renderer crash`）。
- 推送 `main`，打标签 `v5.5.62` 并推送。
- `v*` 标签触发 `.github/workflows/android-release.yml`：自动构建 6 个 APK、从 CHANGELOG 对应章节生成 Release notes、创建 GitHub Release 并上传 APK/JSON；若配置了 `CNB_TOKEN` 则同步 CNB 镜像与 TV 源（`continue-on-error`）。

### 明确不做（避免范围膨胀）
- 不移植输出模式管理 / tunneling / 三内核自动档（webhtv-main 的其余 P0，需多会话）。
- 不移植 TV-fongmi 的音/视频效果系统、在线字幕等。
- 不重建 16KB 对齐的 librtmp（仅记录后续项）。
- 不改动 CI 脚本本身、不改远程托管/观影同步等产品范围功能。