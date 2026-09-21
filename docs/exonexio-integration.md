# WebHomeTV.ExoNexio — NEXIO EXO 集成说明

以 `WebHomeTV.EXO` 分支为蓝本创建（EXO 单内核 + 后台播放默认关闭全部保留），并把内置 EXO 内核的播放行为对齐到 NEXIO 技术栈：

- 上游 media fork: <https://github.com/johnneerdael/media>（androidx/media 的 NEXIO fork，Media3 1.10.0 基线 + FireOS 修复 + DV7→DV8.1 实时转换 + Kodi 级 IEC 音频直通打包器）
- 集成参考: <https://github.com/johnneerdael/nexio>（`settings.gradle.kts` 的 `includeBuild("media")` + dependency substitution；`PlayerSettingsDataStore.kt` 的参数默认值）

## 为什么不是整体替换 media3

nexio 的官方集成方式是把 media fork 作为 submodule 复合构建（composite build）。本项目无法照搬，原因：

1. 主项目是 Gradle 9.5.1 + AGP 9.2.1，media fork 是 Gradle 8.13 + AGP 8.13.2；复合构建共享同一 Gradle 运行时，跨 AGP 大版本必然失败。
2. app 深度依赖 FongMi 魔改 media3 的专有 API（`media3-ui-danmaku` 弹幕模块 25+ 文件、`MediaEdition`/`CacheDataReader`/`PlaybackDiagnostics`、BDMV/UDF/AVS3 扩展 reader），nexio media（1.10.0 基线）没有这些模块。
3. 本项目基座已经在消费 NEXIO 技术：`third_party/exo-dv5-native`（libdovi + libplacebo 预编译）与 `ExoDv5*` 系列类就是 NEXIO DV5/DV8.1 管线的移植；N1 分支的 `ExoPassthroughAudioSink` 是其 Kodi 16BIT passthrough 思路的实现。

因此 ExoNexio 采用与基座一致的"策略集成层"：通过 Media3 稳定接缝（LoadControl / AudioSink / 提取器工厂 / 设置策略）把 NEXIO 的参数与行为移植进来，而非替换引擎源码。

## 集成内容与默认值（与 NEXIO 同步）

| NEXIO 参数（PlayerSettingsDataStore.kt） | 默认值 | 本分支实现 |
|---|---|---|
| `BufferSettings.DEFAULT_MIN_BUFFER_MS` | 20000 | `NexioPlayerSettings.NEXIO_MIN_BUFFER_MS` → LoadControl |
| `BufferSettings.DEFAULT_MAX_BUFFER_MS` | 50000 | 同上 |
| `BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS` | 3000 | 同上 |
| `BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS` | 5000 | 同上 |
| `BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB` | 350 | 同上（targetBufferBytes） |
| `BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS` | 0 | 同上（retain=false） |
| `experimentalDtsIecPassthroughEnabled` | false | 播放性能对话框「NEXIO IEC 直通」开关，接 `ExoCompressedAudioDirectPolicy` vendor-direct 路由 |
| `experimentalDv7ToDv81Enabled` | false | 「NEXIO DV7→DV8.1」开关；默认关闭时 DV7 走 HEVC HDR10 基底层（NEXIO 行为），开启后走基座 libdovi 实时 RPU 改写 |
| `fireOsCompatibilityFallbackEnabled` | false | 「NEXIO FireOS 兼容」开关（FireOS IEC 起播监督等补丁的使能位） |

## 代码位置

- `app/src/main/java/com/fongmi/android/tv/setting/NexioPlayerSettings.java` — NEXIO 默认值镜像（Java 持久化）
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoNexioIntegration.java` — 集成层（LoadControl 构建、IEC 编码提示、TrueHD masquerade 判定、FireOS 探测）
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java` — 播放器构建处接入（LoadControl 覆盖 + IEC 日志）
- `app/src/main/java/com/fongmi/android/tv/setting/PlaybackPerformanceCatalog.java` — 新增 3 个性能选项
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/PlaybackPerformanceDialog.java` — 选项 UI
- `app/src/main/java/com/fongmi/android/tv/setting/PlaybackPerformanceSetting.java` — `getDv7HandlingMode()` 尊重 NEXIO DV7 默认

## 修复记录

### 2026-09-21 第三轮：整体换用 NEXIO Kodi C++ 音频引擎（真 IEC 打包器）

**为什么放弃 N1 伪装方案**：N1 的 `ExoPassthroughAudioSink`（DTS 轨道伪装 + 原始码流直写）在目标设备上验证是错误路线；用户另一台设备上 NEXIO 应用（源码集成）TrueHD 直通成功，证明 NEXIO 的 Kodi C++ 音频引擎是可行路径。

**本轮改动**：
1. **vendored Java 侧**（`app/src/main/java/androidx/media3/`）：从 nexio media（e9297c15）原样移植 `exoplayer/audio/kodi/` 包（KodiNativeAudioSink、KodiTrueHdNativeAudioSink、KodiTrueHdEntryAudioSink + validation 运行时）、`RendererClockAwareAudioSink`、`common/util/AmazonQuirks`。包名不变 → JNI 符号匹配。
2. **fongmi 1.11 适配**：三个 sink 增补 `configure(AudioSinkConfig)` 覆写（fongmi 的 ForwardingAudioSink 会绕过 legacy 路由）；`AudioCapabilities.is*` 静态调用改指 `AmazonQuirks.is*`。
3. **native 预编译**（`third_party/nexio-native/`）：从 nexio v0.58 release APK 提取 `libkodiCppAudioSinkJNI.so` 及其 FFmpeg 依赖（avcodec/avformat/avfilter/avutil/swresample）双 ABI；DT_NEEDED 全部自洽，无 libc++_shared/mbedtls 额外依赖。
4. **接线**（`ExoUtil.buildAudioSink`）：IEC 开关开启时 → 设置 AmazonQuirks 全套 IEC packer 默认（AC3/E-AC3/DTS/DTS-HD/TrueHD 直通全开、转码关）→ 返回 `KodiTrueHdEntryAudioSink.create(baselineDefaultSink, trueHdDefaultSink)`；TrueHD 走 `KodiTrueHdNativeAudioSink`（MAT/IEC 打包），DTS/DTS-HD 走 `KodiNativeAudioSink`（真 IEC 61937 打包，不再有 4MB AudioTrack 分配问题），其余格式原样 DefaultAudioSink。
5. 上一轮的 `ExoNexioPassthroughSink`（N1 移植）删除；`ExoCompressedAudioDirectPolicy` 的 builder 层封顶保留（标准直通路径仍受益）。

### 2026-09-21 第二轮：builder 层修复（已被第三轮取代）

logcat 确认：TrueHD 在 EDID 协商阶段就被判不支持 → 解码成 PCM（17:02:36/48 两次 PCM 8ch 轨道）；DTS-HD 直通 AudioTrack 请求 2250000 字节 → AudioFlinger 4MB 分配 ENOMEM ×3 → 播放中断。builder 层补丁无法触及协商阶段，且被默认关闭的开关挡住。

### 2026-09-21 第一轮：初始集成

初版 NEXIO IEC 集成只在 `ExoUtil` 加了日志，未接入 AudioTrack 构建路径。

## 后续升级路径（可选）

若要完整替换为 nexio media 源码：

1. 把主项目 Gradle/AGP 降到 8.13/8.13.2（风险大，牵动整个工程）。
2. 或者把 nexio media 用 CI 预编译成 AAR（`publish.gradle` 已支持 `mavenRepo` 发布），替换 `third_party/maven/androidx/media3/*`，再在 app 侧补齐 `media3-ui-danmaku` 等缺失模块的源码编译。media fork 锁定 commit：`e9297c15f39947c1ae8972fa191cbd64914e81cb`（main @ 2026-05-04）。
