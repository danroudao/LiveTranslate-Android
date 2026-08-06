# LiveTranslate Android — UI 开发接手指南

> 面向接手 UI 开发的工程师。本文档覆盖：项目现状、代码导航、UI 模块深度说明、
> 数据流、构建环境、测试方法与设计约定。
>
> 配套文档：[README.md](README.md)（项目简介）· [DEVELOPMENT_PLAN_zh.md](DEVELOPMENT_PLAN_zh.md)（移植架构方案）· [ENV_SETUP_zh.md](ENV_SETUP_zh.md)（环境与踩坑）

---

## 1. 项目现状（v0.7.1，versionCode=6）

**实时音频翻译 App**：捕获系统音频 → 语音识别 → LLM 翻译 → 悬浮字幕显示。

### 功能清单

| 模块 | 功能 | 状态 |
|------|------|------|
| 音频 | MediaProjection 系统音频捕获、麦克风混音、48k→16k 重采样 | ✅ |
| VAD | Silero VAD（sherpa-onnx）+ 完整状态机（渐进/自适应静音/回溯切分） | ✅ |
| ASR | 远程（HTTP 服务器，兼容 Windows 版协议）/ 本地（SenseVoice int8 离线） | ✅ |
| 翻译 | 三协议（OpenAI 兼容 / Anthropic / Gemini），SSE 流式，思考型模型兼容 | ✅ |
| 字幕 | 顶部悬浮窗 / 底部无障碍字幕条 / 通知栏字幕，三通道 | ✅ |
| 样式 | 透明度/字体/字号/粗体/圆角/尺寸可调（⚙ 菜单） | ✅ |
| 配置 | 多模型管理、平台预设、模型列表拉取、断点续传下载 | ✅ |
| 工具 | 基准测试（延迟/成功率）、内置测试语音、翻译历史 | ✅ |

### 技术栈
- Kotlin + View（**未用 Compose**，全部程序化构建 UI）
- minSdk 29 / targetSdk 34 / compileSdk 34
- sherpa-onnx 1.13.4（JitPack，含 onnxruntime + SenseVoice/Silero）
- OkHttp 4.12（网络）、AppCompat + Material

---

## 2. 代码导航（`android-app/app/src/main/java/com/example/livetranslate/`）

```
MainActivity.kt          主界面（所有 UI 入口，约 600 行）
├── 权限引导（悬浮窗/录音/通知/媒体投影）
├── ASR 引擎模式选择（远程/本地）
├── 翻译模型管理（Spinner + 编辑/新增/删除对话框）
│     ├── 平台预设一键填充（[DMX][DeepSeek][OpenAI][Claude][Gemini]）
│     ├── 获取模型列表（按协议拉取，防手输错误）
│     └── 模型选择对话框
├── 模型管理对话框（下载/删除/进度）
├── 基准测试（BenchmarkRunner + 结果对话框）
├── 测试语音播放（assets 内置 → TTS 兜底）
└── 无障碍字幕条开关引导

pipeline/
├── CaptureService.kt        核心管线服务（前台服务）
│     ├── 捕获线程 → VAD → ASR 队列 → 翻译线程池 → 字幕更新
│     ├── interim 增量 ASR（句切分/回音去重/回声启发式）
│     └── 通知栏字幕（BigTextStyle）
├── AudioCapturer.kt         MediaProjection + AudioRecord + 重采样
├── VadProcessor.kt          Silero/能量 VAD 状态机（387 行 Python 移植）
├── SileroVad.kt             sherpa-onnx Vad.compute() 封装（v5 窗口 576）
├── InterimSplitter.kt       句切分 + 回音去重
├── OverlayManager.kt        ★ 顶部悬浮窗（UI 重点，见 §3）
├── SubtitleAccessibilityService.kt  ★ 无障碍字幕条（UI 重点，见 §3）
└── (CaptureService 内部)     通知字幕

net/
├── LlmTranslator.kt         三协议翻译客户端（SSE 解析/降级重试/思考型兼容）
└── RemoteAsrClient.kt       远程 ASR（二进制协议）

asr/
├── AsrEngine.kt             引擎接口（远程/本地统一）
└── LocalAsrEngine.kt        sherpa-onnx SenseVoice

model/
├── ModelConfig.kt           翻译模型配置（JSON 序列化）
├── SettingsStore.kt         SharedPreferences 封装（全部设置）
├── SubtitleStyle.kt         ★ 字幕条样式数据类
├── ModelDownloader.kt       断点续传下载器
└── ModelRepository.kt       模型仓库定义（多源 URL）

benchmark/BenchmarkRunner.kt  基准测试
```

---

## 3. UI 模块深度说明（接手重点）

### 3.1 整体 UI 架构

- **全部视图程序化构建**（代码里 `LinearLayout`/`TextView`/`SeekBar` 拼装，无 XML 布局）
- **深色主题强制**：`AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)`（MainActivity.onCreate 开头）—— 界面按深色设计，浅色系统下文字会与深色背景融合，**不要去掉这行**
- **字幕样式全局共享**：`SettingsStore.subtitleStyle`（`SubtitleStyle` JSON）—— 悬浮窗与无障碍条读同一份样式，改动一处两处同步

### 3.2 OverlayManager（顶部悬浮窗，`pipeline/OverlayManager.kt`）

**窗口结构**（FrameLayout 根）：
```
FrameLayout v（TYPE_APPLICATION_OVERLAY 窗口）
├── LinearLayout content（垂直）
│     ├── LinearLayout topRow（水平，右对齐）
│     │     ├── ⚙ 菜单按钮（→ showStyleMenu）
│     │     └── ✕ 关闭按钮（→ hide）
│     ├── TextView original（原文，13sp 灰色）
│     └── TextView translation（译文，22sp 白色）
└── TextView handle "⤡"（FrameLayout 绝对定位 BOTTOM|END，resize 手柄）
```

**交互设计（重要约定）**：
| 区域 | 手势 | 行为 |
|------|------|------|
| topRow（⚙/✕ 之外） | 拖动 | 移动整个窗口 |
| ⚙ 按钮 | 点击 | 弹出样式二级菜单（锚定窗口下方） |
| ✕ 按钮 | 点击 | 关闭悬浮窗（服务继续运行） |
| ⤡ 手柄 | 拖动 | 调整窗口尺寸（右下角，绝对定位跟随） |
| 内容区 | 点击 | **无操作**（刻意移除弹菜单，避免误触 ✕） |

> **历史教训**：内容区点击弹菜单会导致用户点 ✕ 时误触菜单——因此菜单触发**严格限定在 ⚙ 图标**。后续改 UI 时保持此约定。

**窗口参数**：
- `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN`
- 初始：`x 居中`、`y=dp(240)`、宽=`屏宽-2×12dp`（边缘留白）、高=`WRAP_CONTENT`
- 尺寸持久化：`SettingsStore.overlayWidthPx/HeightPx`（0=自动）

**二级菜单（showStyleMenu）**：
- `PopupWindow`，**必须** `setWindowLayoutType(TYPE_APPLICATION_OVERLAY)`（服务上下文无 Activity token，否则弹不出）
- `showAsDropDown(anchor, 0, dp(6))` 锚定窗口下方（空间不足自动翻转）
- 内容（垂直）：背景透明度 SeekBar → 字号 −/+/值 → 字体 Spinner（默认/serif/monospace/cursive/Medium）→ 粗体开关 → 圆角 SeekBar → 重置样式 + 完成
- 所有调整**实时生效 + 立即持久化**（`store.subtitleStyle = style`）
- 样式应用：`applyStyle()`（GradientDrawable 圆角 + argb 透明度背景；`applyFont()` 字体+字号）

**样式系统**（`model/SubtitleStyle.kt`）：
```kotlin
data class SubtitleStyle(
    val alpha: Int = 210,          // 背景透明度 0-255
    val fontSize: Float = 0f,      // sp；0=屏幕自适应（autoFontSize: 屏宽dp×0.055，封顶 28）
    val fontFamily: String = "default",  // default/serif/monospace/cursive/sans-serif-medium
    val cornerRadius: Int = 14,    // dp
    val bold: Boolean = false,
)
```

### 3.3 SubtitleAccessibilityService（底部无障碍字幕条）

- `AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY`（免悬浮窗权限）
- 结构：水平 LinearLayout = [译文文本(weight=1) | ⚙ | ✕ | ⤡]
- 同样支持样式菜单（⚙ 触发，锚定字幕条上方弹出）+ resize（限高屏幕 66%）+ ✕ 关闭（`hideBar()`，服务保持连接，主界面③按钮可重新显示）
- 启用方式：系统设置 → 无障碍 → LiveTranslate 字幕条（或 `adb shell settings put secure enabled_accessibility_services ...`）
- **注意**：API 33+ 无障碍覆盖层高度 ≤ 屏幕 2/3

### 3.4 主界面（MainActivity）现有 UI 元素

自上而下：标题（含版本号）→ ASR 服务器地址输入 → ASR 引擎模式 Spinner → 翻译模型 Spinner + [编辑/新增/删除] → ①授权开始 → ②播放测试语音 → ③无障碍字幕条 → [模型管理/基准测试] → 状态区（滚动日志）

---

## 4. 数据流

### 4.1 字幕管线
```
MediaProjection → AudioCapturer(32ms chunk) → VadProcessor
  → 语音段 → CaptureService ASR 队列 → AsrEngine（远程/本地）
  → 文本 → interim 句切分 → LlmTranslator（三协议 SSE）
  → onFinal → OverlayManager.update / SubtitleAccessibilityService.updateSubtitle / 通知栏
```

### 4.2 样式数据流
```
⚙ 菜单控件（SeekBar/按钮）
  → SubtitleStyle.copy(...) → SettingsStore.subtitleStyle（SharedPreferences 立即写）
  → applyStyle() → 悬浮窗/无障碍条视图刷新
```
两处字幕组件各自持有 `style` 快照，修改时各自更新；重启 App 后从 Store 读取。

### 4.3 模型配置数据流
```
MainActivity 编辑对话框 → ModelConfig.toJson() → SettingsStore.models（JSON 数组）
  → CaptureService 启动时 ModelConfig.fromJson() → LlmTranslator(protocol, ...)
```

---

## 5. 构建环境

### 5.1 标准构建（任意机器）
```bash
# 依赖：JDK 17、Android SDK（platform 34 + build-tools 35）、Gradle 8.7+
cd android-app
# 国内网络已配好镜像：阿里云 maven（google/central/gradle-plugin）+ JitPack（sherpa-onnx）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（~83MB，含 arm64+x86_64）
```

### 5.2 模拟器测试环境（本机 Docker）
```bash
# 容器 android-emu（ubuntu + JDK17 + gradle8.7 + Android SDK + AVD test34）
docker start android-emu
docker cp android-app android-emu:/workspace   # 同步代码
docker exec android-emu bash -c 'cd /workspace && export ANDROID_HOME=/opt/android-sdk && /opt/gradle-8.7/bin/gradle :app:assembleDebug --no-daemon'
docker exec android-emu bash -c 'export PATH=/opt/android-sdk/platform-tools:$PATH && adb install -r /workspace/app/build/outputs/apk/debug/app-debug.apk'
# 授权（UI 自动化）与测试脚本见 full_test_v2.sh
```

### 5.3 发布流程（重要约定）
- **versionCode 必须每次递增**（ColorOS 禁止覆盖安装同版本号）—— 当前 6，下次 7
- 版本号同步改：`app/build.gradle.kts` + MainActivity 标题
- 发版产物：`dist/LiveTranslate-demo-vX.Y.Z-arm64.apk`（仅 arm64+x86_64 ABI）
- 推 GitHub：`git add -A && git commit && git push`

---

## 6. 测试方法

### 6.1 自动化（full_test_v2.sh）
覆盖：授权流程 → 悬浮窗 → ASR → 菜单弹出 → 字号/字体/粗体/透明度/圆角 → 重置 → resize → ✕ 关闭。
注意：脚本中 `get_center` 对含正则特殊字符的文本（如 "+"）需转义；uiautomator dump 对 overlay 窗口内容不可见（⚙/✕ 用像素分析或推算坐标）。

### 6.2 手动真机
1. 装 APK → 授予悬浮窗权限
2. 模型配置：模型管理 → [DMX] 预设 → 填 Key → 获取模型列表 → 选模型
3. ① 授权 → 系统弹窗选 Start now → ② 播放内置测试语音
4. 验证：字幕出现 → ⚙ 调样式 → ⤡ 调尺寸 → ✕ 关闭

### 6.3 常见坑
- PopupWindow 必须设 `TYPE_APPLICATION_OVERLAY`（服务上下文）
- 测试环境高频 uiautomator dump 可能引发偶发 ANR（真机无此问题）
- overlay 窗口内容 uiautomator 抓不到，控件定位用像素分析（截图 → 纯 python PNG 解码找白色像素簇）或推算

---

## 7. 设计约定（改 UI 时遵守）

1. **深色 UI**：不要移除 `MODE_NIGHT_YES`；背景 `#101014`/`#1E1E23`，文字白色
2. **菜单触发只在 ⚙**：内容区不弹菜单（历史教训）
3. **实时生效 + 立即持久化**：样式调整不设"应用"按钮
4. **程序化构建**：延续 View 代码构建风格；若迁移 Compose 需整体重构（当前 UI 规模约 1500 行，可评估）
5. **中英文文案**：当前中文硬编码，后续可接 i18n（原项目有 zh/en 双语文案可参考 `i18n/`）
6. **版本管理**：versionCode 递增；标题显示版本号方便测试反馈定位

---

## 8. 建议的 UI 改进方向（供接手参考）

- [ ] 设置页完善：VAD/ASR/字幕的完整设置面板（当前分散在主界面和字幕条菜单）
- [ ] 字幕条菜单 UX：透明度/字号滑块实时预览 + 预设样式模板（如"高清/夜览/极简"）
- [ ] 主界面布局：当前纵向 ScrollView 堆叠，可改为 Tab/分组
- [ ] 通知栏字幕样式跟随 SubtitleStyle
- [ ] i18n 中英文
- [ ] 悬浮窗动画（显示/隐藏淡入淡出）
- [ ] Compose 迁移评估（若团队倾向）
