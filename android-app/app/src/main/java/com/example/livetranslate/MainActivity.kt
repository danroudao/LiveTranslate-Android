package com.example.livetranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.livetranslate.benchmark.BenchmarkRunner
import com.example.livetranslate.model.ModelConfig
import com.example.livetranslate.model.ModelDownloader
import com.example.livetranslate.model.ModelFile
import com.example.livetranslate.model.ModelRepository
import com.example.livetranslate.model.SettingsStore
import com.example.livetranslate.net.LlmTranslator
import com.example.livetranslate.pipeline.CaptureService
import com.example.livetranslate.ui.IOSMotion
import com.example.livetranslate.ui.UIKit
import java.util.Locale

/**
 * LiveTranslate 主界面 —— Apple 官网 / Block Studio 风格 v0.9.0
 *
 * 设计语言：
 *  - Aurora & Glass：极光光球动态背景 + 毛玻璃 squircle 卡片
 *  - Bento Grid：模块化卡片网格，24px+ 宽敞间距
 *  - Big Type：大标题 700 字重 / 注释 #86868B / 字距微缩
 *  - 品牌蓝 #0071E3 主按钮 / 浅灰 #E5E5EA 次级按钮
 *  - 弹簧物理动效 cubic-bezier(0.25,1,0.5,1) + 卡片序列入场
 */
class MainActivity : AppCompatActivity(), CaptureService.Listener {

    private lateinit var statusView: TextView
    private lateinit var asrView: TextView
    private lateinit var tlView: TextView
    private lateinit var btnStart: View
    private lateinit var btnTestAudio: View
    private lateinit var etAsrUrl: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var store: SettingsStore
    private var tts: TextToSpeech? = null
    private var asrModeSelected = 0

    // 运行状态指示（头部呼吸圆点）
    private var statusDot: View? = null
    private var statusText: TextView? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        android.util.Log.i("MainActivity", "projection result: code=${result.resultCode}, data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCapture(result.resultCode, result.data!!)
        } else {
            appendStatus("❌ 用户拒绝了屏幕/音频捕获授权 (code=${result.resultCode})")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 保留强制深色模式（UI 手册约定：防止 DayNight 浅色系统下文字与背景融合）。
        // 本页面全部 View 显式浅色配色，不受影响；系统弹窗使用 LightDialogAlert 浅色主题。
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val content = buildUi()
        setContentView(content.view)
        applyLightSystemBars()
        CaptureService.listener = this
        etAsrUrl.setText(store.asrUrl)
        refreshModelSpinner()

        // 页面进入动效：卡片序列 fade-in-up（Apple 风格错峰入场）
        IOSMotion.staggerIn(content.enterViews, 520L, 90L)

        // 支持 adb 注入：am start --es DEEPSEEK_KEY sk-xxx
        intent.getStringExtra("DEEPSEEK_KEY")?.let { key ->
            val m = store.activeModel().copy(apiKey = key)
            store.setActiveModel(store.activeModelIndex, m)
            refreshModelSpinner()
            appendStatus("已注入 API Key 到「${m.name}」")
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                appendStatus("TTS 就绪（en-US）")
            } else {
                appendStatus("⚠️ TTS 不可用，但内置测试语音不受影响（点②直接播放）")
            }
        }
    }

    /** 浅色状态栏：深色图标 + 透明背景（Apple 浅色页面） */
    private fun applyLightSystemBars() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = UIKit.BG
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    override fun onDestroy() {
        CaptureService.listener = null
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- UI：Bento Grid ----------

    /** 根容器：极光背景层 + 内容层（记录入场动画目标） */
    class ContentRoot(root: FrameLayout) {
        val view: FrameLayout = root
        val enterViews = mutableListOf<View>()
    }

    private fun buildUi(): ContentRoot {
        val root = FrameLayout(this).apply {
            setBackgroundColor(UIKit.BG)
        }
        // 极光弥散背景（光球层）
        root.addView(UIKit.AuroraView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        val enterViews = mutableListOf<View>()
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        fun enter(v: View) = enterViews.also { it.add(v) }

        // ── 头部：Big Type 大标题 + 状态指示 + 版本徽章 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(2))
        }
        header.addView(UIKit.bigTitle(this, "LiveTranslate", 32f))
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        statusDot = UIKit.statusDot(this, UIKit.TEXT_TERTIARY, 9)
        header.addView(statusDot)
        statusText = TextView(this).apply {
            text = "待机"
            textSize = 12.5f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(7), 0, dp(12), 0)
        }
        header.addView(statusText)
        header.addView(TextView(this).apply {
            text = "v0.9.0"
            textSize = 11f
            setTextColor(UIKit.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            background = UIKit.roundedBg(this@MainActivity, 0xCCFFFFFF.toInt(), 10)
            elevation = dp(2).toFloat()
            setPadding(dp(11), dp(5), dp(11), dp(5))
        })
        enter(header)
        content.addView(header)

        val sub = UIKit.subtitle(this, "实时音频翻译 · 悬浮字幕", 14.5f)
        sub.setPadding(dp(4), dp(2), dp(4), dp(6))
        enter(sub)
        content.addView(sub)

        // ── Bento Row 1：主操作区（左大卡 + 右列双小卡） ──
        val bentoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        // 左大卡：开始翻译（品牌蓝）
        val startCard = UIKit.card(this, radiusDp = 26, padding = 22).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        btnStart = UIKit.iosButton(this, "开始翻译", UIKit.ButtonStyle.PRIMARY, heightDp = 56) {
            ensurePermissionsAndStart()
        }
        startCard.addView(btnStart)
        startCard.addView(UIKit.subtitle(this, "捕获系统音频 · 实时翻译", 12.5f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        bentoRow.addView(startCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f))

        // 右列：测试语音 + 字幕条
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f)
            setPadding(dp(12), 0, 0, 0)
        }
        val testCard = UIKit.card(this, radiusDp = 22, padding = 14).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        btnTestAudio = UIKit.iosButton(this, "测试语音", UIKit.ButtonStyle.SECONDARY, heightDp = 44, small = true) {
            playTestAudio()
        }
        testCard.addView(btnTestAudio)
        testCard.addView(UIKit.subtitle(this, "内置英文样例", 11.5f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        rightCol.addView(testCard)

        val accCard = UIKit.card(this, radiusDp = 22, padding = 14).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val btnAccessibility = UIKit.iosButton(this, "字幕条", UIKit.ButtonStyle.SECONDARY, heightDp = 44, small = true)
        btnAccessibility.setOnClickListener {
            if (com.example.livetranslate.pipeline.SubtitleAccessibilityService.isActive) {
                if (com.example.livetranslate.pipeline.SubtitleAccessibilityService.isVisible) {
                    com.example.livetranslate.pipeline.SubtitleAccessibilityService.hideSubtitleBar()
                    appendStatus("字幕条已隐藏（再次点击显示）")
                    btnAccessibility.text = "字幕条"
                } else {
                    com.example.livetranslate.pipeline.SubtitleAccessibilityService.showSubtitleBar()
                    appendStatus("字幕条已显示 ✓（屏幕底部）")
                    btnAccessibility.text = "字幕条 ✓"
                }
            } else {
                appendStatus("请开启「LiveTranslate 字幕条」无障碍服务后返回")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        accCard.addView(btnAccessibility)
        accCard.addView(UIKit.subtitle(this, "无障碍免悬浮窗权限", 11.5f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        rightCol.addView(accCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        bentoRow.addView(rightCol)
        enter(bentoRow)
        content.addView(bentoRow)

        // ── ASR 引擎卡（全宽） ──
        val asrCard = UIKit.card(this)
        enter(asrCard)
        asrCard.addView(UIKit.segmentedControl(
            this,
            listOf("远程 ASR", "本地 SenseVoice"),
            asrModeSelected
        ) { pos -> asrModeSelected = pos })
        asrCard.addView(UIKit.subtitle(this, "服务器地址", 12f).apply {
            setPadding(dp(2), dp(20), dp(2), dp(8))
        })
        etAsrUrl = UIKit.appleInput(this, "http://172.17.0.1:8765", store.asrUrl)
        asrCard.addView(etAsrUrl)
        content.addView(asrCard)

        // ── 翻译模型卡（全宽） ──
        val modelCard = UIKit.card(this)
        enter(modelCard)
        content.addView(modelCard)
        modelSpinner = Spinner(this).apply {
            background = UIKit.roundedBg(this@MainActivity, UIKit.INPUT_BG, 14)
            setPadding(dp(10), 0, dp(10), 0)
        }
        modelCard.addView(modelSpinner)
        val modelBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val btnEdit = UIKit.pillButton(this, "编辑", matchWidth = true) {
            showModelEditDialog(store.activeModelIndex, store.activeModel())
        }
        val btnAdd = UIKit.iosButton(this, "新增", UIKit.ButtonStyle.PRIMARY, heightDp = 40, small = true, matchWidth = true) {
            val m = store.activeModel()
            showModelEditDialog(-1, ModelConfig(
                name = "新模型", apiBase = m.apiBase, apiKey = m.apiKey,
                model = m.model, targetLanguage = m.targetLanguage,
            ))
        }
        val btnDel = UIKit.pillButton(this, "删除", matchWidth = true) {
            val idx = store.activeModelIndex
            store.removeModel(idx)
            refreshModelSpinner()
            appendStatus("已删除模型 #$idx")
        }
        for ((i, b) in listOf(btnEdit, btnAdd, btnDel).withIndex()) {
            modelBtnRow.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (i > 0) marginStart = dp(9)
            })
        }
        modelCard.addView(modelBtnRow)

        // ── Bento Row 2：工具卡 ──
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val modelsTool = UIKit.card(this, radiusDp = 22, padding = 16)
        modelsTool.gravity = Gravity.CENTER_VERTICAL
        modelsTool.addView(TextView(this).apply {
            text = "🗂  模型管理"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UIKit.TEXT)
        })
        modelsTool.addView(UIKit.subtitle(this, "模型下载 · 断点续传", 12f).apply {
            setPadding(0, dp(6), 0, dp(2))
        })
        modelsTool.setOnClickListener { showModelManagerDialog() }
        toolRow.addView(modelsTool, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val benchTool = UIKit.card(this, radiusDp = 22, padding = 16)
        benchTool.gravity = Gravity.CENTER_VERTICAL
        benchTool.addView(TextView(this).apply {
            text = "📊  基准测试"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UIKit.TEXT)
        })
        benchTool.addView(UIKit.subtitle(this, "延迟 · 成功率", 12f).apply {
            setPadding(0, dp(6), 0, dp(2))
        })
        benchTool.setOnClickListener { runBenchmark() }
        toolRow.addView(benchTool, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        enter(toolRow)
        content.addView(toolRow)

        // ── 状态卡（全宽） ──
        val statusCard = UIKit.card(this)
        enter(statusCard)
        content.addView(statusCard)
        asrView = TextView(this).apply {
            textSize = 14f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, dp(6))
        }
        tlView = TextView(this).apply {
            textSize = 16.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UIKit.TEXT)
            setPadding(0, 0, 0, dp(10))
        }
        statusView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF6E6E73.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
        }
        statusCard.addView(asrView)
        statusCard.addView(tlView)
        // 分割 hairline
        statusCard.addView(View(this).apply {
            background = UIKit.roundedBg(this@MainActivity, 0x0D000000.toInt(), 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        statusCard.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        return ContentRoot(root).also { it.enterViews.addAll(enterViews) }
    }

    private fun refreshModelSpinner() {
        val models = store.models.ifEmpty { listOf(store.activeModel()) }
        val names = models.map { it.name + " · " + it.model + "  ▾" }
        // 浅色 Apple 风格：自定义 adapter 强制深色文字（MODE_NIGHT_YES 下系统 item 是白字会与浅底融合）
        modelSpinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val tv = super.getView(pos, convertView, parent) as TextView
                tv.setTextColor(UIKit.TEXT)
                tv.textSize = 15f
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                return tv
            }
        }
        val idx = store.activeModelIndex.coerceIn(0, names.size - 1)
        modelSpinner.setSelection(idx)
    }

    private fun currentModelFromSpinner(): ModelConfig {
        val list = store.models.ifEmpty { listOf(store.activeModel()) }
        return list[modelSpinner.selectedItemPosition.coerceIn(0, list.size - 1)]
    }

    /** 服务运行状态 → 头部呼吸圆点 */
    private fun setRunning(running: Boolean) {
        runOnUiThread {
            statusDot?.background = UIKit.roundedBg(
                this, if (running) UIKit.GREEN else UIKit.TEXT_TERTIARY, 5)
            statusText?.text = if (running) "运行中" else "待机"
            if (running) {
                IOSMotion.breathe(statusDot ?: return@runOnUiThread)
            } else {
                statusDot?.animate()?.cancel()
                statusDot?.alpha = 1f
            }
        }
    }

    // ---------- 模型编辑对话框（浅色 Apple 主题） ----------

    private fun showModelEditDialog(index: Int, model: ModelConfig) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), 0)
        }
        fun field(label: String, value: String, singleLine: Boolean = true): EditText {
            val et = UIKit.appleInput(this@MainActivity, value = value, singleLine = singleLine)
            container.addView(TextView(this@MainActivity).apply {
                text = label; textSize = 12.5f; setTextColor(UIKit.TEXT_SECONDARY)
                setPadding(dp(2), dp(12), dp(2), dp(5))
            })
            container.addView(et)
            return et
        }
        val etName = field("名称", model.name)
        val etBase = field("API Base", model.apiBase)
        val etKey = field("API Key", model.apiKey)
        val btnFetchModels = UIKit.iosButton(this, "获取模型列表（自动拉取防输错）", UIKit.ButtonStyle.SECONDARY, heightDp = 42, small = true)
        container.addView(btnFetchModels)
        val etModel = field("模型名", model.model)
        // 协议选择
        container.addView(TextView(this).apply {
            text = "API 协议"; textSize = 12.5f; setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(12), dp(2), dp(5))
        })
        val protocolSpinner = Spinner(this).apply {
            background = UIKit.roundedBg(this@MainActivity, UIKit.INPUT_BG, 12)
        }
        protocolSpinner.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item,
            listOf("OpenAI 兼容", "Anthropic (Claude)", "Gemini (Google)")
        ) {
            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val tv = super.getView(pos, convertView, parent) as TextView
                tv.setTextColor(UIKit.TEXT)
                tv.textSize = 14f
                return tv
            }
        }
        protocolSpinner.setSelection(
            when (model.protocol) { "anthropic" -> 1; "gemini" -> 2; else -> 0 }
        )
        container.addView(protocolSpinner)

        // 拉取模型列表（按协议对应端点，防止手输模型名出错）
        btnFetchModels.setOnClickListener {
            val base = etBase.text.toString().trim().trimEnd('/')
            val key = etKey.text.toString().trim()
            if (base.isEmpty() || key.isEmpty()) {
                appendStatus("❌ 请先填写 API Base 和 API Key 再拉取模型列表")
                return@setOnClickListener
            }
            val proto = when (protocolSpinner.selectedItemPosition) {
                1 -> "anthropic"; 2 -> "gemini"; else -> "openai"
            }
            btnFetchModels.isEnabled = false
            btnFetchModels.alpha = 0.5f
            Thread {
                val models = fetchModels(base, key, proto)
                runOnUiThread {
                    btnFetchModels.isEnabled = true
                    btnFetchModels.alpha = 1f
                    if (models.isNullOrEmpty()) {
                        appendStatus("❌ 获取模型列表失败：检查 API Base / Key / 协议")
                    } else {
                        showModelPicker(models, etModel)
                    }
                }
            }.start()
        }
        val etTarget = field("目标语言 (zh/en/ja…)", model.targetLanguage)
        val etCtx = field("上下文轮数", model.contextTurns.toString())
        val etTimeout = field("超时(秒)", model.timeout.toString())
        // 平台预设一键填充
        container.addView(TextView(this).apply {
            text = "平台预设（点击自动填充）"
            textSize = 12.5f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(14), dp(2), dp(5))
        })
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = listOf(
            "DMX" to Triple("https://www.dmxapi.cn/v1", "openai", "qwen3.5-flash"),
            "DeepSeek" to Triple("https://api.deepseek.com/v1", "openai", "deepseek-chat"),
            "OpenAI" to Triple("https://api.openai.com/v1", "openai", "gpt-4o-mini"),
            "Claude" to Triple("https://api.anthropic.com", "anthropic", "claude-sonnet-4-5"),
            "Gemini" to Triple("https://generativelanguage.googleapis.com", "gemini", "gemini-2.5-flash"),
        )
        for ((name, cfg) in presets) {
            val presetBtn = UIKit.pillButton(this, name) {
                etBase.setText(cfg.first)
                etModel.setText(cfg.third)
                etTimeout.setText("60")
                protocolSpinner.setSelection(
                    when (cfg.second) { "anthropic" -> 1; "gemini" -> 2; else -> 0 }
                )
                etName.setText(name)
            }
            presetRow.addView(presetBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (presetRow.childCount > 0) marginStart = dp(6)
            })
        }
        container.addView(presetRow)
        container.addView(TextView(this).apply {
            text = "注意：DMX 域名是 dmxapi.cn（不是 apidmx.cn）"
            textSize = 11f
            setTextColor(0xFFC77D2E.toInt())
            setPadding(dp(2), dp(8), dp(2), 0)
        })

        val etExtra = field("附加语言（逗号分隔，如 en,ja）", model.extraLanguages.joinToString(","))
        val cbStreaming = androidx.appcompat.widget.AppCompatCheckBox(this).apply { text = "流式输出"; isChecked = model.streaming }
        val cbJson = androidx.appcompat.widget.AppCompatCheckBox(this).apply {
            text = "JSON 结构化输出（不支持时自动降级）"
            isChecked = model.jsonResponse
        }
        val cbNoThink = androidx.appcompat.widget.AppCompatCheckBox(this).apply { text = "禁用思考 (no_think)"; isChecked = model.noThink }
        val cbNoSystem = androidx.appcompat.widget.AppCompatCheckBox(this).apply { text = "无 system 角色 (Qwen-MT 等)"; isChecked = model.noSystemRole }
        container.addView(cbStreaming)
        container.addView(cbJson)
        container.addView(cbNoThink)
        container.addView(cbNoSystem)
        container.addView(TextView(this).apply {
            text = "系统提示词（留空用默认）"; textSize = 12.5f; setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(12), dp(2), dp(5))
        })
        val etPrompt = EditText(this).apply {
            setText(model.systemPrompt ?: "")
            isSingleLine = false
            minLines = 4
            gravity = Gravity.TOP
            textSize = 14f
            setTextColor(UIKit.TEXT)
            background = UIKit.roundedBg(this@MainActivity, UIKit.INPUT_BG, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(etPrompt)

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this, R.style.LightDialogAlert)
            .setTitle(if (index < 0) "新增模型" else "编辑模型")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val updated = ModelConfig(
                    name = etName.text.toString().ifBlank { "未命名" },
                    apiBase = etBase.text.toString().ifBlank { "https://api.deepseek.com/v1" },
                    apiKey = etKey.text.toString(),
                    model = etModel.text.toString().ifBlank { "deepseek-chat" },
                    targetLanguage = etTarget.text.toString().ifBlank { "zh" },
                    streaming = cbStreaming.isChecked,
                    jsonResponse = cbJson.isChecked,
                    noThink = cbNoThink.isChecked,
                    noSystemRole = cbNoSystem.isChecked,
                    contextTurns = etCtx.text.toString().toIntOrNull() ?: 0,
                    timeout = etTimeout.text.toString().toIntOrNull() ?: 30,
                    systemPrompt = etPrompt.text.toString().ifBlank { null },
                    extraLanguages = etExtra.text.toString()
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    protocol = when (protocolSpinner.selectedItemPosition) {
                        1 -> "anthropic"; 2 -> "gemini"; else -> "openai"
                    },
                )
                if (index < 0) store.addModel(updated) else store.setActiveModel(index, updated)
                refreshModelSpinner()
                appendStatus("模型已保存: ${updated.name} / ${updated.model}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 模型列表拉取（防止手输模型名出错） ----------

    private fun fetchModels(apiBase: String, apiKey: String, protocol: String): List<String>? {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            when (protocol) {
                "anthropic" -> {
                    val req = okhttp3.Request.Builder().url("$apiBase/v1/models")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return null
                        val json = org.json.JSONObject(resp.body!!.string())
                        json.optJSONArray("data")?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                arr.optJSONObject(it)?.optString("id", "")?.takeIf { s -> s.isNotEmpty() }
                            }
                        }
                    }
                }
                "gemini" -> {
                    val req = okhttp3.Request.Builder().url("$apiBase/v1beta/models?key=$apiKey").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return null
                        val json = org.json.JSONObject(resp.body!!.string())
                        json.optJSONArray("models")?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                arr.optJSONObject(it)?.optString("name", "")?.takeIf { s -> s.isNotEmpty() }?.removePrefix("models/")
                            }
                        }
                    }
                }
                else -> {
                    // OpenAI 兼容（DMX/DeepSeek/OpenAI 等）
                    val req = okhttp3.Request.Builder().url("$apiBase/models")
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return null
                        val json = org.json.JSONObject(resp.body!!.string())
                        json.optJSONArray("data")?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                arr.optJSONObject(it)?.optString("id", "")?.takeIf { s -> s.isNotEmpty() }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 模型选择对话框：列表点选填入模型名 */
    private fun showModelPicker(models: List<String>, targetEditText: EditText) {
        val sorted = models.sorted()
        AlertDialog.Builder(this, R.style.LightDialogAlert)
            .setTitle("选择模型（共 ${sorted.size} 个）")
            .setItems(sorted.toTypedArray()) { _, which ->
                targetEditText.setText(sorted[which])
                appendStatus("已选择模型: ${sorted[which]}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun appendStatus(s: String) {
        runOnUiThread {
            statusView.text = "${statusView.text}\n${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())} $s"
        }
    }

    // ---------- 权限 ----------

    private fun ensurePermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
            appendStatus("请授予通知 + 录音权限后再次点击")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            appendStatus("需要悬浮窗权限，正在跳转设置…")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        requestProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestProjection()
        } else {
            appendStatus("❌ 权限被拒绝")
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        appendStatus("请授权屏幕/音频捕获（选择“开始录制”或类似选项）")
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        android.util.Log.i("MainActivity", "startCapture begin, asrModePos=$asrModeSelected")
        try {
            store.asrUrl = etAsrUrl.text.toString()
            val model = currentModelFromSpinner()
            store.activeModelIndex = modelSpinner.selectedItemPosition
            // 本地模型目录：<externalFilesDir>/models/sense-voice/
            val localModelDir = java.io.File(filesDir, "models/sense-voice").absolutePath
            val asrMode = if (asrModeSelected == 1) "local" else "remote"
            android.util.Log.i("MainActivity", "startCapture: asrMode=$asrMode, modelDir=$localModelDir")
            if (asrMode == "local") {
                val ok = java.io.File(localModelDir, "model.int8.onnx").exists()
                if (!ok) {
                    appendStatus("❌ 本地模型缺失，请下载 SenseVoice 模型（约 239MB）")
                    // 自动弹出模型管理对话框引导下载
                    showModelManagerDialog()
                    return
                }
            }
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                putExtra(CaptureService.EXTRA_ASR_URL, store.asrUrl)
                putExtra(CaptureService.EXTRA_ASR_MODE, asrMode)
                putExtra(CaptureService.EXTRA_MODEL_DIR, localModelDir)
                putExtra(CaptureService.EXTRA_MODEL_JSON, model.toJson().toString())
            }
            android.util.Log.i("MainActivity", "startCapture: calling startForegroundService")
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            android.util.Log.i("MainActivity", "startCapture: service started")
            appendStatus("服务已启动（${model.name} / ${model.model}）…")
            setRunning(true)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "startCapture failed", e)
            appendStatus("❌ 启动失败: ${e.message}")
        }
    }

    // ---------- 模型管理（对应 ModelDownloadDialog） ----------

    private fun showModelManagerDialog() {
        val downloader = ModelDownloader(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(12))
        }
        container.addView(TextView(this).apply {
            text = "模型存放到应用内部存储（卸载即清）。本地 ASR 需 SenseVoice 模型。"
            textSize = 12f
            setTextColor(UIKit.TEXT_SECONDARY)
        })

        val statusViews = mutableMapOf<String, TextView>()
        val progressViews = mutableMapOf<String, android.widget.ProgressBar>()

        fun addRow(file: ModelFile) {
            container.addView(TextView(this@MainActivity).apply {
                text = "${file.fileName} (${"%.0f".format(file.sizeBytes / 1048576.0)} MB)"
                textSize = 14f
                setTextColor(UIKit.TEXT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val status = TextView(this@MainActivity).apply { textSize = 13f }
            val progress = android.widget.ProgressBar(this@MainActivity).apply {
                max = 100
                visibility = android.view.View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(UIKit.BRAND)
            }
            val btn = UIKit.pillButton(this@MainActivity, "下载")
            row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(progress, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            row.addView(btn)
            container.addView(row)
            statusViews[file.fileName] = status
            progressViews[file.fileName] = progress

            fun refresh() {
                when (val s = downloader.status(file)) {
                    is ModelDownloader.DownloadStatus.DONE -> {
                        status.text = "✓ 已下载"
                        status.setTextColor(UIKit.GREEN)
                        btn.text = "删除"
                    }
                    is ModelDownloader.DownloadStatus.PARTIAL -> {
                        status.text = "已下载 %.0f%%".format(s.bytes * 100.0 / file.sizeBytes)
                        btn.text = "继续下载"
                    }
                    else -> {
                        status.text = "未下载"
                        status.setTextColor(UIKit.TEXT_SECONDARY)
                        btn.text = "下载"
                    }
                }
            }
            refresh()

            btn.setOnClickListener {
                when (downloader.status(file)) {
                    is ModelDownloader.DownloadStatus.DONE -> {
                        downloader.delete(file)
                        refresh()
                    }
                    else -> {
                        btn.isEnabled = false
                        progress.visibility = android.view.View.VISIBLE
                        status.text = "下载中…"
                        Thread {
                            val ok = downloader.download(file) { p ->
                                runOnUiThread {
                                    progress.progress = (p * 100).toInt()
                                    status.text = "下载中 %.0f%%".format(p * 100)
                                }
                            }
                            runOnUiThread {
                                btn.isEnabled = true
                                progress.visibility = android.view.View.GONE
                                if (ok) {
                                    appendStatus("模型下载完成: ${file.fileName}")
                                } else {
                                    status.text = "下载失败（检查网络）"
                                }
                                refresh()
                            }
                        }.start()
                    }
                }
            }
        }

        for (f in ModelRepository.ALL) addRow(f)

        AlertDialog.Builder(this, R.style.LightDialogAlert)
            .setTitle("模型管理")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---------- 基准测试（对应 benchmark.py） ----------

    private fun runBenchmark() {
        val model = currentModelFromSpinner()
        if (model.apiKey.isEmpty()) {
            appendStatus("❌ 请先配置 API Key 再跑基准测试")
            return
        }
        appendStatus("基准测试开始（${model.name} / ${model.model}）…")
        Thread {
            val translator = LlmTranslator(
                apiBase = model.apiBase, apiKey = model.apiKey, model = model.model,
                targetLanguage = model.targetLanguage, streaming = model.streaming,
                systemPrompt = model.systemPrompt, noSystemRole = model.noSystemRole,
                noThink = model.noThink, jsonResponse = model.jsonResponse,
                contextTurns = model.contextTurns, timeoutSec = model.timeout.toLong(),
                protocol = model.protocol,
            )
            val runner = BenchmarkRunner(translator)
            val summary = runner.run()
            runner.shutdown()
            runOnUiThread {
                val sb = StringBuilder()
                sb.append("完成率 %.0f%%\n".format(summary.successRate * 100))
                sb.append("平均 %.0fms | 最小 %dms | 最大 %dms\n".format(summary.avgMs, summary.minMs, summary.maxMs))
                sb.append("---\n")
                for (r in summary.results) {
                    sb.append("%dms %s\n  → %s\n".format(r.latencyMs, r.sentence, r.translated.ifEmpty { "[失败]" }))
                }
                AlertDialog.Builder(this, R.style.LightDialogAlert)
                    .setTitle("基准测试结果（${model.model}）")
                    .setMessage(sb.toString())
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }.start()
    }

    // ---------- 测试音频 ----------

    private fun playTestAudio() {
        // 1. 内部存储 wav（开发环境 adb 部署的长音频）
        val candidates = listOf(
            java.io.File(filesDir, "test_long.wav").absolutePath,
            java.io.File(filesDir, "test_en.wav").absolutePath,
        )
        for (path in candidates) {
            if (java.io.File(path).exists()) {
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(path)
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnCompletionListener { it.release() }
                    mp.prepareAsync()
                    appendStatus("播放 $path")
                    return
                } catch (e: Exception) {
                    appendStatus("MediaPlayer 失败: ${e.message}")
                }
            }
        }
        // 2. assets 内置测试语音（开箱即用，不依赖 TTS / adb push）
        try {
            val afd = assets.openFd("test_en.wav")
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release() }
            mp.prepareAsync()
            appendStatus("播放内置测试语音（assets）")
            return
        } catch (e: Exception) {
            appendStatus("内置语音播放失败: ${e.message}")
        }
        val t = tts
        if (t != null) {
            t.speak(
                "Hello everyone, this is a live translation test. " +
                    "We are testing the audio capture pipeline on Android. " +
                    "Please check the subtitle overlay on top of the screen.",
                TextToSpeech.QUEUE_FLUSH, null, "test1"
            )
            appendStatus("TTS 播放中…")
        } else {
            appendStatus("TTS 不可用，先 adb push 一个 wav 到 /sdcard/test_en.wav")
        }
    }

    // ---------- Listener ----------

    override fun onStatus(text: String) = appendStatus(text)

    override fun onSegment(asrText: String, lang: String) {
        runOnUiThread {
            IOSMotion.crossfadeText(asrView, "[$lang] $asrText")
        }
    }

    override fun onTranslation(text: String) {
        runOnUiThread {
            IOSMotion.crossfadeText(tlView, "译文: $text")
        }
    }
}
