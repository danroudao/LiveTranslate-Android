# 安卓端本地部署小模型翻译 — 可行性验证报告（2026-08-08）

> 目标：在安卓端本地部署 Qwen3.5 系列小模型（GGUF）替代远程 LLM API 做实时字幕翻译，
> 在 android-emu 容器（模拟器）内完成全量测试。
> 推荐基座：Qwen3.5 2B / 4B 非思考模式；0.8B 视可用性纳入参考。

## 结论摘要

**✅ 完全可行**。llama.cpp 静态二进制 + Qwen3.5 GGUF 在 Android（x86_64 模拟器）上以
OpenAI 兼容 API 提供本地翻译，App 零协议改动（仅补一个参数）即可接入。

| 模型 (Q4_K_M) | App 基准 6 句 | 平均延迟 | 扩展集 20 句 | 翻译质量 |
|---|---|---|---|---|
| **Qwen3.5-0.8B** | ✅ 100% | **1408ms** | ✅ 20/20 | 达标但瑕疵多（漏字/习语直译/服药句误译） |
| **Qwen3.5-2B** | ✅ 100% | 2750ms | ✅ 20/20 | 良好（电竞/动漫术语准确） |
| **Qwen3.5-4B** | ✅ 100% | 6757ms | ✅ 20/20 | 优秀（"大家好，这是直播翻译测试"） |

> 延迟为模拟器（6 vCPU KVM 加速）内 App 端到端数据（含流式渲染）；真机 arm64 预计快 2-4 倍。
> 实时字幕场景建议：**2B 为主力（延迟/质量平衡），4B 追求质量，0.8B 作为低配兜底**。

## 端到端管线实测（模拟器内完整链路）

```
系统音频 (MediaProjection) → Silero VAD → SenseVoice 本地 ASR (169-1443ms/段)
→ Qwen3.5-0.8B 本地翻译 (非思考) → 液态玻璃悬浮窗字幕
```

logcat 实测：
```
ASR [<|en|>] 644ms: hello everyone this is a live translation test we are testing the audio capture pipeline on android
译文: 大家好，这是直播测试，我们正在测试Android音频捕捉管道。
```

## 部署方式

| 项 | 说明 |
|---|---|
| 推理引擎 | llama.cpp `llama-server`，**纯静态编译**（musl/glibc -static），`adb push` 到 `/data/local/tmp` 直接运行（root 环境） |
| 模型 | unsloth GGUF `Qwen3.5-{0.8B,2B,4B}-Q4_K_M.gguf`（hf-mirror 下载） |
| 接口 | OpenAI 兼容 `http://127.0.0.1:8899/v1`，App 配置 apiBase 指向本地即可 |
| 非思考模式 | **`chat_template_kwargs: {"enable_thinking": false}`**（llama.cpp 唯一识别；顶层 `enable_thinking:false` 对它无效） |

### App 代码改动（已合入）

`LlmTranslator.buildOpenAiBody`：noThink 时双发 `chat_template_kwargs.enable_thinking=false`
（对远程 API 无害——未知字段被忽略；对本地 llama.cpp 生效，避免进入思考模式耗尽 max_tokens）。

## 关键踩坑（复现必读）

| # | 坑 | 解决 |
|---|----|------|
| 1 | **mmap 加载 GGUF 性能灾难**：Android /data 是 fscrypt 加密分区，mmap 缺页逐页解密 → prefill 慢 300 倍（2.4 tok/s） | **必须 `--no-mmap`**（读入内存，0.8B 恢复到 130+ tok/s） |
| 2 | 模拟器 guest CPU 无 AVX2（仅 AVX+SSE4.2），`-march=native` 编译 → SIGILL (132) | `-DGGML_NATIVE=OFF -DGGML_AVX2=OFF -DGGML_AVX=ON` 重编 |
| 3 | 4GB RAM 模拟器跑 4B 模型 → lmkd 杀进程 | 模拟器 `-memory 8192` 重启；root 进程可再 `oom_score_adj=-1000` 兜底 |
| 4 | glibc 动态链接二进制无法在 Android 跑 | 静态编译（musl 或 glibc `-static -no-pie`） |
| 5 | `adb shell` 后台进程随会话退出被杀 | `setsid ... < /dev/null &` |
| 6 | `pkill -f llama-server` 会误杀 adb 会话链（docker exec 返回 143） | 用 `pkill -x llama-server` |
| 7 | AVD userdata 分区 5.8GB 装不下 3 个模型 | 分轮 push / 测完即删 |
| 8 | 0.8B 思考模式泄漏（顶层 enable_thinking 无效） | chat_template_kwargs（见上） |

## 下一步（App 内嵌集成路径）

1. **NDK 编译 llama.cpp** 为 `libllama.so`（llama.cpp 无官方 AAR，需自编 JNI 封装或移植 llama.rn 的 android 模块）
2. `ModelConfig` 增加 `protocol="local"`，`LocalLlmEngine` 与远程 `LlmTranslator` 接口对齐（流式回调）
3. GGUF 下载管理复用 `ModelRepository`（ModelScope/hf-mirror 双源，断点续传）
4. 模型加载线程 + 实例重建（对齐 `LocalAsrEngine` 模式）；`:llm` 进程隔离可选加固
5. 真机验证（arm64）延迟/发热曲线
