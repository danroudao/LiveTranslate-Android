# LiveTranslate Android

[LiveTranslate](https://github.com/TheDeathDragon/LiveTranslate)（Windows / Python）的 Android 移植版：
实时音频翻译 —— 捕获系统音频 → VAD → ASR → LLM 翻译 → 悬浮字幕。

## 功能

- **系统音频捕获**：MediaProjection + AudioPlaybackCapture（API 29+），等价 WASAPI loopback
- **Silero VAD**：完整状态机移植（渐进静音 / 自适应静音 / 回溯切分 / 密度过滤），sherpa-onnx 推理
- **双 ASR 引擎**：
  - 远程：HTTP 服务器（与 Windows 版 `asr_server.py` 协议兼容，可共用同一 GPU 服务器）
  - 本地离线：sherpa-onnx SenseVoice int8（中/英/日/韩/粤）
- **增量 ASR**（interim）：边说边识别，完整句立即提交 + 回音去重
- **多协议 LLM 翻译**：
  - OpenAI 兼容（DeepSeek / DMX 中转 / OpenAI 等），SSE 流式
  - Anthropic（Claude 直连）
  - Gemini（Google 直连）
  - 思考型模型自动兼容（content null 防御 / 顶层 enable_thinking / 自动降级重试）
- **三种字幕显示**：顶部悬浮窗（可拖动/关闭）、无障碍字幕条（免悬浮窗权限）、通知栏字幕
- **多语言并行翻译**、多模型配置、模型下载管理（断点续传）、基准测试

## 技术栈

Kotlin · MediaProjection · sherpa-onnx 1.13.4（JitPack）· OkHttp · Jetpack AppCompat

## 构建

```bash
# 需要 Android SDK（compileSdk 34）、JDK 17、Gradle 8.7+
cd android-app
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

国内网络构建：Gradle 依赖走阿里云镜像（`settings.gradle.kts` 已配置），sherpa-onnx 走 JitPack。

## 快速使用

1. 安装 APK，授予悬浮窗权限
2. 模型管理 → 新增模型：
   - 点 `[DMX]` / `[DeepSeek]` 等平台预设（自动填充 API Base/模型名/超时）
   - 填 API Key → 点"获取模型列表"选择模型（防手输错误）
3. 选择 ASR 引擎：远程 ASR（填服务器地址）或本地 SenseVoice（模型管理页下载，约 239MB）
4. 点"授权并开始捕获翻译"→ 系统弹窗选 Start now
5. 播放外语视频 → 悬浮窗实时显示译文

## 文档

- [移植方案（可行性分析/架构设计/分阶段计划）](DEVELOPMENT_PLAN_zh.md)
- [环境搭建 / 踩坑记录 / 验证记录](ENV_SETUP_zh.md)

## 目录结构

```
android-app/
├── app/src/main/java/com/example/livetranslate/
│   ├── pipeline/   音频捕获、VAD、管线服务、悬浮窗、无障碍字幕
│   ├── net/        RemoteAsrClient（远程 ASR 协议）、LlmTranslator（多协议翻译）
│   ├── asr/        LocalAsrEngine（sherpa-onnx SenseVoice）
│   ├── model/      模型配置、下载管理
│   └── benchmark/  基准测试
├── build.gradle.kts / settings.gradle.kts
```

## License

MIT
