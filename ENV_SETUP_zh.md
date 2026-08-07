# LiveTranslate Android 移植 — 环境搭建与垂直切片跑通记录

> 状态：✅ **垂直切片 Demo 已在 Android 14 模拟器中完整跑通**（2026-08-06）
>
> 链路：模拟器播放英文语音 → MediaProjection 捕获系统音频 → 能量 VAD 分段 →
> 远程 ASR（faster-whisper tiny，HTTP 协议复用原项目 `asr_server.py`）→
> DeepSeek LLM 流式翻译 → TYPE_APPLICATION_OVERLAY 悬浮字幕窗

---

## 1. 环境拓扑

```
宿主机 (drd@, 无 sudo 密码, 43G RAM, 16 核, /dev/kvm)
├── Docker (有镜像加速: docker.1ms.run 等)
├── Android SDK: /home/drd/android-sdk (已预装 build-tools 35/36, platform 36)
│     ├── cmdline-tools/latest (sdkmanager/avdmanager)
│     ├── emulator/ (35.1.4, 手动解压 + 手写 package.xml)
│     ├── platform-tools/ (adb)
│     └── system-images/android-34/google_apis/x86_64 (r14, zip 自带 package.xml)
│
├── 容器 android-emu  (ubuntu:22.04, --device /dev/kvm, -p 5554/5555)
│     ├── openjdk-17 + gradle 8.7 (/opt/gradle-8.7)
│     ├── AVD: test34 (pixel_5, 无头 -no-window -gpu swiftshader_indirect)
│     ├── /workspace = Android 工程 (docker cp 同步)
│     └── 模拟器 emulator-5554 (Android 14 / API 34)
│
└── 容器 asr-server (ubuntu:22.04, -p 8765:8765)
      ├── /opt/venv: faster-whisper, fastapi, uvicorn
      ├── 模型: /root/faster-whisper-tiny (hf-mirror.com 手动下载, CPU int8)
      └── asr_server.py --model /root/faster-whisper-tiny --device cpu --compute-type int8
```

**网络链路**：模拟器 guest → NAT → 172.17.0.1:8765（宿主 docker0 网关）→
docker-proxy → asr-server 容器 :8765。

---

## 2. 关键踩坑记录（复现时必读）

| # | 问题 | 解决方案 |
|---|------|---------|
| 1 | dl.google.com TLS 被墙（curl SSL unexpected eof） | 改用 `http://dl.google.com`（明文 HTTP 通）或腾讯镜像 `mirrors.cloud.tencent.com/AndroidSDK/` |
| 2 | docker 官方 `google/android-emulator` 镜像全部 403 | 放弃镜像，自建 ubuntu 容器 + 手动装 SDK |
| 3 | 宿主无 root/sudo 密码，缺图形库 | 全走 Docker 容器（容器内 root 随意 apt） |
| 4 | avdmanager 报 "emulator package must be installed" | 手动解压 emulator 后需自写 `emulator/package.xml`（**必须带 XML namespace** `http://schemas.android.com/repository/android/common/02`，否则 sdkmanager 报 corrupted） |
| 5 | `sys-img2-*.xml` 404 | system image 元数据在 `sys-img/google_apis/sys-img2-1.xml`（子目录） |
| 6 | system image zip 顶层目录是 `x86_64/` 而非完整路径 | 解压到 `system-images/android-34/google_apis/` 即可 |
| 7 | faster-whisper 模型从 HF 下载失败 | `hf-mirror.com` 手动 curl 4 个文件到本地目录，`--model /root/faster-whisper-tiny` 直读本地 |
| 8 | **Android 9+ 默认禁明文 HTTP** → "ASR 服务器不可达" | Manifest `android:usesCleartextTraffic="true"` |
| 9 | adb input tap 坐标错乱（shell glob 展开 `[]`） | `echo "$B"` 必须加引号；用 sed 提取坐标 |
| 10 | Kotlin companion object 内声明 interface | 访问路径变成 `Companion.Listener`；移到类体 |
| 11 | `MediaProjection` import 包名 | 是 `android.media.projection.MediaProjection` 不是 `android.media` |
| 12 | unzip 交互覆盖提示中断脚本 | 用 `unzip -oq`（overwrite + quiet） |

---

## 3. 垂直切片 Demo 说明

工程：`android-app/`（Kotlin + View，无 Compose，minSdk 29 / targetSdk 34）
APK：`android-app/dist/LiveTranslate-demo.apk`（12.8MB）

### 模块 ↔ 原项目映射

| Kotlin 文件 | 对应 Python | 移植内容 |
|-------------|------------|---------|
| `pipeline/AudioCapturer.kt` | `audio_capture.py` | MediaProjection + AudioPlaybackCapture，48k 立体声 → 16k 单声道（线性插值重采样） |
| `pipeline/VadProcessor.kt` | `vad_processor.py` | 能量 VAD + 分段（最小/最大语音时长、静音切段） |
| `pipeline/CaptureService.kt` | `main.py` LiveTranslateApp | 前台服务编排：捕获线程 → 段队列(16) → ASR 线程 → 翻译线程池 |
| `net/RemoteAsrClient.kt` | `asr_remote.py` | 二进制协议 `[uint32 lang_len][lang][float32 PCM]`，与 asr_server.py 完全兼容 |
| `net/LlmTranslator.kt` | `translator.py` | OpenAI SSE 流式、no_think、json_response、重复检测 |
| `pipeline/OverlayManager.kt` | `subtitle_overlay.py` | TYPE_APPLICATION_OVERLAY 悬浮窗（原文+译文、可拖动、描边） |
| `MainActivity.kt` | `control_panel.py` + `dialogs.py` | 权限引导 + 配置 + 测试音频播放 |

### 已验证实测数据（faster-whisper tiny CPU）

```
ASR [en] 796ms: Hello everyone, this is a live translation test.
译文: 大家好，这是一次实时翻译测试。
ASR [en] 329ms: We are testing the audio capture pipeline on Android. Please.
译文: 我们正在Android上测试音频采集流程。请继续。
ASR [en] 323ms: Check the subtitle overlay on top of the screen.
译文: 检查一下屏幕顶部的字幕叠加。
```

端到端延迟：播放语音 → 悬浮窗译文 ≈ 2~4 秒（tiny 模型；换 small/medium 或 GPU 可更快更准）。

---

## 4. 复现步骤

### 4.1 启动环境

```bash
# asr-server（先于模拟器）
docker start asr-server        # 已包含模型与依赖，自动启动 uvicorn
curl http://127.0.0.1:8765/health   # {"status":"ok",...}

# android-emu 容器 + 模拟器
docker start android-emu
docker exec android-emu bash -c 'export PATH=/opt/android-sdk/platform-tools:$PATH && adb wait-for-device && adb shell getprop sys.boot_completed'
```

### 4.2 安装与授权（模拟器 UI 自动化）

```bash
ADB="docker exec android-emu /opt/android-sdk/platform-tools/adb"
$ADB install -r /workspace/app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm grant com.example.livetranslate android.permission.POST_NOTIFICATIONS
$ADB shell pm grant com.example.livetranslate android.permission.RECORD_AUDIO
$ADB shell appops set com.example.livetranslate SYSTEM_ALERT_WINDOW allow
$ADB shell am start -n com.example.livetranslate/.MainActivity --es DEEPSEEK_KEY sk-xxx
# 点击"① 授权并开始捕获翻译"(540,708) → 对话框点 "Start now"(842,1597)
# 点击"② 播放测试语音"(540,840)  → 观察 logcat
$ADB logcat -d -s CaptureService:*
```

### 4.3 重新编译

```bash
docker cp /home/drd/LiveTranslate-Android/android-app android-emu:/workspace
docker exec android-emu bash -c 'cd /workspace && export ANDROID_HOME=/opt/android-sdk && /opt/gradle-8.7/bin/gradle :app:assembleDebug --no-daemon'
```

### 4.4 常用诊断

```bash
docker exec android-emu bash -c 'tail -20 /tmp/emulator.log'   # 模拟器日志
docker logs asr-server 2>&1 | tail                            # ASR 服务器日志
docker exec android-emu bash -c 'export PATH=/opt/android-sdk/platform-tools:$PATH && adb shell dumpsys window windows | grep -A3 "APPLICATION_OVERLAY"'  # 悬浮窗
```

---

## 5. 下一步（对照 DEVELOPMENT_PLAN_zh.md Phase 1 剩余项）

- [x] 工程骨架 + 前台服务 + MediaProjection 捕获
- [x] VAD 分段（能量模式）
- [x] **Silero VAD 完整移植（v0.2）**
      - onnxruntime-android + silero_vad.onnx（v5 streaming 版，含 LSTM state 逐帧回传）
      - 渐进静音 / 自适应静音（P75×1.2）/ 回溯切分 / 密度过滤 / 短句合并 / pre-speech 缓冲
      - 能量兜底：RMS < energyThreshold×0.3 直接判静音（防底噪误判）
- [x] **interim 增量 ASR（v0.2）**
      - 每 ~2s 对 VAD 缓冲增量识别，完整句立即提交 + 比例裁剪 + 回音去重
      - 回声启发式：与已提交尾巴高度重叠的文本不重复提交
- [x] 远程 ASR 客户端（协议兼容）
- [x] LLM 流式翻译（SSE）
- [x] 悬浮字幕窗
- [ ] 翻译模型列表管理（多 API 配置）
- [ ] 无障碍覆盖层路线、通知栏字幕
- [ ] Phase 2：sherpa-onnx 本地 ASR（SenseVoice int8）

### v0.2 实测（长语音连续播放，Silero VAD + interim）

```
ASR: Hello everyone, this is a live.                        ← 半句先行识别
ASR: Hello, everyone! This is a live translation test. We are testing the audio kit.
interim: committed 2 sentence(s), trimmed 3.14s
译文: 这是实时翻译测试。 / 大家好！
ASR: We are testing the audio capture pipeline on Android. Please check...
interim: committed 3 sentence(s), trimmed 3.37s
译文: 我们正在Android上测试音频采集流程。 / 请检查。
ASR: Please check the subtitle overlay on top of the screen.
译文: 请检查屏幕顶部的字幕叠加。
```

播放结束后无循环识别（静音被正确切段/丢弃）。

---

## 6. Phase 2 完成记录（v0.4，2026-08-06）

### 新增功能
1. **翻译模型列表管理**（多 API 配置）
   - `model/ModelConfig.kt` + `model/SettingsStore.kt`（键名对齐 user_settings.json）
   - MainActivity 模型 Spinner + 编辑/新增/删除对话框（对应 ModelEditDialog 全部字段）
   - LlmTranslator 完整化：streaming / json_response / context_turns / no_think / no_system_role
2. **通知栏字幕**：BigTextStyle 显示原文+译文（锁屏可见）
3. **无障碍覆盖层字幕条**：`SubtitleAccessibilityService`（TYPE_ACCESSIBILITY_OVERLAY，免悬浮窗权限）
4. **本地 ASR（sherpa-onnx SenseVoice）**
   - 依赖：`com.github.k2-fsa:sherpa-onnx:1.13.4`（JitPack），内置 onnxruntime（移除独立 onnxruntime-android 依赖避免 so 冲突）
   - `asr/LocalAsrEngine.kt`：SenseVoice int8（239MB，中/英/日/韩/粤），接口与远程引擎统一（AsrEngine）
   - ASR 引擎模式 Spinner：远程 / 本地离线切换
   - SileroVadEngine 改用 sherpa-onnx `Vad.compute()`（内部维护 LSTM state）

### 关键踩坑（Phase 2）
| # | 问题 | 解决 |
|---|------|------|
| 1 | sherpa-onnx AAR 无 ai.onnxruntime Java 类 | 不用 onnxruntime-android，改用 sherpa-onnx 自带 Vad API |
| 2 | `asset:///` 协议不支持 | 模型复制到 filesDir 传绝对路径 |
| 3 | **本地路径 + 非空 AssetManager → "Please set assetManager to null"** | `Vad(null, config)` / `OfflineRecognizer(null, config)` |
| 4 | SileroVadModelConfig 参数顺序（model, threshold, minSilence, minSpeech, windowSize:int, maxSpeech） | javap 反编译确认 |
| 5 | OfflineSenseVoiceModelConfig 第 2 参是 language 非 tokens；tokens 在 OfflineModelConfig 字段 | 源码核对（v1.13.4 kotlin-api） |
| 6 | **silero v5 推理窗口 = 576（512+64 上下文）**，传 512 输出全 0 | prev64 上下文 buffer |
| 7 | adb push 到 /sdcard/Android/data/ 的文件 app 不可见（scoped storage） | run-as 复制到内部存储 files/ |
| 8 | Android 13+ app 读 /sdcard 根目录 wav → EACCES | 测试音频也放内部存储 |
| 9 | MediaProjection 授权回调偶发丢失（同会话第二次授权） | 重启 app 后首次授权正常（模拟器限制） |

### v0.4 实测（完全离线：asr-server 已停止）
```
ASR [<|en|>] 315ms: it is the process of seeking computers to learn from data
译文: 这是让计算机从数据中学习的过程
ASR [<|en|>] 328ms: we will discuss neural networks
译文: 我们将讨论神经网络。
ASR [<|en|>] 183ms: these are systems inspired by the human brain
译文: 这些系统是受人类大脑启发的。
ASR [<|en|>] 232ms: they can recognize patterns religion and speech
译文: 它们能识别模式、宗教和语言
```
- SenseVoice int8 本地推理延迟：130~380ms/段（模拟器 CPU）
- Silero VAD 置信度正常（0.4→1.0），自适应静音生效（P75×1.2）
- 播放结束后无循环识别

### 剩余
- [ ] 本地引擎热切换回滚（加载失败时自动回退远程已实现，UI 完善）
- [ ] 模型下载管理页（断点续传）
- [ ] 多目标语言并行翻译 UI
- [ ] 基准测试页

---

## 7. v0.5 完成记录（2026-08-06）

### 新增功能
1. **模型下载管理页**（`model/ModelRepository.kt` + `model/ModelDownloader.kt`）
   - 断点续传（Range 头 + 本地已有字节数）、多源回退（hf-mirror → huggingface.co）
   - 大小校验、下载/继续/删除、进度条、状态显示（未下载/已下载 x%/✓ 已下载）
   - 下载到内部存储 filesDir（scoped storage 安全）
2. **多语言并行翻译**（对应原项目 _translate_extra_langs）
   - ModelConfig.extraLanguages（编辑对话框"附加语言"字段）
   - 主翻译 + 附加语言并行（线程池），合并显示到悬浮窗译文区 + 无障碍字幕条（多行 [ja] 等）
3. **基准测试页**（对应 benchmark.py）
   - BenchmarkRunner：6 句基准集，逐句翻译统计延迟/成功率
   - 实测：完成率 100%，平均 1775ms / 最小 842ms / 最大 2307ms（DeepSeek）

### 实测（模拟器）
```
模型管理：model.int8.onnx ✓ 已下载 / tokens.txt ✓ / silero_vad.onnx 下载验证（8s 完成）
多语言：  extra translate [ja]: ライブ配信へようこそ。
          extra translate [ja]: 今日は、これから…
          extra translate [ja]: 人工知能について話すために。
基准：    完成率 100% | 平均 1775ms | 最小 842ms | 最大 2307ms
```

---

## 8. v0.5.1 修复记录（2026-08-06，真机反馈）

### 真机 Bug：翻译输出 "null" + 误报"模型可能不支持结构化输出"

**根因**（与 ASR 无关，用户无论本地/远程都复现）：
- deepseek-reasoner（实际路由 deepseek-v4-flash）流式响应思考阶段 `"content": null`（思考内容在 `reasoning_content` 字段）
- `org.json.JSONObject.optString("content")` 对 JSON null 返回**字符串 "null"** → 译文区拼出 "nullnullnull..."
- 恰好触发 `checkRepetition` 重复检测 → 显示误导性报错"重复输出检测（模型可能不支持结构化输出）"

**修复**（LlmTranslator.kt）：
1. 流式/非流式都改用 `isNull("content")` 显式判断，JSON null 帧直接跳过（思考帧忽略）
2. 空内容/纯 "null" 结果 → 明确报错"API 返回空内容（模型可能还在思考或请求参数不兼容）"
3. `checkRepetition` 要求 ≥2 次完整循环（text.regionMatches ×2），减少误报
4. 报错文案改为"翻译结果疑似重复，请检查模型输出"

**验证**（模拟器，deepseek-reasoner 模型跑基准测试）：
```
完成率 100% | 平均 3162ms | 最小 1883ms | 最大 4349ms
6 句全部正常翻译（修复前：null 串 + 报错）
```

### 附：真机 ASR null 排查要点（若仍复现）
- 确认已授予"录音"权限（Android 14+ MediaProjection 音频捕获需要 RECORD_AUDIO）
- 确认播放源 App 未禁用音频捕获（Android 14+ App 可声明 allowAudioPlaybackCapture=false）
- 用哔哩哔哩/YouTube 测试；系统自带播放器可能不兼容

---

## 9. v0.5.2 修复记录（2026-08-06，真机反馈第二轮）

### 问题 1：HTTP 400 "This response_format type is unavailable now"
- 原因：用户勾选"JSON 结构化输出"后，deepseek-v4-flash 不支持 response_format json_schema
- 修复：**自动降级重试**——收到含 response_format/json_schema/json_object 的 400 时，
  去掉 response_format **和 extra_body（enable_thinking）** 重试一次
- 验证：JSON 勾选 + deepseek-reasoner 基准测试 100% 成功（修复前全部 400 失败）

### 问题 2：无障碍字幕条无关闭按钮
- 修复：字幕条加 ✕ 按钮（点击隐藏，服务保持连接）；主界面③按钮变为显示/隐藏切换

### 问题 3：浅色系统下应用内文字与背景融合不可见
- 原因：DayNight 主题在浅色系统下文字黑色 + 深色背景
- 修复：MainActivity 强制 `AppCompatDelegate.MODE_NIGHT_YES`（深色 UI 设计不变）

---

## 10. v0.6 多协议支持（2026-08-06，参考 DMX API 文档）

### DMX API 平台分析
- https://www.dmxapi.cn 是 OpenAI 兼容聚合网关（new-api 系）
- 端点：/v1（错误格式为 OpenAI 风格：error/message/type/param/code）
- 平台文档展示多种接入方式（OpenAI SDK / Anthropic SDK / Gemini SDK）

### 新增：三协议翻译支持
- ModelConfig 增加 `protocol` 字段（openai / anthropic / gemini），编辑对话框协议选择器
- 平台模板提示：DMX 中转（https://www.dmxapi.cn/v1）/ DeepSeek / OpenAI / Claude / Gemini
- LlmTranslator 按协议分发：

| 协议 | 端点 | 鉴权 | SSE 解析 |
|------|------|------|---------|
| OpenAI | POST /chat/completions | Authorization: Bearer | data: choices[].delta.content（[DONE] 结束，content null 防御） |
| Anthropic | POST /v1/messages | x-api-key + anthropic-version | event: content_block_delta（message_stop 结束） |
| Gemini | POST /v1beta/models/{m}:streamGenerateContent?alt=sse | x-goog-api-key | data: candidates[].content.parts[].text（空 candidates 结束） |

### 验证（模拟器 + mock SSE 服务器）
- OpenAI：deepseek-reasoner 实测 100%（含 json 降级）
- Anthropic：mock 验证 100%，输出正确拼接
- Gemini：mock 验证 100%，输出正确拼接

### 踩坑
| # | 问题 | 解决 |
|---|------|------|
| 1 | Anthropic 流结束是 message_stop 事件（非 [DONE]），解析器需 break | when(type) 处理 |
| 2 | Gemini 流结束是空 candidates 数组 | length==0 → break |
| 3 | mock 服务器 BaseHTTPRequestHandler 无 Content-Length 时连接悬挂 | 加 Content-Length + Connection: close + flush |
| 4 | pkill -f 匹配自身命令行导致自杀 | 用 [.] 正则技巧或容器隔离 |
| 5 | mock 路径匹配：URL 是 models/{m}:streamGenerateContent（冒号无斜杠）| 条件不带前导斜杠 |

### DMX 平台使用（用户场景）
- 协议选"OpenAI 兼容"，API Base 填 https://www.dmxapi.cn/v1，API Key 填 DMX 令牌
- 模型名填 DMX 支持的模型（如 gpt-4o / claude-sonnet-4-5 / gemini-2.5-pro 等中转名）
- v4-flash 类思考模型：无需勾选 JSON 输出（自动降级）；"禁用思考"也可关掉

---

## 11. DMX 平台 + qwen3.5-flash 适配验证（2026-08-06）

### DMX API 实测
- /v1/models：563 个模型可用（含 qwen3.5-flash / deepseek-v4-flash / claude / gemini 等）
- **模型名 `qwen3.5-flash`（无前缀）可用**（DMXAPI- 前缀版超时/不可用）
- **qwen3.5-flash 是思考型模型**：响应含 `reasoning_content`（思考过程）+ `content`（最终翻译）
- 流式响应：思考阶段 `delta.content: null`（App v0.5.1 的 JSON null 防御已覆盖）
- `extra_body.enable_thinking=false` **无效**（仍输出思考过程，被忽略，无副作用）
- `response_format` json_schema → 400 "messages must contain the word 'json'"（App 降级重试自动处理 ✓）

### App 端到端（模拟器基准测试）
```
完成率 83%（5/6）| 平均 12650ms | 最小 8019ms | 最大 18460ms
8019ms The weather today... → 今天天气很好，非常适合户外活动。
9349ms We are testing... → 我们正在 Android 上测试音频采集管线。
12844ms Neural networks... → 神经网络可以识别图像和语音中的模式。
14579ms Artificial intelligence... → 人工智能正在改变我们生活和工作的方式。
18460ms Hello everyone... → 大家好，这是实时翻译测试。
```

### 使用建议（真机）
- 协议：OpenAI 兼容；API Base：https://www.dmxapi.cn/v1；模型名：qwen3.5-flash
- **超时建议设 60 秒**（思考型模型首 token 8-18 秒，30 秒偶发超时）
- 不用勾选"JSON 结构化输出"/"禁用思考"（自动降级/被忽略）
- 6 句基准 1 句超时失败属正常波动，重试即可

---

## 12. v0.6.1 非思考模式修复（2026-08-06，DMX qwen3.5-flash）

### 问题
qwen3.5-flash 是思考型模型，翻译延迟 8-18 秒，实时字幕不可用。

### 根因
DMX 中转对 `extra_body: {"enable_thinking": false}` **无效**（仍输出 reasoning_content）。

### 解决方案（实测参数矩阵）
| 参数 | 位置 | 效果 |
|------|------|------|
| `enable_thinking: false` | **顶层** | ✅ **有效**（reasoning 消失） |
| `reasoning_effort: "none"` | 顶层 | ✅ 有效 |
| `enable_thinking: false` | extra_body | ❌ 无效 |
| `chat_template_kwargs` / `thinking: disabled` / `thinking_budget: 0` | extra_body | ❌ 无效 |
| qwen3.5-flash-cc 变体 | — | ❌ 仍思考 |

### 代码修改（LlmTranslator.buildOpenAiBody）
no_think=true 时：**顶层 `enable_thinking: false` + extra_body 双发**（DMX 顶层生效，DeepSeek 两者兼容）
400 降级条件扩展：含 enable_thinking/thinking 也触发去参重试

### 实测对比（DMX qwen3.5-flash 基准）
```
思考模式：  完成率 83% | 平均 12650ms | 最大 18460ms
非思考模式：完成率 100% | 平均  1304ms | 最小 292ms | 最大 1845ms  ← 快 10 倍
```
TTFB 实测：0.26s（流式）

---

## 13. v0.6.2 修复：真机"空内容"/基准失败（2026-08-06）

### 用户反馈
真机基准测试失败、实际翻译返回"API 返回空内容"（模拟器却成功）。

### 根因（模拟器复现）
真机配置勾选了"JSON 结构化输出"（json_response=true）→ DMX 对 response_format 返回 400
→ **降级逻辑把 enable_thinking 也一并去掉了**（v0.6.1 实现）→ 回到思考模式
→ qwen3.5-flash 思考过程耗尽 max_tokens → content 全空 → "API 返回空内容" + 超时

模拟器测试时 json_response=false（未触发降级）→ 未暴露。

### 修复（v0.6.2）
1. **降级只去掉 response_format，保留 enable_thinking**（该参数全平台兼容，无需降级）
2. **思考型自动重试**：流式/非流式响应中出现 reasoning_content 且最终 content 为空
   → 自动强制 enable_thinking=false 重试（最多 3 次）
3. 纯空响应重试一次

### 验证（json_response=true + DMX qwen3.5-flash 基准）
```
修复前：完成率 83% | 平均 20098ms（11-33s，思考模式）
修复后：完成率 100% | 平均  1785ms（0.9-2.3s）
```

### 真机配置建议
- "JSON 结构化输出"可勾选也可不勾（两种都正常）
- "禁用思考"保持勾选（默认）
- 超时 60 秒（思考兜底）

---

## 14. v0.7.x 字幕条样式系统（2026-08-06）

- 字幕条样式：透明度/字体(5种)/字号(10-60sp)/粗体/圆角(0-48dp)/尺寸可调
- 二级菜单：⚙ 图标触发（锚定字幕条旁弹出，内容区点击不弹菜单——避免误触 ✕）
- resize 手柄 ⤡：FrameLayout 绝对定位右下角，拖动改尺寸（实测 397→499px）
- 边缘留白 12dp + 屏幕自适应（宽度=屏宽-留白，字号=屏宽dp×0.055 封顶 28sp）
- 交互约定：⚙ 弹菜单 / ✕ 关闭 / 顶部拖动 / ⤡ 调尺寸
- 样式持久化：SettingsStore.subtitleStyle（悬浮窗与无障碍条共享）
- 版本：v0.7.0(5) → v0.7.1(6)，versionCode 每次递增（ColorOS 限制）
- UI 接手者必读：UI_DEV_GUIDE_zh.md

---

## 15. v0.10.x 液态玻璃 + v0.11 三主题验证记录（2026-08-07）

### v0.10 Liquid Glass（液态玻璃）
- 窗口级真毛玻璃：`blurBehindRadius`（API 31+），悬浮窗 26dp / 菜单 24dp，dumpsys 确认 `blurBehindRadius=71`
- **PopupWindow 模糊失效坑**：show 后立即设置 blurBehindRadius 会被 PopupWindow 内部布局覆盖 → `postDelayed(160ms)` 再设置才生效
- **菜单与悬浮窗重叠坑**：showAsDropDown 锚定 ⚙（悬浮窗顶部）→ 菜单盖住译文；改 showAtLocation 手动定位悬浮窗底部下方 6dp，空间不足弹上方贴状态栏；PopupWindow 无 maxHeight → 固定 height=min(内容估算, 可用空间) + ScrollView 内部滚动
- **滑杆黑块坑**：菜单玻璃底色过暗（rgba(18,20,32)）+ SeekBar 默认背景 → 提亮 rgba(42,44,58) + `background=null` + `splitTrack=false`
- **GradientDrawable 无 setShader**（javap 确认）→ 用 `GradientDrawable(Orientation.TOP_BOTTOM, colors)` 内置渐变
- **附加语言译文重复中文坑**：mock LLM 只读 `target_language` 字段，但 App 用 system prompt "into X" 指定目标语言 → mock 改为正则解析 system prompt 推断语言

### v0.11 三主题系统
- ThemeManager：色板动态委托（`val BG get() = ThemeManager.current.bg`），切主题 recreate 生效，持久化 `theme` 键
- 暗色主题 = 第一版还原：纯色背景（showAurora=false）、不透明卡片、纯色平面按钮、蓝色分段胶囊
- 菜单/字幕窗用独立玻璃参数（menuBase 等），不随卡片纯色化
- 状态栏按主题：浅色主题浅底+深图标（applyThemeSystemBars 必须在 setContentView 之后，否则 decorView null 崩溃）
- 视觉验证：describe_image 视觉模型逐主题审查（极光/毛玻璃/黑块/重叠）
