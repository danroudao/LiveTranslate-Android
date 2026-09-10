# LiveTranslate Android

[LiveTranslate](https://github.com/TheDeathDragon/LiveTranslate)（Windows / Python）的 Android 移植版：
实时音频翻译 —— 捕获系统音频 → VAD → ASR → LLM 翻译 → 悬浮字幕。

当前基线：**v0.12.8**（含本地小模型翻译引擎与 ASR 准确性优化）

## 功能

- **系统音频捕获**：MediaProjection + AudioPlaybackCapture（API 29+），等价 WASAPI loopback
- **Silero VAD**：完整状态机移植（渐进静音 / 自适应静音 / 回溯切分 / 密度过滤），sherpa-onnx 推理
  - v0.13 调优：threshold 0.45 / min_silence 0.6s / max_speech 8s（快语速/口音适配）
- **双 ASR 引擎**：
  - 远程：HTTP 服务器（与 Windows 版 `asr_server.py` 协议兼容，可共用同一 GPU 服务器）
  - 本地离线：sherpa-onnx SenseVoice int8（中/英/日/韩/粤，固定 en 提示提升口音识别）
  - 本地备选：Whisper tiny/base（sherpa-onnx，英文口音鲁棒；x86_64 模拟器崩溃，arm64 可用）
- **增量 ASR**（interim）：边说边识别，完整句立即提交 + 回音去重
  - 句切分优化：英文缩写（Mr./U.S./etc.）、数字小数点（3.14）、省略号、连续标点、闭合引号保护（17 用例全过）
- **多协议 LLM 翻译**：
  - OpenAI 兼容（DeepSeek / DMX 中转 / OpenAI 等），SSE 流式
  - Anthropic（Claude 直连）
  - Gemini（Google 直连）
  - 思考型模型自动兼容（content null 防御 / 顶层 enable_thinking / 自动降级重试）
  - **本地引擎（v0.12+）**：内嵌 llama.cpp（`protocol=local`，Qwen3.5 GGUF 0.8B/2B/4B 非思考模式，完全离线）
- **三种字幕显示**：顶部悬浮窗（液态玻璃真毛玻璃，可拖动/关闭）、无障碍字幕条（免悬浮窗权限）、通知栏字幕
  - v0.12.7：全透明纯字幕模式（背景/毛玻璃归零，仅文字+阴影；按钮自动隐藏、点击字幕条呼出；通知栏「隐藏/显示字幕窗」兑底开关）
  - v0.12.8：横竖屏自适应（旋转/折屏重排，横竖屏各自记住用户拖动/尺寸，横屏文本居中）+ 字幕模式切换（仅译文 / 双语）
- **三主题可切换**（右上角齿轮）：🌙 暗色液态玻璃（默认·第一版）/ ☀️ 浅色 Apple / 💜 VTuber 紫
- **多语言并行翻译**、多模型配置、模型下载管理（断点续传）
- **模型管理分组页（v0.12.5+）**：引擎分组（ASR/LLM）+ 直观名称 + 一键下载自动配套文件
- **基准测试**、状态日志截断（200 行防膨胀）、Android 14 FGS 权限等待防御

## 技术栈

Kotlin · MediaProjection · sherpa-onnx 1.13.4（JitPack）· llama.cpp（NDK 交叉编译，libllmengine.so）· OkHttp · Jetpack AppCompat

## 构建

```bash
# 需要 Android SDK（compileSdk 34）、JDK 17、Gradle 8.7+、NDK 26.1.10909125
cd android-app
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

国内网络构建：Gradle 依赖走阿里云镜像（`settings.gradle.kts` 已配置），sherpa-onnx 走 JitPack。
NDK 交叉编译脚本：`android-app/tools/ndk-build.sh`（预编译静态库已入库 `app/src/main/cpp/prebuilt/`）。

## 快速使用

1. 安装 APK，授予悬浮窗权限
2. 模型管理 → 新增模型：
   - 点 `[DMX]` / `[DeepSeek]` 等平台预设（自动填充 API Base/模型名/超时）
   - 填 API Key → 点"获取模型列表"选择模型（防手输错误）
3. 选择 ASR 引擎：远程 ASR（填服务器地址）或本地 SenseVoice（模型管理页下载，约 239MB）
4. 点"① 授权并开始捕获翻译"→ 系统弹窗选 Start now
5. 播放外语视频 → 悬浮窗实时显示译文

完全离线翻译（可选）：模型管理页下载 Qwen3.5 GGUF（0.8B/2B/4B）→ 翻译模型新增/编辑选择"本地 LLM"协议 → 模型名填 GGUF 文件名。

## 文档

- [UI 开发接手指南（代码导航/UI 模块/主题系统/数据流/构建测试/踩坑）](UI_DEV_GUIDE_zh.md)
- [移植方案（可行性分析/架构设计/分阶段计划）](DEVELOPMENT_PLAN_zh.md)
- [环境搭建 / 踩坑记录 / 验证记录](ENV_SETUP_zh.md)
- [本地小模型翻译可行性验证报告（Qwen3.5 GGUF / MiniMind 评测 / 性能与稳定性）](LOCAL_LLM_TEST_zh.md)

## 目录结构

```
android-app/
├── app/src/main/java/com/example/livetranslate/
│   ├── ui/        主题引擎（ThemeManager）、组件库（UIKit）、液态玻璃（LiquidGlass）、动画（IOSMotion）
│   ├── pipeline/   音频捕获、VAD、管线服务、悬浮窗、无障碍字幕、增量句切分（InterimSplitter）
│   ├── net/        RemoteAsrClient（远程 ASR 协议）、LlmTranslator（多协议翻译 + 本地引擎）
│   ├── asr/        LocalAsrEngine（SenseVoice/Whisper 自动推断）、LocalLlmEngine（JNI 封装）
│   ├── model/      模型配置、分组下载管理（ModelRepository/GROUPS）
│   └── benchmark/  基准测试
├── app/src/main/cpp/    llama.cpp JNI 封装（jni_llm.cpp + prebuilt 静态库）
├── tools/ndk-build.sh   NDK 交叉编译脚本
└── build.gradle.kts / settings.gradle.kts
```

## License

MIT
