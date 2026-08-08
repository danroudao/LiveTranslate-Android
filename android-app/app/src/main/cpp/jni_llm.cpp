// jni_llm.cpp — 安卓端内嵌 llama.cpp 推理引擎 JNI 封装
// 依赖：libllama.a + libggml.a（NDK 交叉编译预置）
// 特性：LLAMA_LOAD_MODE_NONE（规避 fscrypt mmap 缺页解密）、精简 prompt（<|im_start|> 手工模板）、
//       temp/top_p 采样、流式 token 回调
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>

#include "llama.h"

#define TAG "LocalLlmEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * smpl = nullptr;
    std::mutex mutex;  // 串行化生成（单槽语义）
    bool ready = false;
    int nCtx = 1024;
};

constexpr int MAX_NEW_TOKENS = 128;

Engine * get_engine(jlong h) {
    return reinterpret_cast<Engine *>(h);
}

void throw_java(JNIEnv * env, const char * msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg);
}

// 拼接 Qwen 系 chatml 非思考格式 prompt（等价于 enable_thinking=false 的模板渲染：
// assistant 前缀后带空 think 块，模型跳过思考直接回答）
std::string build_prompt(const std::string & sys, const std::string & user) {
    std::string p;
    p.reserve(sys.size() + user.size() + 128);
    p += "<|im_start|>system\n";
    p += sys;
    p += "<|im_end|>\n";
    p += "<|im_start|>user\n";
    p += user;
    p += "<|im_end|>\n";
    p += "<|im_start|>assistant\n";
    p += "<think>\n\n</think>\n\n";
    return p;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_livetranslate_asr_LocalLlmEngine_nativeInit(
    JNIEnv * env, jobject, jstring modelPath, jint nCtx, jint nThreads)
{
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return 0;
    Engine * e = new Engine();
    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_NONE;          // 关键：不 mmap（Android fscrypt 加密分区 mmap 性能灾难）
    LOGI("loading model: %s (threads=%d)", path, (int) nThreads);
    e->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!e->model) {
        LOGE("model load failed");
        delete e;
        throw_java(env, "模型加载失败（文件损坏或格式不支持）");
        return 0;
    }
    e->vocab = llama_model_get_vocab(e->model);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 1024;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = 6;  // prefill 并行线程（模拟器 6 vCPU，prefill 是大头）
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;  // Flash Attention 加速（外部 llama-server 实测有效）
    e->ctx = llama_init_from_model(e->model, cparams);
    e->nCtx = nCtx > 0 ? (int) nCtx : 1024;
    if (!e->ctx) {
        LOGE("ctx create failed");
        llama_model_free(e->model);
        delete e;
        throw_java(env, "上下文创建失败（内存不足？）");
        return 0;
    }
    auto sparams = llama_sampler_chain_default_params();
    e->smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(e->smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_dist(0));
    e->ready = true;
    LOGI("model ready");
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jstring JNICALL
Java_com_example_livetranslate_asr_LocalLlmEngine_nativeChat(
    JNIEnv * env, jobject, jlong handle, jstring system, jstring user, jobject callback)
{
    Engine * e = get_engine(handle);
    if (!e || !e->ready) {
        throw_java(env, "引擎未初始化");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(e->mutex);

    // 清空 KV cache：每次翻译从头写（显式 pos 0 覆盖会与旧 cell 冲突导致 decode 失败）
    llama_memory_clear(llama_get_memory(e->ctx), false);

    LOGI("chat: prompt build");

    const char * sys = env->GetStringUTFChars(system, nullptr);
    const char * usr = env->GetStringUTFChars(user, nullptr);
    std::string prompt = build_prompt(sys ? sys : "", usr ? usr : "");
    env->ReleaseStringUTFChars(system, sys);
    env->ReleaseStringUTFChars(user, usr);

    // 回调对象：GlobalRef + 线程附加
    JavaVM * jvm = nullptr;
    env->GetJavaVM(&jvm);
    jobject cb = callback ? env->NewGlobalRef(callback) : nullptr;

    std::vector<llama_token> toks;
    {
        // 新版 llama.cpp：成功时返回 -n_tokens（负数），非负表示错误
        const int n = llama_tokenize(e->vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
        if (n >= 0) {
            if (cb) env->DeleteGlobalRef(cb);
            throw_java(env, "tokenize 失败");
            return nullptr;
        }
        const int n_tokens = -n;
        toks.resize(n_tokens);
        if (llama_tokenize(e->vocab, prompt.c_str(), prompt.size(), toks.data(), toks.size(), true, true) < 0) {
            if (cb) env->DeleteGlobalRef(cb);
            throw_java(env, "tokenize 失败");
            return nullptr;
        }
    }
    LOGI("chat: tokenized %zu tokens", toks.size());
    const int64_t t0_ms = llama_time_us() / 1000;

    // 显式 batch pos：每次翻译从位置 0 覆盖旧 KV（llama_batch_get_one 的 null pos 会从 KV 末尾继续，
    // 多次翻译后 KV 写满 n_ctx 导致 decode 失败 —— 已修复）
    if ((int) toks.size() + MAX_NEW_TOKENS > (int) e->nCtx) {
        if (cb) env->DeleteGlobalRef(cb);
        throw_java(env, "prompt 过长，超出上下文");
        return nullptr;
    }
    llama_batch batch = llama_batch_init(toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++) {
        batch.token[i] = toks[i];
        batch.pos[i] = (llama_pos) i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == toks.size() - 1);
    }
    batch.n_tokens = (int32_t) toks.size();

    if (llama_model_has_encoder(e->model)) {
        if (llama_encode(e->ctx, batch)) {
            llama_batch_free(batch);
            if (cb) env->DeleteGlobalRef(cb);
            throw_java(env, "encoder 失败");
            return nullptr;
        }
        llama_token decoder_start = llama_model_decoder_start_token(e->model);
        if (decoder_start == LLAMA_TOKEN_NULL) decoder_start = llama_vocab_bos(e->vocab);
        batch.n_tokens = 1;
        batch.token[0] = decoder_start;
        batch.pos[0] = (llama_pos) toks.size();
        batch.logits[0] = true;
    }
    if (llama_decode(e->ctx, batch)) {
        llama_batch_free(batch);
        if (cb) env->DeleteGlobalRef(cb);
        throw_java(env, "decode 失败");
        return nullptr;
    }
    LOGI("chat: prefill %lld ms", (long long) (llama_time_us() / 1000 - t0_ms));

    // 流式回调：nativeChat 由 Java 线程阻塞调用，直接用传入 env（禁止 Attach/Detach ——
    // 对 JVM 创建的线程 Detach 会使 env 失效导致堆损坏崩溃）
    jmethodID onToken = cb ? env->GetMethodID(env->GetObjectClass(cb), "onToken", "(Ljava/lang/String;)V") : nullptr;

    std::string out;
    char buf[64];
    int n_generated = 0;
    int64_t t_decode = 0, t_sample = 0, t_cb = 0;
    for (;;) {
        int64_t t0_tok = llama_time_us();
        llama_token id = llama_sampler_sample(e->smpl, e->ctx, -1);
        t_sample += llama_time_us() - t0_tok;
        if (llama_vocab_is_eog(e->vocab, id)) { LOGI("chat: eog at %d", n_generated); break; }
        int n = llama_token_to_piece(e->vocab, id, buf, sizeof(buf), 0, true);
        if (n <= 0) continue;
        std::string piece(buf, n);
        out += piece;
        t0_tok = llama_time_us();
        if (cb && onToken) {
            jstring js = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(cb, onToken, js);
            env->DeleteLocalRef(js);
        }
        t_cb += llama_time_us() - t0_tok;
        if (++n_generated >= MAX_NEW_TOKENS) break;
        batch.n_tokens = 1;
        batch.token[0] = id;
        batch.pos[0] = (llama_pos) (toks.size() + n_generated);
        batch.logits[0] = true;
        t0_tok = llama_time_us();
        if (llama_decode(e->ctx, batch)) {
            LOGE("decode failed at token %d", n_generated);
            break;
        }
        t_decode += llama_time_us() - t0_tok;
    }
    LOGI("chat: gen %d tokens: decode %lld ms (%.1f ms/tok) sample %lld ms cb %lld ms | total(prefill+gen) %lld ms",
        n_generated, (long long) (t_decode / 1000), t_decode / 1000.0 / (n_generated > 0 ? n_generated : 1),
        (long long) (t_sample / 1000), (long long) (t_cb / 1000), (long long) (llama_time_us() / 1000 - t0_ms));
    LOGI("chat: gen %d tokens in %lld ms", n_generated, (long long) (llama_time_us() / 1000 - t0_ms));
    llama_batch_free(batch);
    if (cb) env->DeleteGlobalRef(cb);

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_livetranslate_asr_LocalLlmEngine_nativeClose(JNIEnv *, jobject, jlong handle)
{
    Engine * e = get_engine(handle);
    if (!e) return;
    if (e->smpl) llama_sampler_free(e->smpl);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // extern "C"
