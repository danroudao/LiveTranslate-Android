package com.example.livetranslate.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.livetranslate.MainActivity
import com.example.livetranslate.R
import com.example.livetranslate.asr.AsrEngine
import com.example.livetranslate.asr.LocalAsrEngine
import com.example.livetranslate.net.AsrResult
import com.example.livetranslate.net.LlmTranslator
import com.example.livetranslate.net.RemoteAsrClient
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 管线前台服务 —— main.py LiveTranslateApp 的 Kotlin 移植。
 *
 * 捕获线程 → Silero/Energy VAD 分段 → ASR 队列线程
 *   ├─ vad_flush: 整段 ASR → 翻译（异步）
 *   └─ interim: 边说边识别（每 ~2s），完整句立即提交并 trim_front
 * 翻译结果 → TYPE_APPLICATION_OVERLAY 悬浮窗 + 通知。
 */
class CaptureService : Service() {

    interface Listener {
        fun onStatus(text: String)
        fun onSegment(asrText: String, lang: String)
        fun onTranslation(text: String)
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "livetranslate"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.example.livetranslate.START"
        const val ACTION_STOP = "com.example.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ASR_URL = "asr_url"
        const val EXTRA_MODEL_JSON = "model_json"
        const val EXTRA_ASR_MODE = "asr_mode"  // remote | local
        const val EXTRA_MODEL_DIR = "model_dir"
        const val EXTRA_TARGET_LANG = "target_lang"

        /** 状态回调（Activity 观察用） */
        var listener: Listener? = null

        /** 服务是否运行中（MainActivity 停止按钮用） */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    // 管线组件
    private var projection: MediaProjection? = null
    private var capturer: AudioCapturer? = null
    private var engine: AsrEngine? = null
    private var translator: LlmTranslator? = null
    private var overlay: OverlayManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // VAD + interim
    private val vad = VadProcessor()
    private val splitter = InterimSplitter()
    private var interimActive = false
    private var interimPending = ""
    private var lastInterimSamples = 0
    private var lastInterimCheckTime = 0L
    private var committedTail = ""
    private var interimEnabled = true
    private var interimNullCount = 0

    // 段队列：捕获线程入队，ASR 线程消费（对应 _asr_queue maxsize=16）
    private val segmentQueue = LinkedBlockingQueue<Pair<String, FloatArray?>>(16)
    private val asrExecutor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newFixedThreadPool(2)
    /** 本地 LLM（llama.cpp 单槽）专用串行执行器：避免并发排队，且让前缀缓存持续命中 */
    private val localExecutor = Executors.newSingleThreadExecutor()

    private var running = false

    /** 附加目标语言（来自活动模型配置） */
    private var extraLanguages: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        // Silero VAD 引擎（assets 中的 onnx 模型）
        try {
            vad.silero = SileroVadEngine(this)
            Log.i(TAG, "Silero VAD 引擎就绪")
        } catch (e: Throwable) {
            Log.w(TAG, "Silero VAD 加载失败，回退能量模式: ${e.message}")
            vad.mode = "energy"
            vad.threshold = 0.012
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPipeline()
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val asrUrl = intent.getStringExtra(EXTRA_ASR_URL) ?: "http://172.17.0.1:8765"
                val asrMode = intent.getStringExtra(EXTRA_ASR_MODE) ?: "remote"
                val modelDir = intent.getStringExtra(EXTRA_MODEL_DIR) ?: ""
                val modelJson = intent.getStringExtra(EXTRA_MODEL_JSON)
                val model = try {
                    com.example.livetranslate.model.ModelConfig.fromJson(
                        org.json.JSONObject(modelJson ?: "{}")
                    )
                } catch (e: Exception) {
                    com.example.livetranslate.model.ModelConfig(
                        name = "DeepSeek", apiBase = "https://api.deepseek.com/v1",
                        apiKey = "", model = "deepseek-chat", targetLanguage = "zh",
                    )
                }
                startPipeline(resultCode, data, asrUrl, asrMode, modelDir, model)
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("LiveTranslate 运行中")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val style = Notification.BigTextStyle().bigText(text)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveTranslate")
            .setContentText(text.take(80))
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** 通知栏字幕：原文 + 译文（BigTextStyle，锁屏可见）—— 500ms 节流合并，避免频繁 notify 卡顿 */
    private var lastNotifTs = 0L
    private var pendingNotif: Pair<String, String>? = null
    private fun updateNotificationSubtitle(original: String, translation: String) {
        handler.post {
            pendingNotif = original to translation
            val now = System.currentTimeMillis()
            if (now - lastNotifTs < 500) return@post   // 节流：合并高频更新
            lastNotifTs = now
            val (o, t) = pendingNotif ?: return@post
            pendingNotif = null
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("$o\n\n$t"))
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "实时翻译", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ---------- 管线 ----------

    private fun startPipeline(
        resultCode: Int,
        data: Intent?,
        asrUrl: String,
        asrMode: String,
        modelDir: String,
        model: com.example.livetranslate.model.ModelConfig,
    ) {
        if (running) stopPipeline()
        if (data == null) {
            postStatus("缺少 MediaProjection 授权结果")
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
        if (projection == null) {
            postStatus("MediaProjection 获取失败")
            return
        }
        this.projection = projection
        this.engine = if (asrMode == "local" && modelDir.isNotEmpty()) {
            try {
                // 固定 en 语言提示：SenseVoice auto 模式在口音英语上会误检语言（实测印度口音被检成 ja/zh）
                LocalAsrEngine(this, modelDir, language = "en")
            } catch (e: Throwable) {
                postStatus("本地 ASR 加载失败: ${e.message}（回退远程）")
                RemoteAsrClient(asrUrl)
            }
        } else {
            RemoteAsrClient(asrUrl)
        }
        this.translator = LlmTranslator(
            apiBase = model.apiBase,
            apiKey = model.apiKey,
            model = model.model,
            targetLanguage = model.targetLanguage,
            streaming = model.streaming,
            systemPrompt = model.systemPrompt,
            noSystemRole = model.noSystemRole,
            noThink = model.noThink,
            jsonResponse = model.jsonResponse,
            contextTurns = model.contextTurns,
            timeoutSec = model.timeout.toLong(),
            protocol = model.protocol,
            modelDir = java.io.File(this.filesDir, "models/llm").absolutePath,
        )
        this.overlay = OverlayManager(this, com.example.livetranslate.model.SettingsStore(this)).also { it.show() }
        this.extraLanguages = model.extraLanguages
        running = true
        isRunning = true
        interimActive = false
        interimPending = ""
        committedTail = ""
        vad.hardReset()
        vad.silero?.resetState()

        // 异步确认远程 ASR 服务器可达（本地模式跳过）
        if (asrMode != "local") {
            netExecutor.execute {
                val remote = engine as? RemoteAsrClient
                val ok = remote?.health()
                postStatus(if (ok != null) "ASR 服务器在线: $ok" else "⚠️ ASR 服务器不可达: $asrUrl")
            }
        }

        // 捕获线程：chunk → VAD；语音中时按间隔触发 interim
        capturer = AudioCapturer(
            projection = projection,
            onChunk = { chunk ->
                if (!running) return@AudioCapturer
                var seg: FloatArray? = null
                synchronized(vad) {
                    seg = vad.processChunk(chunk)
                }
                if (seg != null) {
                    enqueueAsr("vad_flush", seg)
                } else if (interimEnabled && vad.isSpeaking && vad.lastConfidence >= 0.3) {
                    // 仅在最近有真实语音迹象时触发 interim（避免静音段循环识别）
                    val bufSamples = vad.speechSamples
                    val totalDur = bufSamples.toDouble() / 16000
                    val elapsed = (bufSamples - lastInterimSamples).toDouble() / 16000
                    val now = System.currentTimeMillis()
                    val cooldown = now - lastInterimCheckTime
                    if (totalDur >= 2.0 && elapsed >= 2.0 && cooldown >= 1000) {
                        lastInterimCheckTime = now
                        enqueueAsr("interim", null)
                    }
                }
            },
            onError = { msg -> postStatus("音频错误: $msg") },
        )

        val started = capturer?.start() == true
        if (!started) {
            postStatus("音频捕获启动失败（检查悬浮窗/录音权限）")
            stopPipeline()
            return
        }
        postStatus("管线运行中 → $asrUrl（VAD: ${vad.mode}）")

        // ASR 消费线程
        asrExecutor.execute {
            while (running || segmentQueue.isNotEmpty()) {
                val item = segmentQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val type = item.first
                when (type) {
                    "vad_flush" -> {
                        val seg = item.second ?: continue
                        if (interimActive) processInterimFinal(seg) else processSegment(seg)
                        interimActive = false
                        interimPending = ""
                        lastInterimSamples = 0
                        committedTail = ""
                    }
                    "interim" -> {
                        drainInterimDuplicates()
                        doInterimAsr()
                        synchronized(vad) { lastInterimSamples = vad.speechSamples }
                    }
                }
            }
        }
    }

    private fun enqueueAsr(type: String, seg: FloatArray?) {
        if (!segmentQueue.offer(type to seg)) {
            // 队列满：丢最旧（对应 _enqueue_asr drop-oldest）
            segmentQueue.poll()
            segmentQueue.offer(type to seg)
        }
    }

    private fun drainInterimDuplicates() {
        while (true) {
            val item = segmentQueue.poll() ?: break
            if (item.first != "interim") {
                // 非 interim 任务放回队首
                val rest = ArrayList<Pair<String, FloatArray?>>()
                segmentQueue.drainTo(rest)
                segmentQueue.offer(item)
                for (r in rest) segmentQueue.offer(r)
                break
            }
        }
    }

    // ---------- ASR + 提交 ----------

    /** 整段处理：ASR → 过滤 → 提交 */
    private fun processSegment(audio: FloatArray) {
        val result = runAsr(audio) ?: return
        val text = result.text.trim()
        if (text.isEmpty() || !text.any { it.isLetterOrDigit() }) return
        processSegmentText(text, result.language)
    }

    /** interim 结束冲刷：strip 回音重叠 + 合并短句缓冲 */
    private fun processInterimFinal(audio: FloatArray) {
        val result = runAsr(audio) ?: return
        var text = splitter.stripCommittedOverlap(result.text.trim(), committedTail)
        if (interimPending.isNotEmpty()) {
            text = interimPending + text
            interimPending = ""
        }
        if (text.isEmpty() || !text.any { it.isLetterOrDigit() }) return
        processSegmentText(text, result.language)
    }

    private fun runAsr(audio: FloatArray): AsrResult? {
        val t0 = System.currentTimeMillis()
        val result = try {
            engine?.transcribe(audio)
        } catch (e: Exception) {
            postStatus("ASR 异常: ${e.message}")
            null
        }
        if (result == null) {
            postStatus("ASR 返回空（服务器繁忙？）")
            return null
        }
        val asrMs = System.currentTimeMillis() - t0
        postStatus("ASR [${result.language}] ${asrMs}ms: ${result.text}")
        return result
    }

    /** 提交一条识别文本：更新 UI + 异步翻译 */
    private fun processSegmentText(text: String, sourceLang: String) {
        listener?.onSegment(text, sourceLang)
        overlay?.update(text, "")
        val translator = this.translator ?: return
        val modelName = translator.model
        val modelId = translator.apiBase
        if (translator.protocol != "local" && translator.apiKey.isEmpty()) {
            overlay?.update(text, "(未配置 API Key)")
            return
        }
        // 本地推理服务单槽串行处理：并发请求会排队且前缀缓存失效，串行化后每句直接命中缓存
        val exec = if (LlmTranslator.isLocalApiBase(translator.apiBase)) localExecutor else netExecutor
        exec.execute {
            translator.translateStreaming(
                text, sourceLang,
                onPartial = { partial ->
                    // 本地引擎：翻译极快（~0.35s），流式整合过程不上字幕条（用户反馈跳动），onFinal 一次性显示
                    if (translator.protocol != "local") overlay?.update(text, partial)
                },
                onFinal = { final ->
                    overlay?.update(text, final)
                    listener?.onTranslation(final)
                    postStatus("译文: $final")
                    updateNotificationSubtitle(text, final)
                    // 无障碍字幕条（若已开启）
                    if (com.example.livetranslate.pipeline.SubtitleAccessibilityService.isActive) {
                        com.example.livetranslate.pipeline.SubtitleAccessibilityService.updateSubtitle("$text\n$final")
                    }
                    // 附加语言并行翻译（对应 _translate_extra_langs）
                    translateExtraLanguages(text, sourceLang, final)
                },
                onError = { err ->
                    overlay?.update(text, "[翻译失败: $err]")
                    postStatus("翻译失败[$modelName/$modelId]: $err")
                },
            )
        }
    }

    /** 附加目标语言并行翻译，结果合并到通知/无障碍字幕 */
    private fun translateExtraLanguages(text: String, sourceLang: String, primary: String) {
        val extra = extraLanguages.filter { it != translator?.targetLanguage && it.isNotBlank() }
        if (extra.isEmpty()) return
        val results = java.util.concurrent.ConcurrentHashMap<String, String>()
        val done = java.util.concurrent.CountDownLatch(extra.size)
        // 本地模式：附加语言也走串行执行器（避免本地单槽服务器排队）
        val exec = if (LlmTranslator.isLocalApiBase(translator?.apiBase ?: "")) localExecutor else netExecutor
        for (lang in extra) {
            exec.execute {
                try {
                    val t = translator ?: return@execute
                    val r = t.withTargetLanguage(lang).translate(text, sourceLang)
                    if (r != null) {
                        results[lang] = r
                        Log.i(TAG, "extra translate [$lang]: $r")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extra translate [$lang] failed: ${e.message}")
                } finally {
                    done.countDown()
                }
            }
        }
        // 主线程延迟合并显示（附加翻译完成后更新通知字幕）
        Thread {
            try {
                done.await(30, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
            }
            Log.i(TAG, "extra merge: results=${results.size} for text='${text.take(30)}'")
            if (results.isNotEmpty()) {
                // 多语言合并显示：悬浮窗译文区 + 无障碍字幕条（多行）
                val sb = StringBuilder(primary)
                for ((lang, r) in results) sb.append("\n[$lang] $r")
                overlay?.update(text, sb.toString())
                if (com.example.livetranslate.pipeline.SubtitleAccessibilityService.isActive) {
                    com.example.livetranslate.pipeline.SubtitleAccessibilityService.updateSubtitle("$text\n$sb")
                }
            }
        }.start()
    }

    // ---------- interim 增量 ASR ----------

    /** 对当前 VAD 缓冲做增量识别：提交完整句 + 裁剪已消费音频 */
    private fun doInterimAsr(): Boolean {
        val peek: Pair<FloatArray, Double>?
        synchronized(vad) { peek = vad.peekBuffer() }
        if (peek == null) return false
        val (audio, duration) = peek
        if (duration < 1.5) return false  // 太短不识别

        val result = runAsr(audio)
        if (result == null) {
            // 连续空结果（静音/噪声）：重置 VAD 避免死循环
            interimNullCount++
            if (interimNullCount >= 5) {
                synchronized(vad) { vad.hardReset() }
                interimNullCount = 0
                postStatus("VAD 重置（连续静音）")
            }
            return false
        }
        interimNullCount = 0
        var fullText = result.text.trim()
        if (fullText.isEmpty() || !fullText.any { it.isLetterOrDigit() }) return false

        fullText = splitter.stripCommittedOverlap(fullText, committedTail)
        if (fullText.isEmpty()) return false

        // 回声启发式：新文本基本是已提交尾巴的重复（残余音频被反复识别）→ 不提交，大幅 trim
        val tailCore = committedTail.replace(" ", "").lowercase()
        val textCore = fullText.replace(" ", "").lowercase()
        if (tailCore.length > 8 &&
            textCore.length <= tailCore.length + 12 &&
            textCore.contains(tailCore.takeLast(minOf(8, tailCore.length)))
        ) {
            Log.i(TAG, "echo detected, trimming without commit: '$fullText'")
            val trimTo = (audio.size - (0.3 * 16000).toInt()).coerceAtLeast(0)
            synchronized(vad) { vad.trimFront(trimTo) }
            return true
        }

        val sentences = splitter.splitSentences(fullText)
        if (sentences.size <= 1) return false  // 还没说完一句

        // 除最后一句外都是完整句（最后一句可能还在说）
        val complete = sentences.subList(0, sentences.size - 1)
        val committed = complete.joinToString("")
        if (committed.isBlank()) return false

        // 提交完整句（短句进 pending 缓冲）
        var actuallyCommitted = false
        for (sent in complete) {
            val s = sent.trim()
            if (s.isEmpty()) continue
            if (splitter.isShortUtterance(s)) {
                interimPending += s
                Log.d(TAG, "short utterance buffered: '$s'")
                continue
            }
            val finalText = if (interimPending.isNotEmpty()) {
                (interimPending + s).also { interimPending = "" }
            } else s
            processSegmentText(finalText, result.language)
            actuallyCommitted = true
        }
        if (!actuallyCommitted) return false

        // 比例裁剪 + 0.3s 安全余量；保留 ≥0.5s 给剩余句
        val totalSamples = audio.size
        val ratio = committed.length.toDouble() / maxOf(fullText.length, 1)
        var trimSamples = (ratio * totalSamples).toInt() + (0.3 * 16000).toInt()
        val maxTrim = totalSamples - (0.5 * 16000).toInt()
        trimSamples = minOf(trimSamples, maxOf(maxTrim, 0))
        val minTrim = (0.3 * 16000).toInt()
        if (trimSamples < minTrim && trimSamples > 0) {
            trimSamples = minOf(minTrim, totalSamples / 2)
        }
        synchronized(vad) { vad.trimFront(trimSamples) }

        // 记录已提交文本尾部（回音去重用）
        committedTail = if (committed.length > 50) committed.substring(committed.length - 50) else committed
        interimActive = true
        Log.i(TAG, "interim: committed ${complete.size} sentence(s), trimmed ${"%.2f".format(trimSamples / 16000.0)}s")
        return true
    }

    // ---------- 生命周期 ----------

    private fun stopPipeline() {
        running = false
        isRunning = false
        capturer?.stop()
        capturer = null
        projection?.stop()
        projection = null
        overlay?.hide()
        overlay = null
        segmentQueue.clear()
        try {
            engine?.close()
        } catch (e: Exception) {
        }
        engine = null
        postStatus("已停止")
        // 完全退出前台服务（ACTION_STOP / 手动停止时）
        runCatching { stopSelf() }
    }

    private fun postStatus(text: String) {
        Log.i(TAG, text)
        handler.post {
            listener?.onStatus(text)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text.take(80)))
        }
    }

    override fun onDestroy() {
        stopPipeline()
        vad.silero?.close()
        super.onDestroy()
    }
}
