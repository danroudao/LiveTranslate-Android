# LiveTranslate Android — UI 开发接手指南

> 面向接手 UI 开发的工程师。本文档覆盖：项目现状、代码导航、UI 模块深度说明、
> 数据流、主题系统、构建环境、测试方法与设计约定。
>
> 配套文档：[README.md](README.md)（项目简介）· [DEVELOPMENT_PLAN_zh.md](DEVELOPMENT_PLAN_zh.md)（移植架构方案）· [ENV_SETUP_zh.md](ENV_SETUP_zh.md)（环境与踩坑）

---

## 1. 项目现状（v0.11.0，versionCode=9）

**实时音频翻译 App**：捕获系统音频 → 语音识别 → LLM 翻译 → 悬浮字幕显示。

### 功能清单

| 模块 | 功能 | 状态 |
|------|------|------|
| 音频 | MediaProjection 系统音频捕获、麦克风混音、48k→16k 重采样 | ✅ |
| VAD | Silero VAD（sherpa-onnx）+ 完整状态机（渐进/自适应静音/回溯切分） | ✅ |
| ASR | 远程（HTTP 服务器）/ 本地（SenseVoice int8 离线）双引擎 | ✅ |
| 翻译 | 三协议（OpenAI / Anthropic / Gemini），SSE 流式，思考型模型兼容 | ✅ |
| 字幕 | 顶部悬浮窗 / 底部无障碍字幕条 / 通知栏字幕，三通道 | ✅ |
| **主题** | **三主题可切换（暗色液态玻璃默认 / 浅色 Apple / VTuber 紫）** | ✅ |
| 样式 | 透明度/字体/字号/粗体/圆角/尺寸可调（⚙ 菜单）+ 预设模板 | ✅ |
| 配置 | 多模型管理、平台预设、模型列表拉取、断点续传下载 | ✅ |
| 工具 | 基准测试、内置测试语音、翻译历史 | ✅ |

### 技术栈
- Kotlin + View（**未用 Compose**，全部程序化构建 UI）
- minSdk 29 / targetSdk 34 / compileSdk 34
- sherpa-onnx 1.13.4（JitPack）、OkHttp 4.12、AppCompat + Material

---

## 2. 代码导航（`android-app/app/src/main/java/com/example/livetranslate/`）

```
MainActivity.kt          主界面（权限引导、模型管理、基准测试、主题切换入口）
ui/                      ★ 共享 UI 层（主题化组件库，接手重点）
├── ThemeManager.kt      ★ 主题引擎（三主题色板/材质/极光/对话框）
├── UIKit.kt             ★ 组件库（卡片/按钮/分段控件/滑杆/输入框/头像工具）
├── LiquidGlass.kt       ★ 液态玻璃材质（玻璃面板/窗口模糊/极光背景 AuroraView）
└── IOSMotion.kt         动画库（iOS 曲线/spring/pop/呼吸/交叉淡化）
pipeline/
├── CaptureService.kt    核心管线服务（捕获→VAD→ASR→翻译→字幕）
├── AudioCapturer.kt     MediaProjection + AudioRecord + 重采样
├── VadProcessor.kt      Silero/能量 VAD 状态机
├── SileroVad.kt         sherpa-onnx Vad.compute() 封装
├── InterimSplitter.kt   句切分 + 回音去重
├── OverlayManager.kt    ★ 顶部悬浮窗（UI 重点，见 §3.2）
└── SubtitleAccessibilityService.kt  ★ 无障碍字幕条（UI 重点，见 §3.3）
net/  (LlmTranslator 三协议 / RemoteAsrClient)
asr/  (AsrEngine / LocalAsrEngine SenseVoice)
model/  (ModelConfig / SettingsStore / SubtitleStyle / ModelDownloader / ModelRepository)
benchmark/  BenchmarkRunner
```

**资源目录**（`android-app/app/src/main/`）：
```
res/drawable/   ic_*.xml（MDI 线性图标转 VectorDrawable）、dialog_bg.xml（浅色对话框）
res/values/     styles.xml（LightDialogAlert 浅色对话框主题）、themes.xml
assets/fonts/   Pacifico.ttf（VTuber 主题手写体标题）
assets/img/     heroine.webp（猫耳少女立绘）、chibi_*.webp（Q版小人x3）、cat_mascot.webp（白猫）
```

---

## 3. UI 模块深度说明（接手重点）

### 3.1 主题系统（v0.11.0 核心架构）

**三主题可切换，右上角齿轮按钮 → 对话框选择 → recreate 生效 → 持久化**：

| 主题 | name | 特点 |
|------|------|------|
| 🌙 暗色液态玻璃（**默认**） | `dark` | 第一版 v0.8.0 外观：纯色 #101014 背景（无极光）、不透明卡片 #1E1E23、纯色平面按钮、蓝色分段胶囊；**保留**液态玻璃字幕窗/菜单优化 |
| ☀️ 浅色 Apple | `light` | 第二版 v0.9.0：极光背景 + 白色毛玻璃卡片 + 品牌蓝 #0071E3 + 浅色状态栏/对话框 |
| 💜 VTuber 紫 | `vtuber` | 第三版：深紫背景 + 手写体渐变标题 + AI 立绘/Q版头像/白猫 + 紫渐变按钮 |

**实现机制**（关键设计）：
- `ThemeManager.current` 是全局当前主题（`AppTheme` 数据类：画布/卡片/文字/主色/输入框/滑杆/分段控件/极光/菜单参数/对话框主题）
- **UIKit 色板全部是动态委托属性**（`val BG get() = ThemeManager.current.bg`）——组件方法内部引用 `UIKit.X` 自动跟随主题，**新增组件时不要写死颜色常量**
- 切换流程：`ThemeManager.set(context, name)` 写 SettingsStore（`theme` 键）→ `MainActivity.recreate()` → `onCreate` 里 `ThemeManager.init(this)` 读取
- 主题差异点：
  - `showAurora`：dark=false（第一版纯色背景），light/vtuber=true
  - `menuBase/menuSheen/menuEdge`：样式菜单专用玻璃参数，**不随卡片纯色化**（dark 卡片纯色但菜单保持液态玻璃）
  - `dialogThemeRes`：light 用 `LightDialogAlert`（styles.xml），dark/vtuber 用系统深色
  - `primaryGradA/B`：vtuber 紫渐变，dark/light 纯色

**VTuber 主题专属元素**（仅该主题渲染，MainActivity 中 `vtuberTheme` 分支）：
- 手写体标题（Pacifico + 紫渐变 shader）
- 主立绘圆形头像（`UIKit.roundAvatar`）
- 步骤卡 Q版头像（`stepRow`：头像 + 按钮横排）
- 白猫吉祥物（底部 55% 透明）

### 3.2 OverlayManager（顶部悬浮窗）

**窗口结构**（FrameLayout 根）：
```
FrameLayout v（TYPE_APPLICATION_OVERLAY 窗口，blurBehindRadius=71px 真毛玻璃）
├── LinearLayout content（垂直）
│     ├── LinearLayout topRow（水平，右对齐）：⚙ 菜单按钮 + ✕ 关闭
│     ├── TextView original（原文，13sp 灰色）
│     └── TextView translation（译文，22sp 白）
└── TextView handle "⤡"（右下角 resize 手柄）
```

**交互设计（重要约定，勿改）**：
| 区域 | 手势 | 行为 |
|------|------|------|
| topRow | 拖动 | 移动窗口（半透明跟随 + 松手边界 spring 回弹） |
| ⚙ | 点击 | 弹出样式菜单 |
| ✕ | 点击 | 关闭悬浮窗（服务继续运行） |
| ⤡ | 拖动 | 调整窗口尺寸 |
| 内容区 | 点击 | **无操作**（历史教训：内容区弹菜单导致误触 ✕） |

**液态玻璃**：
- 窗口 `blurBehindRadius = dp(26)`（API 31+，真毛玻璃；低版本自动跳过）
- 背景 = 深蓝灰半透明（alpha 随样式）+ 顶部光泽渐变 + 45% 白描边（`applyStyle()` 中 LayerDrawable 三层）
- 动画：显示淡入+下滑、隐藏淡出+上滑、译文交叉淡化

**样式菜单（showStyleMenu）定位逻辑（v0.10.1 修复，重要）**：
```kotlin
// 1. 手动定位 showAtLocation（不用 showAsDropDown——其自动翻转不可控，会盖住悬浮窗）
// 2. 锚定悬浮窗底部下方 6dp；下方空间不足 → 弹到上方并对齐状态栏下方
// 3. 菜单内容包 ScrollView + popup.height = min(内容估算, 可用空间)
//    （PopupWindow 无 maxHeight 属性，只能设固定 height）
// 4. blurPopup 延迟 160ms 应用（PopupWindow 内部布局会覆盖立即设置的 blurBehindRadius）
```

**样式系统**（`model/SubtitleStyle.kt`，悬浮窗与无障碍条共享）：
```kotlin
data class SubtitleStyle(
    val alpha: Int = 210,          // 背景透明度
    val fontSize: Float = 0f,      // sp；0=屏幕自适应
    val fontFamily: String = "default",  // default/serif/monospace/cursive/sans-serif-medium
    val cornerRadius: Int = 14,    // dp
    val bold: Boolean = false,
)
```

### 3.3 SubtitleAccessibilityService（底部无障碍字幕条）

- `TYPE_ACCESSIBILITY_OVERLAY`（免悬浮窗权限），结构：`[译文文本 | ⚙ | ✕ | ⤡]`
- 同样 blurBehindRadius + 液态玻璃背景 + 样式菜单（锚定字幕条**上方**弹出）
- 菜单样式与悬浮窗共用主题参数

### 3.4 主界面（MainActivity）

自上而下：大标题（主题化）→ 状态点/版本徽章/主题齿轮 → 操作卡（①开始翻译 ②测试语音 ③字幕条，暗色=纵向纯按钮，VTuber=Q版步骤卡）→ ASR 卡（分段控件+输入框）→ 模型卡（Spinner+编辑/新增/删除）→ 工具卡（模型管理/基准测试，纯文字按钮）→ 白猫（VTuber）→ 状态日志卡

**根结构**：FrameLayout = [AuroraView 极光层（showAurora 时）] + [ScrollView 内容层]

---

## 4. 数据流

### 4.1 字幕管线
```
MediaProjection → AudioCapturer(32ms chunk) → VadProcessor
  → 语音段 → CaptureService ASR 队列 → AsrEngine（远程/本地）
  → 文本 → interim 句切分 → LlmTranslator（三协议 SSE）
  → onFinal → OverlayManager.update / SubtitleAccessibilityService.updateSubtitle / 通知栏
```

### 4.2 主题数据流
```
齿轮按钮 → ThemeManager.set(context, name) → SettingsStore（"theme" 键，立即持久化）
  → MainActivity.recreate() → ThemeManager.init() → UIKit 色板动态委托 → 全组件新色
悬浮窗/菜单：下次创建时读取 ThemeManager.current（无需重建窗口）
```

### 4.3 样式数据流
```
⚙ 菜单控件 → SubtitleStyle.copy(...) → SettingsStore.subtitleStyle → applyStyle() 两处同步
```

### 4.4 模型配置数据流
```
MainActivity 编辑对话框 → ModelConfig.toJson() → SettingsStore.models → CaptureService 启动读取
```

---

## 5. 构建环境

### 5.1 标准构建（任意机器）
```bash
cd android-app
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（~83MB，含 arm64+x86_64）
```

### 5.2 模拟器测试环境（本机 Docker）
```bash
docker start android-emu
docker cp android-app android-emu:/workspace   # 同步代码
docker exec android-emu bash -c 'cd /workspace && export ANDROID_HOME=/opt/android-sdk && /opt/gradle-8.7/bin/gradle :app:assembleDebug --no-daemon'
docker exec android-emu bash -c 'export PATH=/opt/android-sdk/platform-tools:$PATH && adb install -r ...'
```

### 5.3 发布流程（重要约定）
- **versionCode 必须每次递增**（ColorOS 禁止覆盖安装同版本号）—— 当前 9，下次 10
- 版本号同步改：`app/build.gradle.kts` + MainActivity 头部徽章
- 本地演示分发：APK 放 `/tmp/www/`，`python3 -m http.server 8897` 托管，飞书发 `http://192.168.0.8:8897/xxx.apk`（局域网；公网 NAT 不可达，需端口转发）

---

## 6. 测试方法

### 6.1 自动化（full_test_v2.sh 为基础）
覆盖：授权流程 → 悬浮窗 → ASR → 菜单弹出 → 样式调整 → 重置 → resize → ✕ 关闭。
**新增（v0.11）**：
- 主题切换：齿轮 → 对话框 → 选主题 → 验证 recreate + prefs `theme` 键
- 菜单定位：悬浮窗拖到屏幕底部 → 打开菜单 → 验证向上弹出且不重叠
- 菜单高度：内容超屏幕时内部可滚动

### 6.2 视觉验证（本机无 GUI，用视觉模型）
```bash
adb exec-out screencap -p > /tmp/x.png
# 用 describe_image 工具审查截图（极光/毛玻璃/黑块/重叠等）
```

### 6.3 常见坑
| # | 坑 | 解决 |
|---|-----|------|
| 1 | PopupWindow 必须设 `TYPE_APPLICATION_OVERLAY` | 服务上下文无 Activity token |
| 2 | showAsDropDown 自动翻转不可控 → 菜单盖住悬浮窗 | 用 showAtLocation 手动定位（§3.2） |
| 3 | PopupWindow 无 maxHeight | 固定 height = min(内容估算, 可用空间) + 内部 ScrollView |
| 4 | PopupWindow 的 blurBehindRadius 被内部布局覆盖 | `content.postDelayed({ blurPopup() }, 160)` |
| 5 | GradientDrawable 无 setShader | 用 `GradientDrawable(Orientation.TOP_BOTTOM, colors)` 内置渐变 |
| 6 | SeekBar 默认背景/黑块 | `background = null` + `splitTrack = false` + 自定义 Drawable 填充（ProgressBar 不走 setLevel） |
| 7 | LayerDrawable 拇指不渲染 | 自定义 Drawable 必须实现 getIntrinsicWidth/Height |
| 8 | `androidx.core.graphics.withAlpha` 不可用（无 core-ktx） | 用 `UIKit.withAlphaCompat(color, alpha)` |
| 9 | `applyThemeSystemBars` 在 setContentView 前调用 → decorView null 崩溃 | 移到 setContentView 之后 |
| 10 | 未挂载 View 强转 layoutParams → NPE | 用 `addView(view, LayoutParams(...))` 显式传参 |
| 11 | Spinner 在 MODE_NIGHT_YES 下白字浅底融合 | 自定义 ArrayAdapter 强制深色文字 |
| 12 | overlay 窗口内容 uiautomator 抓不到 | 像素分析（截图 → 纯 python PNG 解码找白簇）或推算坐标 |

---

## 7. 设计约定（改 UI 时遵守）

1. **主题化**：所有颜色/材质必须走 `UIKit.X` 动态委托或 `ThemeManager.current`，**禁止写死颜色**（否则切主题失效）
2. **默认主题**：暗色 = 第一版外观（纯色背景/纯色卡片/纯色平面按钮/蓝色分段胶囊），不要给暗色主题加极光/玻璃卡
3. **菜单触发只在 ⚙**：内容区不弹菜单（历史教训）
4. **实时生效 + 立即持久化**：样式调整不设"应用"按钮
5. **程序化构建**：延续 View 代码构建风格；若迁移 Compose 需整体重构
6. **版本管理**：versionCode 递增；标题显示版本号方便测试反馈定位
7. **bug 修复不回退**：菜单定位/滑杆黑块/毛玻璃等修复是既定行为，重构时保留
8. **中英文文案**：当前中文硬编码，后续可接 i18n

---

## 8. 版本历史（UI 相关）

| 版本 | 内容 |
|------|------|
| v0.8.0 (7) | iOS 风格深色现代化：ui/ 包（IOSMotion/UIKit）、分段控件、预设菜单、新滑杆、动画体系 |
| v0.9.0 (8) | Block Studio 浅色 Apple 官网风格（极光+毛玻璃 Bento Grid）——后被用户否决回退 |
| v0.10.0 (8) | Liquid Glass 液态玻璃：窗口级 blurBehindRadius、玻璃面板、深色极光、菜单玻璃化 |
| v0.10.1 | 修复：菜单与悬浮窗重叠（showAtLocation 手动定位+高度限制）、滑杆黑块（底色提亮+清背景） |
| v0.11.0 (9) | 三主题可切换（ThemeManager）+ VTuber 主题（AI 生成资产）+ 暗色主题还原第一版 + 按钮纯色化 |

## 9. 建议的 UI 改进方向（供接手参考）

- [x] 设置页完善：主题切换（齿轮入口）已落地
- [x] 字幕条菜单预设模板（高清/夜览/极简）已落地
- [ ] 通知栏字幕样式跟随 SubtitleStyle
- [ ] i18n 中英文
- [ ] 主题数量扩展（自定义主题色编辑）
- [ ] Compose 迁移评估（若团队倾向）
