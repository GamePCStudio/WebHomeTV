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

### 2026-09-21 IEC 直通不生效修复

**现象**（logcat，pid 5605）：选择 DTS-HD MA 7.1 音轨后播放报错：
- `AudioFlinger: not enough memory for AudioTrack size=4194528` → AudioTrack init 失败（status -12 / -20），重试 3 次全败 → `ExoPlaybackException`
- HAL 层 `audio_hw_primary` 实际已成功打开 DTS-HD 裸流（format=0xc000000, ch=0x063f），硬件路径是通的

**根因**：初版 NEXIO IEC 集成只在 `ExoUtil` 加了日志，未接入 AudioTrack 构建路径。media3 标准直通缓冲按“250ms × DTS-HD 4 倍系数 × 码率”计算，DTS-HD MA 18Mbps → 2.25MB → AudioFlinger 页对齐分配 4MB，内存受限的 Amlogic 盒子直接 ENOMEM。

**修复**（`ExoCompressedAudioDirectPolicy.applyNexioIecPassthrough`）：
1. 压缩直通 AudioTrack 缓冲封顶 512KB（约 227ms@18Mbps，Kodi IEC 同量级），避开 4MB 分配失败
2. TrueHD 在 Amlogic 上无原生编码 AudioTrack 支持：把 AudioFormat 标签改为 DTS（HAL 内容嗅探真实码流），复用 N1 分支 ExoPassthroughAudioSink 的同款方案
3. 接入点为 `AudioTrackAudioOutputProvider.Builder.setAudioTrackBuilderModifier`（`build()` 前最后一改），vendor-direct 路径行为不变

## 后续升级路径（可选）

若要完整替换为 nexio media 源码：

1. 把主项目 Gradle/AGP 降到 8.13/8.13.2（风险大，牵动整个工程）。
2. 或者把 nexio media 用 CI 预编译成 AAR（`publish.gradle` 已支持 `mavenRepo` 发布），替换 `third_party/maven/androidx/media3/*`，再在 app 侧补齐 `media3-ui-danmaku` 等缺失模块的源码编译。media fork 锁定 commit：`e9297c15f39947c1ae8972fa191cbd64914e81cb`（main @ 2026-05-04）。
