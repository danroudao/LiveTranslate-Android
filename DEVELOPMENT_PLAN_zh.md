# LiveTranslate Android 移植开发文档

> 基于 [TheDeathDragon/LiveTranslate](https://github.com/TheDeathDragon/LiveTranslate)（Windows / Python / PyQt6）的 Android 端移植可行性分析与开发方案。
>
> 分析基准：仓库 `main` 分支（截至分析时，核心模块共约 1.1 万行 Python）。

---

## 目录

1. [结论摘要](#1-结论摘要)
2. [原项目架构剖析](#2-原项目架构剖析)
3. [移植可行性逐模块评估](#3-移植可行性逐模块评估)
4. [技术选型](#4-技术选型)
5. [Android 端目标架构](#5-android-端目标架构)
6. [模块级详细设计](#6-模块级详细设计)
7. [关键难点与解决方案](#7-关键难点与解决方案)
8. [分阶段实施计划](#8-分阶段实施计划)
9. [权限与合规清单](#9-权限与合规清单)
10. [测试策略](#10-测试策略)
11. [风险清单与应对](#11-风险清单与应对)
12. [代码映射表（Python → Kotlin）](#12-代码映射表python--kotlin)

---

## 1. 结论摘要

**结论：可以移植，且具备很高的实用价值。** 原项目的核心管线（音频捕获 → VAD → ASR → LLM 翻译 → 悬浮字幕）在 Android 上全部有对应能力，但**每个环节都需要换技术底座**，不能直接跑 Python 代码：

| 环节 | Windows 原实现 | Android 对应方案 | 可行性 |
|------|---------------|-----------------|--------|
| 系统音频捕获 | WASAPI loopback（PyAudioWPatch） | `MediaProjection` + `AudioPlaybackCapture`（API 29+） | ✅ 可行（有平台限制，见 §7.1） |
| 麦克风混音 | PyAudio 输入流 | `AudioRecord` + 软件混音 | ✅ 可行 |
| VAD | Silero VAD（PyTorch） | Silero VAD ONNX（sherpa-onnx 内置） | ✅ 可行 |
| ASR（本地） | faster-whisper / SenseVoice / FunASR（PyTorch+CUDA） | sherpa-onnx（ONNX 推理）跑 SenseVoice / Whisper | ✅ 可行（小模型）；FunASR Nano（3.5GB）❌ 不现实 |
| ASR（远程） | HTTP 远程 Whisper 服务器（`asr_server.py`） | 复用同一 wire protocol，OkHttp 客户端 | ✅ 最佳路径（手机零负担） |
| LLM 翻译 | openai SDK + httpx（流式 SSE） | OkHttp SSE 流式解析 | ✅ 完全可移植 |
| 悬浮字幕 | PyQt6 透明置顶窗口 | `WindowManager` 悬浮窗 / 无障碍服务覆盖层 | ✅ 可行 |
| 设置 UI | PyQt6 控制面板 | Jetpack Compose | ✅ 可行 |
| 进程隔离 | `multiprocessing` spawn worker | 独立 `:asr` 进程（Service） | ✅ 可行 |

**推荐策略（分阶段）**：
- **Phase 1（推荐首发）**：远程 ASR 模式 —— 手机只做"捕获 + VAD + 网络请求 + 悬浮字幕"，对应原项目 `asr_remote.py` + `asr_server.py` 的成熟链路，手机端 APK 体积小、发热低、无模型下载。
- **Phase 2**：本地 ASR —— 集成 sherpa-onnx，跑 SenseVoice-Small 量化版 / Whisper tiny/base，覆盖无局域网 GPU 服务器的场景。
- **Phase 3**：多语言字幕窗口、通知栏字幕、基准测试、导出转录等增强。

---

## 2. 原项目架构剖析

### 2.1 数据管线

```
系统音频 (WASAPI loopback, 32ms chunk)
   └─▶ VAD (Silero, 渐进静音 + 自适应阈值 + 回溯切分)
         └─▶ ASR worker 子进程 (faster-whisper / SenseVoice / FunASR-Nano / Anime-Whisper / Remote)
               └─▶ 文本后处理 (空文本/噪声过滤、语言过滤、增量句切分 pysbd、回音去重)
                     └─▶ LLM 翻译 (OpenAI 兼容 API, 流式 SSE, JSON schema, 上下文)
                           └─▶ PyQt6 悬浮字幕窗 + 独立字幕窗 (OBS 用) + 转录文件
```

### 2.2 关键设计决策（移植时必须保留的"灵魂"）

1. **进程隔离**：ASR 模型跑在 `multiprocessing` 子进程（`asr_client.py` → `asr_worker.py`，Pipe IPC）。原因：模型库原生内存泄漏、崩溃不影响 UI 主进程，并支持 worker 崩溃自动重启（最多 3 次）、RSS 超阈值自动回收（recycle）。**Android 上对应方案：独立 `:asr` 进程 + Binder/套接字 IPC，或至少独立线程 + 崩溃捕获。**
2. **低延迟 VAD 参数**：32ms chunk（512 样本 @16kHz）匹配 Silero 原生窗口；渐进静音（buffer 越长接受越短的停顿）、自适应静音（P75×1.2，0.3~2.0s）、回溯切分、语音密度过滤、短句合并。**这些纯逻辑应 1:1 移植到 Kotlin。**
3. **增量 ASR（interim）**：长句边说边识别——pysbd 分句 + 逗号兜底切分 + 比例裁剪 + 回音去重（`_strip_committed_overlap`）。翻译延迟从"整段说完"降到"说完一句"。**Android 上必须保留，这是体验核心。**
4. **翻译健壮性**：10s 超时、重复循环检测（`RepetitionError`）、`no_system_role`（Qwen-MT 等拒绝 system 角色）、`no_think`（`extra_body.enable_thinking=false`）、`json_response`（`{"t": "..."}` schema）、`overrides`/`extra_body` 合并、每模型独立配置。
5. **模型双源下载**：ModelScope / HuggingFace 可切换，断点续传，`cache_path` 可配置，下载失败不影响当前 worker。
6. **异步 UI 更新**：所有跨线程 UI 更新走 Qt 信号；设置 300ms 防抖自动保存（原子写 `.tmp` + `os.replace`）。

### 2.3 模块清单（11,388 行 Python）

| 文件 | 行数 | 职责 |
|------|-----:|------|
| main.py | 2343 | 入口、管线编排（capture 线程 / ASR 队列线程 / 翻译线程池）、worker 生命周期 |
| subtitle_overlay.py | 1286 | PyQt6 悬浮字幕窗（置顶/穿透/拖拽/14 主题/流式逐字显示） |
| control_panel.py | 1510 | 设置面板（7 个标签页：VAD/ASR、翻译、样式、字幕、基准、缓存、更新日志） |
| subtitle_window.py / subtitle_settings.py | 934/651 | 独立字幕窗（OBS 捕获、描边文字、动画） |
| audio_capture.py | 443 | WASAPI loopback + 麦克风混音 + 设备热切换 |
| vad_processor.py | 387 | Silero VAD + 能量模式 + 渐进/自适应静音 + 回溯切分 |
| translator.py | 432 | OpenAI 兼容翻译客户端（流式/JSON/上下文/代理） |
| model_manager.py | 749 | 模型检测、下载（双源）、缓存路径管理 |
| dialogs.py | 749 | 向导、下载进度、模型编辑对话框 |
| asr_*（7 个文件） | ~1000 | 各 ASR 后端 + 远程客户端 + worker 进程 |
| benchmark.py / transcript_writer.py / log_window.py / i18n.py | ~500 | 基准测试 / 转录导出 / 日志窗 / i18n |

---

## 3. 移植可行性逐模块评估

### 3.1 音频捕获：✅ 可行（替代方案成熟）

原实现：PyAudioWPatch 打开 WASAPI loopback 设备，后台线程读取 → 重采样 16kHz 单声道 float32 → 队列。

Android 对应：
- **`MediaProjection` + `AudioPlaybackCaptureConfiguration`**（API 29+）捕获设备上其他 App 的音频输出。这是 Google 官方提供的"系统音频回环"，与 WASAPI loopback 定位一致。
- **API 29–33**：默认捕获所有允许捕获的 App 声音（`AudioAttributes` 的 `USAGE_MEDIA`/`USAGE_GAME` 等）。
- **API 34+**：目标 App 可声明 `android:allowAudioPlaybackCapture="false"` 拒绝被捕获（浏览器/网课 App 若声明则无法捕获）。
- **Android 15（API 35）**：使用 `MediaProjection` 必须伴随前台服务且 `foregroundServiceType="mediaProjection"`。
- **DRM 内容（Netflix 等）**：受保护内容始终无法捕获，与平台无关，需在文档中说明。

麦克风混音：`AudioRecord`（麦克风流）+ 软件混音，逻辑与原项目 `_mic_buf` 拼接混音完全一致。

采样率：`AudioPlaybackCapture` 输出通常为 48kHz 立体声 PCM（float 或 16bit），需重采样到 16kHz 单声道 float32 —— 原项目 `_resample_to_mono` 的线性插值逻辑可直接照搬。

### 3.2 VAD：✅ 可行（纯逻辑 + ONNX 模型）

原实现：Silero VAD v5（PyTorch），32ms 窗口置信度；渐进静音 / 自适应静音 / 回溯切分 / 密度过滤 / 短句合并。

Android 对应：
- sherpa-onnx 内置 Silero VAD ONNX（约 2MB），提供 `VoiceActivityDetector`，输出逐帧置信度。
- **建议**：VAD 状态机（静音计时、渐进 tier、P75 自适应、回溯切分、pre-speech 环缓冲）是纯 Kotlin 逻辑，逐函数移植 `vad_processor.py`（387 行）。
- 注意 sherpa-onnx VAD 是"逐 512 样本窗口"输出置信度，与原项目"每 chunk 一帧置信度 + 置信度历史回溯"的模式需做适配：可以把 sherpa-onnx 当成"置信度提供器"，状态机自己写。

### 3.3 ASR 本地引擎：⚠️ 有条件可行

| 原引擎 | Android 方案 | 评价 |
|--------|-------------|------|
| faster-whisper（CTranslate2） | whisper.cpp（GGML）或 sherpa-onnx Whisper ONNX | ✅ tiny/base/small 可跑；medium/large 太慢 |
| SenseVoice（FunASR） | **sherpa-onnx SenseVoice ONNX** | ✅ **首选**：官方发布 int8 量化版，手机可实时，中/日/英/粤/韩/阿等多语种，支持热词、带时间戳 |
| FunASR-Nano（Qwen3-0.6B 底座） | — | ❌ 3.5GB 权重 + 强算力需求，手机不现实，放弃 |
| Anime-Whisper | whisper.cpp 变体（`anime-whisper` 是 fine-tuned Whisper） | ⚠️ 可尝试导出 GGML/ONNX，优先级低 |
| **Remote Whisper（HTTP）** | **直接复用协议** | ✅✅ 最佳路径，见 §6.3 |

sherpa-onnx 是 k2-fsa 社区的语音推理库，官方发布 `io.github.k2-fsa:sherpa-onnx` Android AAR，支持 VAD + SenseVoice + Whisper + Paraformer + 热词，全部 ONNX Runtime 推理，CPU 实时率良好，且内置 Kotlin/Java API。这是本项目本地 ASR 的技术底座。

### 3.4 LLM 翻译：✅ 完全可移植

原实现：`openai` SDK + `httpx`，POST `/chat/completions`，`stream=true` SSE 逐字返回；支持 `response_format` JSON schema、`extra_body`、代理、超时、重复检测、上下文历史。

Android 对应：
- `OkHttp` 发起 POST + SSE 逐行解析（`data: {...}` 前缀剥离，`[DONE]` 终止），或 `Retrofit` + `okhttp-sse`。
- 所有逻辑（prompt 模板、`{source_lang}/{target_lang}/{context}` 格式化、`no_system_role` 合并、`json_response` 提取 `{"t": ...}`、`_check_repetition` 重复检测、上下文窗口）**1:1 移植为 Kotlin 类 `LlmTranslator`**。
- 注意原项目对"代理"的处理（`trust_env=False` 绕过系统代理）：Android 上默认直连，HTTP 代理按需配置（`ProxySelector`）。

### 3.5 悬浮字幕：✅ 可行（两条路线）

原实现：PyQt6 无边框透明置顶窗，支持鼠标穿透、拖拽、14 套配色、流式逐字渲染、OBS 独立字幕窗。

Android 对应两条路线：

1. **悬浮窗路线（推荐首发）**：`SYSTEM_ALERT_WINDOW`（"显示在其他应用上层"权限，用户手动授权）+ `WindowManager.addView`，`TYPE_APPLICATION_OVERLAY`。支持拖动（`onTouch`）、透明度、字号/颜色配置。类似原项目 overlay。
2. **无障碍服务路线（增强）**：`AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY`，可在任意应用上方显示且**不需要悬浮窗权限**，还能感知当前前台 App（可做"仅在看视频 App 时显示字幕"的智能策略）。Android 13+ 无障碍覆盖层有尺寸限制（约 2/3 屏幕），适合字幕条形态。

渲染：单行/双行（原文+译文）字幕条，`Canvas` 描边文字（对应原项目 QPainterPath 描边），流式逐字更新用 `SpannableString` 或直接重绘。14 套配色主题可直接复用为 JSON 资源。

### 3.6 设置与模型管理：✅ 可行

- 设置界面：Jetpack Compose 重做（7 个标签页 → 单 Activity 多 Tab / 底部导航）。
- 设置持久化：`DataStore`（替代 `user_settings.json` 原子写）。
- 模型下载：原项目是"下载到本地目录"。Android 上模型放在 `context.getExternalFilesDir()`（免存储权限），下载用 `OkHttp` + 断点续传（`Range` 头），双源（HuggingFace/ModelScope 国内镜像）保留。首次启动引导页复用原项目 SetupWizard 流程。

---

## 4. 技术选型

### 4.1 方案对比

| 方案 | 说明 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| **A. Kotlin 原生 + sherpa-onnx（推荐）** | Kotlin + Compose + OkHttp + sherpa-onnx AAR | 性能好、发热可控、生态成熟、JNI 封装完善、可发布 Play 商店 | 开发量较大 | ✅ **采用** |
| B. Python 打包（Chaquopy / Kivy / BeeWare） | 尽量复用 Python 代码 | 复用率高 | PyQt6/WASAPI/pyaudiowpatch 全不可用，torch/funasr 在 Android 上无法安装，性能差 | ❌ 否决 |
| C. Termux 跑 Python | 手机端 Linux 环境 | 零开发 | 无悬浮窗/媒体投影权限模型，无法正规分发，体验差 | ❌ 否决 |
| D. Flutter + sherpa-onnx 插件 | 跨平台 | sherpa-onnx 官方有 Flutter 支持 | 音频捕获/悬浮窗/无障碍仍需原生插件，多一层 | ⚠️ 备选（若未来要出 iOS 版可考虑） |

### 4.2 关键依赖清单（Android 侧）

| 用途 | 库 |
|------|-----|
| ASR / VAD / 本地语音模型 | `com.github.k2-fsa:sherpa-onnx`（AAR，含 ONNX Runtime） |
| 网络（远程 ASR + LLM SSE） | OkHttp（+ `okhttp-sse`）或 Retrofit |
| UI | Jetpack Compose（Material 3） |
| 持久化 | DataStore（Preferences） |
| 音频 | `MediaProjection` / `AudioRecord`（系统 API） |
| 协程 | Kotlin Coroutines + Flow |
| DI（可选） | Hilt |
| 崩溃上报（可选） | Firebase Crashlytics / 自建 |

---

## 5. Android 端目标架构

```
┌────────────────────────────────────────────────────────────┐
│ App 主进程 (com.example.livetranslate)                      │
│                                                            │
│  MainActivity (Compose UI)                                 │
│    ├─ 设置页 / 模型管理页 / 状态监控页                      │
│    ├─ MediaProjection 授权引导                             │
│    └─ 悬浮窗管理 (OverlayController)                       │
│                                                            │
│  PipelineService (前台服务, mediaProjection 类型)          │
│    ├─ AudioCapturer ── MediaProjection AudioRecord 回环    │
│    │     └─ resample → 16kHz mono float32 (32ms chunk)    │
│    ├─ MicMixer ── AudioRecord 麦克风混音                    │
│    ├─ VadProcessor (Kotlin 移植 + sherpa-onnx VAD)         │
│    ├─ SpeechSegmenter (渐进/自适应静音/回溯切分)            │
│    ├─ AsrClient ── (远程 HTTP) 或 (本地 sherpa-onnx 线程)  │
│    ├─ InterimSplitter (pysbd 逻辑移植 + 回音去重)           │
│    ├─ LlmTranslator (OkHttp SSE 流式)                      │
│    └─ OverlayUpdater ── 悬浮窗流式更新                     │
│                                                            │
│  :asr 进程 (仅本地 ASR 模式启用)                           │
│    └─ LocalAsrEngine (sherpa-onnx, 崩溃隔离)               │
└────────────────────────────────────────────────────────────┘
```

### 5.1 进程与线程模型

| 组件 | 所在线程/进程 | 说明 |
|------|--------------|------|
| 音频回调 | 捕获线程（`AudioRecord` 读循环） | 与 Python `_read_loop` 对应 |
| VAD 状态机 | 捕获线程内联 | 32ms/帧，低开销 |
| ASR 队列 + 分段处理 | 单线程调度器（`Channel`/`HandlerThread`） | 对应 Python `_asr_loop` |
| 本地 ASR 推理 | 独立线程（或 `:asr` 进程） | 阻塞调用不卡主线程 |
| 翻译 | `Dispatchers.IO` 线程池（上限 8） | 对应 `ThreadPoolExecutor(max_workers=8)` |
| UI 更新 | 主线程（`Handler`/Compose state） | 所有跨线程更新走主线程 post |

### 5.2 与 Python 进程模型的对位

| Python（Windows） | Android |
|-------------------|---------|
| 主进程 = Qt UI + 管线编排 | 主进程 = Activity + `PipelineService`（同进程） |
| ASR worker 子进程（`mp.spawn` + Pipe） | 本地 ASR 模式：`android:process=":asr"` 的 `LocalAsrService`，IPC 用 Binder/Messenger 或直接套接字；远程模式无需子进程 |
| worker 崩溃自动重启（3 次上限） | `:asr` 进程死亡回调 → Service 重建；或线程内 try/catch + 引擎重建 |
| worker RSS 超阈值回收 | 本地引擎线程：定期检查 `Debug.getMemoryInfo`，超阈值重建引擎实例 |

> 简化建议：Phase 2 本地 ASR 先用"主进程内独立线程 + 引擎实例重建"实现，`:asr` 进程隔离作为后续加固项。远程 ASR 模式本来就不需要子进程（Python 版也是进程内 `RemoteASREngine`）。

---

## 6. 模块级详细设计

### 6.1 AudioCapturer（对应 `audio_capture.py`）

```
AudioCapturer
├─ start(projectionData: Intent) : 用户授权 MediaProjection 后
│    MediaProjectionManager.getMediaProjection() 
│    → AudioPlaybackCaptureConfiguration.Builder(projection)
│        .addMatchingUsage(USAGE_MEDIA).addMatchingUsage(USAGE_GAME)
│        .addMatchingUsage(USAGE_UNKNOWN)   // 可按需扩展
│    → AudioRecord(48000, CHANNEL_IN_STEREO, ENCODING_PCM_FLOAT,
│                  AUDIO_SOURCE_REMOTE_SUBMIX 等价物 = MediaProjection 内部)
├─ loop() : 读取 32ms 帧 → 混音 → 重采样 16k mono float32 → Channel
├─ MicMixer : AudioRecord(麦克风) 独立读循环，缓冲拼接 + 逐 chunk 混音
├─ 设备/会话热切换 : 捕获会话丢失回调 → 重新授权引导
└─ 兼容性兜底 : API < 29 或授权被拒 → 降级为"仅麦克风模式"
```

要点：
- **必须** `AudioRecord.ENCODING_PCM_FLOAT`（若设备不支持则 `PCM_16BIT` + 转换），buffer 大小按 `AudioRecord.getMinBufferSize` 的 2 倍。
- 重采样实现：线性插值（原项目同款算法），或 `android.media.AudioResampler`（API 31+，性能更好，可选）。
- 捕获静默时段持续输出零 chunk（对应 `_loopback_disabled` 时输出 `np.zeros`），保持 VAD 状态机时钟稳定。
- 前台服务类型：`android:foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限（API 34+ 动态申请），`FOREGROUND_SERVICE`（API 28+）。

### 6.2 VadProcessor（对应 `vad_processor.py`，387 行逐函数移植）

保留全部行为：

| 原逻辑 | Kotlin 实现 |
|--------|-------------|
| Silero 置信度（torch） | sherpa-onnx `VoiceActivityDetector`（窗口 512@16k） |
| 能量模式 / disabled 模式 | 用 RMS 阈值替代（保留配置项） |
| 渐进静音 tier（3s/6s/10s → 1.0/0.5/0.25） | 纯逻辑移植 |
| 自适应静音（P75×1.2，0.3~2.0s 钳制） | 纯逻辑移植 |
| 回溯切分（平滑置信度历史找最低谷） | 纯逻辑移植 |
| 语音密度过滤（<25% 阈值以上 chunk 丢弃） | 纯逻辑移植 |
| 短段合并（`_is_speaking=false` 但保留 buffer） | 纯逻辑移植 |
| pre-speech 环缓冲（3 chunk ≈ 96ms） | 纯逻辑移植 |
| `trim_front` / `force_flush` / `peek_buffer` | 接口对应实现 |

接口设计（Kotlin）：

```kotlin
class VadProcessor(
    val sampleRate: Int = 16000,
    val chunkDuration: Float = 0.032f,
) {
    fun processChunk(chunk: FloatArray): FloatArray?   // 返回完整语音段或 null
    fun flush(): FloatArray?
    fun forceFlush(): FloatArray?
    fun trimFront(samples: Int)
    fun peekBuffer(): Pair<FloatArray, Float>?
    fun updateSettings(settings: VadSettings)
    val lastConfidence: Float
    val isSpeaking: Boolean
}
```

> sherpa-onnx 的 `VoiceActivityDetector` 使用 `SpeechSegment`（窗口+滑窗），可配置 `windowSize=512`、`silenceDuration`、`speechPad`。**建议只把它当置信度源**，状态机保留自有实现以完整继承原项目的低延迟调优。

### 6.3 AsrClient（远程 + 本地双实现）

**远程模式（Phase 1 核心，对应 `asr_remote.py` + `asr_server.py`）**

Wire protocol 完全复用（`asr_server.py` 已在 Windows/Linux GPU 机上验证）：

```
POST http://<host>:8765/transcribe
Body: [uint32 lang_len][lang utf-8 bytes][float32 PCM 16kHz mono]
Resp: {"text": "...", "language": "ja", "elapsed": 0.42}

GET  http://<host>:8765/health
Resp: {"status": "ok", "model": "large-v3"}
```

- OkHttp 客户端，`content-type: application/octet-stream`。
- 连接健康检查放后台线程（对应 Python `__init__` 里同步探测，Android 必须异步）。
- 服务器地址/语言提示/超时（默认 30s，connect 5s）可配置。
- **附加价值**：原项目 `asr_server.py` 可直接部署在用户已有的 GPU 机器上，手机端零 ASR 成本，同时天然规避 Android 本地推理的发热与兼容问题。

**本地模式（Phase 2，对应 `asr_engine.py` / `asr_sensevoice.py`）**

- 首选模型：**SenseVoice-Small int8 量化 ONNX**（约 250MB，sherpa-onnx 官方支持，`--model-type=sense-voice`，支持 `language` 参数与热词）。
- 备选：whisper-tiny/base（`--model-type=whisper`，英文/通用）。
- 推理接口保持与远程模式一致：`fun transcribe(audio: FloatArray, wordTimestamps: Boolean=false): AsrResult?`，上层管线无感知切换。

### 6.4 LlmTranslator（对应 `translator.py`，432 行逐函数移植）

```kotlin
class LlmTranslator(
    val apiBase: String, val apiKey: String, val model: String,
    val targetLanguage: String = "zh", val maxTokens: Int = 256,
    val temperature: Double = 0.3, val streaming: Boolean = true,
    val systemPrompt: String? = null, val noSystemRole: Boolean = false,
    val noThink: Boolean = true, val jsonResponse: Boolean = false,
    val timeoutSeconds: Int = 10, val overrides: Map<String, Any>? = null,
    val extraBody: Map<String, Any>? = null,
    val contextTurns: Int = 0,
) {
    fun translate(text: String, sourceLang: String): String
    fun translateFlow(text: String, sourceLang: String): Flow<String>  // 流式逐字
    fun withTargetLanguage(lang: String): LlmTranslator
}
```

实现要点：
- SSE 解析：`okhttp-sse` `EventSource`；或手动逐行读 `Response.body.source()`，剥离 `data: ` 前缀，遇 `[DONE]` 结束；处理 `[DONE]` 前最后一个 `data: {usage...}`（对应 `stream_options.include_usage`）。
- 总超时：请求发出后计时 `timeoutSeconds` 截止（对应 Python 版 deadline 检查）。
- 请求体组装 `buildRequestKwargs` 对应 `_build_request_kwargs`：
  - `messages`：`system` 角色（`noSystemRole` 时合并进 user 消息）→ 可选上下文历史 → 当前文本。
  - `response_format`：`json_response=true` 时 `{"type":"json_schema", ...schema {"t": "string"}...}`。
  - `extra_body`：`noThink` 注入 `{"enable_thinking": false}` 并合并自定义项。
  - `overrides`：temperature/top_p/max_tokens/frequency_penalty/presence_penalty/seed 白名单。
- 响应后处理：`json_response` 提取 `{"t": ...}`；`_check_repetition` 重复环检测（长度 ≥8 循环段）；`RepetitionError` 提示用户。
- 上下文历史：`contextTurns` 对 `(原文, 译文)` 最近 N 轮，两种注入方式（prompt `{context}` 模板 或 多轮 messages）都保留。
- 多目标语言：`withTargetLanguage` 复用同一 HTTP 客户端（对应 `_translate_extra_langs` 线程池并行）。

### 6.5 管线编排（对应 `main.py` 的 `LiveTranslateApp`）

```
PipelineService (startForeground)
├─ CoroutineScope(SupervisorJob + Dispatchers.Default)
├─ audioChannel: Channel<AudioChunk>(capacity=100)      // 对应 queue.Queue(maxsize=100)
├─ asrChannel: Channel<AsrJob>(capacity=16)             // 对应 _asr_queue(maxsize=16)
├─ 捕获协程 : AudioCapturer.flow() → VadProcessor → 产出段或 interim 定时器
├─ 调度协程 : 消费 asrChannel → 远程/本地 ASR → 文本后处理
│     └─ Interim 状态机 (pending/committed_tail/trim/回音去重)
├─ 翻译协程池 : supervisorScope + repeat(8) { launch { ... } }
│     └─ LlmTranslator.translateFlow → OverlayUpdater 流式更新
└─ 状态上报 : StateFlow<PipelineState> → UI / 通知栏 / 悬浮窗
```

保留的行为细节：
- 暂停/恢复（对应 `pause()`/`resume()`）：暂停时丢弃音频但保持 VAD 时钟。
- 队列满时丢最旧段（对应 `_enqueue_asr` 的 drop-oldest）。
- ASR 未就绪时丢弃段（对应 `_asr_ready` 标志）。
- 语言过滤：ASR 检测语言 ≠ 设定语言时丢弃（对应 `_process_segment` 中过滤逻辑）。
- 同语言跳过翻译（`source_lang == target_lang`）。
- 噪声过滤：≥2s 段 ≤3 个字母数字字符丢弃。
- 内存监控：`Debug.getMemoryInfo` 定期采样 + 日志（对应 `_mem_snapshot` 30s 定时）。

### 6.6 Overlay（对应 `subtitle_overlay.py` / `subtitle_window.py`）

悬浮字幕窗（`OverlayService`）：
- `WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY)`，`FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`，`pixelFormat=TRANSLUCENT`。
- 视图结构：可拖动头部（暂停/清除/设置/退出按钮）+ 字幕区（原文小字在上、译文大字在下，对应 `show_original`）。
- 流式渲染：翻译中间结果 50ms 节流刷新（对应 `update_streaming_signal` 的 50ms QTimer throttle）。
- 样式系统：14 套主题（Dracula/Nord/Monokai/Tokyo Night/Catppuccin 等）移植为 Kotlin 数据类 `SubtitleStyle`（背景色、原文/译文字体、字号、描边色、透明度），持久化 DataStore。
- 位置记忆：拖动后保存归一化坐标（对应 `overlay_x/y/w/h` 防抖保存）。
- 屏幕方向/尺寸变化：`onConfigurationChanged` 重新布局。

无障碍字幕条（增强，Phase 3）：
- `AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY`，`FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_FOCUSABLE`。
- 可感知前台包名（`event.packageName`），按白名单显示。
- 注意 API 33+ 覆盖层限制：字幕条高度保持在屏幕 2/3 以内，滚动更新。

通知栏镜像（可选）：`Notification` 大文本样式实时显示最近一条译文，锁屏可见。

### 6.7 设置与引导（对应 `control_panel.py` + `dialogs.py`）

设置项分组（Compose 页面）：
1. **音频**：捕获开关（MediaProjection 授权）、麦克风混音开关、设备选择（蓝牙/有线/内置，对应 `audio_device`）。
2. **VAD/ASR**：VAD 模式（silero/energy/disabled）、阈值、最小/最大语音时长、静音模式（自动/固定）、静音时长、ASR 引擎（远程/本地）、服务器 URL、模型选择（SenseVoice/Whisper）、语言、padding 秒数。
3. **翻译**：模型列表（名称/API Base/Key/模型名/代理）、流式开关、JSON 输出、上下文轮数、no_think、overrides/extra_body 高级参数、超时、提示词模板（含 4 套预设 daily/esports/anime/webid）。
4. **字幕**：字号/颜色/描边/透明度/位置/主题（14 套）。
5. **模型管理**：下载源（HuggingFace/ModelScope）、已下载模型、删除/重新下载、存储占用。
6. **统计**：ASR/翻译计数、token 消耗、费用估算（对应 MonitorBar）、CPU/内存。
7. **更新日志**：i18n/CHANGELOG 渲染。

首次启动流程（对应 SetupWizard）：选择模型下载源 → 引导授予悬浮窗权限 → 引导授予媒体投影权限 → 配置翻译 API → 开始使用。

### 6.8 ModelManager（对应 `model_manager.py`）

- 模型目录：`context.getExternalFilesDir("models")`（免存储权限；卸载即清）。
- 下载：OkHttp + `Range` 断点续传 + 进度回调（对应 ModelDownloadDialog 进度条）；双源 URL 生成逻辑保留（ModelScope 国内 / HF 镜像）。
- 校验：下载完成后检查关键文件存在与大小（对应 `is_asr_cached` / `get_missing_models`）。
- 磁盘占用统计用于设置页展示。

---

## 7. 关键难点与解决方案

### 7.1 MediaProjection 音频捕获的平台限制

| 限制 | 说明 | 应对 |
|------|------|------|
| 需用户授权 | 每次"开始捕获"需 `startActivityForResult(mediaProjectionManager.createScreenCaptureIntent())` | 授权结果持久化判断；会话失效回调重新引导；**捕获随授权失效自动停止**，需检测 `onStop` 后重启 |
| API 34+ 可被目标 App 拒绝 | `allowAudioPlaybackCapture="false"` 的 App 无法捕获 | 文档说明；提供"仅麦克风模式"兜底 |
| DRM 内容不可捕获 | 平台硬限制 | 文档说明（Netflix/DRM 视频无法翻译） |
| Android 15 前台服务要求 | `foregroundServiceType="mediaProjection"` | 提前声明，启动顺序：先申请权限再 startForeground |
| 捕获的是"混合输出" | 无逐 App 分流 | 与原 WASAPI 行为一致，无额外工作 |
| 悬浮窗权限冲突 | 部分厂商 ROM 悬浮窗权限入口深 | 引导页提供跳转 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`；提供"无障碍覆盖层"备选路线 |

### 7.2 本地 ASR 的算力与功耗

- 只推荐 SenseVoice-Small int8（~250MB）与 whisper-tiny/base。
- 推理线程绑定小核风险 → 用 `HandlerThread(THREAD_PRIORITY_AUDIO)` 或让系统调度；开启"仅在充电时允许本地 ASR"选项。
- 发热降级：连续 N 段超时或电池温度过高 → 自动提示切换到远程模式。
- 首次推理预热（对应 Python 版 padding bucket 概念：`sensevoice_pad_seconds` / `whisper_pad_seconds` 输入填充减少分帧边缘错误）。

### 7.3 SSE 流式与断网

- 断网/超时 → 当前段标记失败（对应 `update_translation(msg_id, "[error: ...]")`），不阻塞后续段（翻译线程池隔离）。
- OkHttp 重试策略：仅对 `ConnectionError` 类做 1 次退避重试，`4xx` 不重试（对应 Python 版异常分类）。
- 电量优化：`ConnectivityManager` 监听，无网时暂停翻译只留 ASR（可选）。

### 7.4 悬浮窗在游戏/全屏场景

- 游戏沉浸模式（Immersive Mode）下 `TYPE_APPLICATION_OVERLAY` 仍可显示，但部分设备有"游戏加速/免打扰"拦截 → 提供无障碍覆盖层备选。
- 多显示器/折叠屏：用 `DisplayManager` 取当前默认 display 布局，字幕条固定底部安全区（`WindowInsets`）。

### 7.5 后台保活

- 前台服务 + 通知（`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`）。
- 厂商白名单引导（设置页"电池优化白名单"帮助）。
- 不做流氓保活；服务被杀后由用户手动重启，或 `START_STICKY` 恢复（注意 Android 14+ 后台启动前台服务限制，重启需用户交互或媒体投影会话已存在）。

### 7.6 与原项目协议/配置兼容

- `config.yaml` / `user_settings.json` 的结构保留为 DataStore 的键名，便于两边配置互导（可选功能：从剪贴板导入 JSON 配置）。
- 远程 ASR 服务器协议完全兼容，可同时服务 Windows 端与 Android 端。

---

## 8. 分阶段实施计划

### Phase 1 —— 远程 ASR 核心闭环（MVP，建议 4–6 周）

**目标**：手机捕获系统音频 → VAD → 远程 ASR → LLM 流式翻译 → 悬浮字幕。可用、可测、可演示。

- [ ] 工程骨架：`PipelineService` 前台服务、Compose 设置页、DataStore
- [ ] `AudioCapturer`（MediaProjection + 重采样 + 麦克风混音）
- [ ] `VadProcessor`（Kotlin 全量移植 + 单元测试）
- [ ] `RemoteAsrClient`（复用 wire protocol，对接现有 `asr_server.py`）
- [ ] `LlmTranslator`（SSE 流式 + 全部选项 + 单元测试）
- [ ] 管线编排（Channel + 协程，interim 增量 ASR 含句切分/回音去重）
- [ ] 悬浮字幕 `OverlayService`（拖动/样式/流式更新）
- [ ] 引导流程（权限三步走）+ 首版测试矩阵

**验收标准**：局域网内手机观看日语直播，2 秒内出第一句译文，字幕悬浮在任意 App 上方，断网/服务器不可达不崩溃。

### Phase 2 —— 本地 ASR 引擎（建议 4 周）

- [ ] 集成 sherpa-onnx AAR，SenseVoice-Small int8 + Whisper tiny/base
- [ ] `LocalAsrEngine`（独立线程 + 实例重建回收）
- [ ] 模型下载/管理页（断点续传、双源、存储统计）
- [ ] 引擎热切换（远程 ↔ 本地，对应 `_switch_asr_engine` 的回滚语义：新引擎加载失败自动恢复旧引擎）
- [ ] 发热监控与降级提示

**验收标准**：中端手机（骁龙 7 系）SenseVoice 本地实时率 < 0.5，连续使用 30 分钟温度可接受。

### Phase 3 —— 体验增强（按需排期）

- [ ] 无障碍字幕条（免悬浮窗权限路线）+ 前台 App 白名单
- [ ] 多目标语言并行翻译（对应 `_translate_extra_langs`）
- [ ] 转录导出（对应 `transcript_writer.py`，保存到 `Documents`）
- [ ] 基准测试页（对应 `benchmark.py`：译文延迟/质量对比）
- [ ] i18n（zh/en 双语，对应 `i18n/`）
- [ ] 配置导入导出（与 Windows 版互通）
- [ ] 通知栏字幕镜像、锁屏显示

---

## 9. 权限与合规清单

| 权限 | 用途 | 级别 |
|------|------|------|
| `SYSTEM_ALERT_WINDOW` | 悬浮字幕窗 | 特殊授权（设置页手动开启） |
| `FOREGROUND_SERVICE` | 捕获/翻译常驻 | 普通（运行时声明） |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 15 媒体投影前台服务 | 普通（API 34+） |
| `POST_NOTIFICATIONS` | 前台服务通知 | 运行时 |
| `RECORD_AUDIO` | 麦克风混音 | 运行时 |
| `INTERNET` | 远程 ASR / LLM API | 普通 |
| `ACCESS_NETWORK_STATE` | 网络状态监听 | 普通 |
| `BIND_ACCESSIBILITY_SERVICE`（Phase 3） | 无障碍字幕条 | 特殊授权 |
| `WAKE_LOCK`（可选） | 屏幕常亮选项 | 普通 |

合规注意：
- Play 商店政策：`SYSTEM_ALERT_WINDOW` 与无障碍服务需在商店声明用途；媒体投影用于"音频字幕翻译"属允许的辅助功能场景。
- 隐私：音频仅本地处理或发送到**用户自配置**的服务器；需在隐私政策中说明"音频数据可能发送至用户配置的翻译/ASR 服务器"。
- 应用内不要收集音频数据上传第三方（默认不提供任何遥测）。

---

## 10. 测试策略

| 层 | 内容 |
|----|------|
| 单元测试（JVM） | VadProcessor 状态机（用固定置信度序列回放 Python 测试向量）、句子切分器（中日英样例）、回音去重、RepetitionError 检测、SSE 解析器（mock OkHttp ResponseBody）、JSON 提取 |
| **一致性测试（关键）** | 将 `vad_processor.py` / `translator.py` / `main.py` 的文本后处理逻辑用**同一组输入输出样例**分别在 Python 与 Kotlin 跑，断言一致 —— 保证移植不失真 |
| 仪器测试 | MediaProjection 授权流程（UiAutomator）、悬浮窗显示/拖动、前台服务生命周期 |
| 真机矩阵 | Android 10/12/14/15 各一台；覆盖：视频 App（B站/YouTube）、语音通话（VoIP）、音乐 App；后台锁屏场景 |
| 性能 | 捕获到译文首字延迟（P50/P95）、CPU/内存/温度 30 分钟曲线、SSE 丢包恢复 |
| 兼容 | 远程 ASR 服务器与 Windows 原版互测（同一服务器同时服务两端） |

---

## 11. 风险清单与应对

| 风险 | 等级 | 应对 |
|------|------|------|
| MediaProjection 授权频繁失效（部分 ROM） | 高 | 会话失效自动重启捕获 + 引导重新授权；提供仅麦克风模式 |
| 目标 App 拒绝捕获（API 34+） | 中 | 文档明示；引导用户切换浏览器/播放器；麦克风兜底 |
| 本地 ASR 发热/耗电 | 中 | 默认远程模式；int8 量化模型；充电限定选项；温度降级 |
| sherpa-onnx SenseVoice 精度低于 FunASR 全精度 | 中 | 接受 trade-off（手机场景）；远程模式保精度需求 |
| 厂商 ROM 悬浮窗/保活策略差异 | 中 | 白名单引导页；无障碍覆盖层备选；START_STICKY |
| 流式翻译成本/延迟波动 | 低 | 超时兜底、重复检测、错误提示不阻塞 |
| 双端（Win/Android）配置语义漂移 | 低 | 保留原键名；配置导入导出互通 |

---

## 12. 代码映射表（Python → Kotlin）

| Python 文件 | Kotlin 模块 | 移植方式 |
|-------------|-------------|----------|
| `audio_capture.py` | `audio/AudioCapturer.kt`、`audio/MicMixer.kt` | 重写（API 替换）+ 重采样算法移植 |
| `vad_processor.py` | `vad/VadProcessor.kt` | 逐函数移植 + sherpa-onnx 置信度源 |
| `main.py`（管线部分） | `pipeline/PipelineService.kt`、`pipeline/InterimSplitter.kt` | 重写为协程版，逻辑保留 |
| `asr_remote.py` + `asr_server.py` 协议 | `asr/RemoteAsrClient.kt` | 协议复用，网络层重写 |
| `asr_engine.py` / `asr_sensevoice.py` | `asr/LocalAsrEngine.kt`（sherpa-onnx） | 后端替换，接口对齐 |
| `asr_client.py` / `asr_worker.py` | `asr/AsrWorkerIsolation.kt`（Phase 2+） | 进程隔离重设计 |
| `translator.py` | `translate/LlmTranslator.kt`、`translate/SseParser.kt` | 逐函数移植 |
| `subtitle_overlay.py` / `subtitle_window.py` | `overlay/OverlayService.kt`、`overlay/SubtitleStyle.kt` | 重写（WindowManager），主题数据移植 |
| `control_panel.py` / `dialogs.py` | `ui/`（Compose 设置页、引导页） | 重写 |
| `model_manager.py` | `model/ModelManager.kt` | 重写（OkHttp 下载 + 双源） |
| `benchmark.py` | `benchmark/BenchmarkRunner.kt` | 重写 |
| `transcript_writer.py` | `transcript/TranscriptWriter.kt` | 重写 |
| `i18n.py` + `i18n/*.yaml` | `res/values*/strings.xml` | 资源化 |
| `config.yaml` / `user_settings.json` | `DataStore`（键名保留） | 结构映射 |

---

## 附：给开发者的快速起步建议

1. 先跑通原项目（Windows + 一台有 NVIDIA GPU 的机器，部署 `asr_server.py`），用 curl 验证 `/transcribe` 与 `/health`，为 Android 端调试提供真实后端。
2. Phase 1 尽量不动 VAD/翻译逻辑，只做"搬运"：Kotlin 实现与 Python 版跑同一组测试向量，保证行为一致后再接 UI。
3. 悬浮窗先做最简形态（固定底部字幕条），再补拖动/主题/无障碍路线。
4. 全程保持 `PipelineService` 与 UI 解耦，用 `StateFlow` 暴露状态，便于后续接通知栏/无障碍/基准测试。
