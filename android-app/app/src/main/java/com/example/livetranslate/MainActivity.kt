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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
        // 强制深色模式：系统组件统一深色（页面配色由主题引擎控制）
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        com.example.livetranslate.ui.ThemeManager.init(this)
        val content = buildUi()
        setContentView(content.view)
        applyThemeSystemBars()
        CaptureService.listener = this
        etAsrUrl.setText(store.asrUrl)
        refreshModelSpinner()

        // 页面进入动效：整体淡入 + 上滑（iOS decelerate）
        IOSMotion.enter(content.scroll, 420, 24f)

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

    override fun onDestroy() {
        CaptureService.listener = null
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- UI ----------

    /** 根容器：极光背景层 + 内容层 */
    class ContentRoot(val view: android.widget.FrameLayout, val scroll: ScrollView)

    private fun buildUi(): ContentRoot {
        // 液态玻璃氛围：深色极光背景 + 内容层
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(UIKit.BG)
        }
        if (com.example.livetranslate.ui.ThemeManager.current.showAurora) {
            frame.addView(com.example.livetranslate.ui.LiquidGlass.AuroraView(this),
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // ── 头部：大标题 + 状态指示 + 版本徽章 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        val vtuberTheme = com.example.livetranslate.ui.ThemeManager.current.name == "vtuber"
        header.addView(TextView(this).apply {
            text = "LiveTranslate"
            textSize = if (vtuberTheme) 30f else 27f
            if (vtuberTheme) {
                // VTuber 主题：手写体 + 紫色渐变艺术字
                setTypeface(android.graphics.Typeface.createFromAsset(assets, "fonts/Pacifico.ttf"))
                post {
                    paint.shader = android.graphics.LinearGradient(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        intArrayOf(UIKit.PURPLE, UIKit.LAVENDER, UIKit.PURPLE),
                        null, android.graphics.Shader.TileMode.CLAMP)
                    invalidate()
                }
            } else {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            setTextColor(UIKit.TEXT)
        })
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        if (vtuberTheme) {
            // 主立绘：紫发猫耳少女（VTuber 主题专属）
            header.addView(UIKit.roundAvatar(this, "img/heroine.webp", 46))
            header.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 1)
            })
        }
        statusDot = UIKit.statusDot(this, UIKit.TEXT_TERTIARY, 8)
        header.addView(statusDot)
        statusText = TextView(this).apply {
            text = "待机"
            textSize = 12f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(6), 0, dp(10), 0)
        }
        header.addView(statusText)
        header.addView(TextView(this).apply {
            text = "v0.11.0"
            textSize = 11f
            setTextColor(UIKit.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            background = com.example.livetranslate.ui.LiquidGlass.panel(this@MainActivity, 9,
                com.example.livetranslate.ui.ThemeManager.current.cardBase,
                com.example.livetranslate.ui.ThemeManager.current.cardSheen,
                com.example.livetranslate.ui.ThemeManager.current.cardEdge)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        // 主题切换按钮
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_gear)
            setColorFilter(UIKit.TEXT)
            alpha = 0.7f
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { showThemeDialog() }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "实时音频翻译 · 悬浮字幕"
            textSize = 13f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        // ── 主操作卡片（参考图步骤卡：Q 版头像 + 按钮） ──
        root.addView(UIKit.sectionLabel(this, "操作"))
        val actionCard = UIKit.card(this)
        val btnStartBtn = UIKit.iosButton(this, "① 开始翻译", UIKit.ButtonStyle.PRIMARY) {
            ensurePermissionsAndStart()
        }
        btnStart = if (vtuberTheme) stepRow(this, UIKit.roundAvatar(this, "img/chibi_phone.webp", 40), btnStartBtn)
                   else btnStartBtn
        actionCard.addView(btnStart)
        val btnTestBtn = UIKit.iosButton(this, "② 播放测试语音（英文）", UIKit.ButtonStyle.SECONDARY) {
            playTestAudio()
        }
        btnTestAudio = if (vtuberTheme) stepRow(this, UIKit.roundAvatar(this, "img/chibi_ear.webp", 40), btnTestBtn)
                       else btnTestBtn
        actionCard.addView(btnTestAudio, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        val accBtn = UIKit.iosButton(this, "③ 无障碍字幕条", UIKit.ButtonStyle.SECONDARY, heightDp = 44)
        val accRow = if (vtuberTheme) stepRow(this, UIKit.roundAvatar(this, "img/chibi_doc.webp", 40), accBtn)
                     else accBtn
        val btnAccessibility = accBtn
        btnAccessibility.setOnClickListener {
            if (com.example.livetranslate.pipeline.SubtitleAccessibilityService.isActive) {
                if (com.example.livetranslate.pipeline.SubtitleAccessibilityService.isVisible) {
                    com.example.livetranslate.pipeline.SubtitleAccessibilityService.hideSubtitleBar()
                    appendStatus("字幕条已隐藏（再次点击显示）")
                    btnAccessibility.text = "③ 无障碍字幕条"
                } else {
                    com.example.livetranslate.pipeline.SubtitleAccessibilityService.showSubtitleBar()
                    appendStatus("字幕条已显示 ✓（屏幕底部）")
                    btnAccessibility.text = "③ 无障碍字幕条（已显示）"
                }
            } else {
                appendStatus("请开启「LiveTranslate 字幕条」无障碍服务后返回")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        actionCard.addView(accRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        root.addView(actionCard)

        // ── 白猫吉祥物（VTuber 主题专属底部装饰） ──
        if (vtuberTheme) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, 0)
                addView(ImageView(this@MainActivity).apply {
                    val bmp = UIKit.loadAssetBitmap(this@MainActivity, "img/cat_mascot.webp")
                    if (bmp != null) setImageBitmap(bmp)
                    alpha = 0.55f
                    layoutParams = LinearLayout.LayoutParams(dp(110), dp(70))
                })
            })
        }

        // ── ASR 引擎卡片 ──
        root.addView(UIKit.sectionLabel(this, "ASR 引擎"))
        val asrCard = UIKit.card(this)
        asrCard.addView(UIKit.segmentedControl(
            this,
            listOf("远程 ASR", "本地 SenseVoice"),
            asrModeSelected
        ) { pos -> asrModeSelected = pos })
        asrCard.addView(TextView(this).apply {
            text = "服务器地址"
            textSize = 12f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(14), dp(2), dp(6))
        })
        etAsrUrl = EditText(this).apply {
            isSingleLine = true
            setTextColor(UIKit.TEXT)
            setHintTextColor(UIKit.TEXT_TERTIARY)
            textSize = 14f
            background = UIKit.roundedBg(this@MainActivity, UIKit.CARD_HI, 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        asrCard.addView(etAsrUrl)
        root.addView(asrCard)

        // ── 翻译模型卡片 ──
        root.addView(UIKit.sectionLabel(this, "翻译模型"))
        val modelCard = UIKit.card(this)
        modelSpinner = Spinner(this).apply {
            background = UIKit.roundedBg(this@MainActivity, UIKit.CARD_HI, 10)
            setPadding(dp(8), 0, dp(8), 0)
        }
        modelCard.addView(modelSpinner)
        val modelBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnEdit = UIKit.pillButton(this, "编辑", matchWidth = true) {
            showModelEditDialog(store.activeModelIndex, store.activeModel())
        }
        val btnAdd = UIKit.pillButton(this, "新增", matchWidth = true) {
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
            modelBtnRow.addView(b, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        modelCard.addView(modelBtnRow)
        root.addView(modelCard)

        // ── 工具卡片（参考图：线性图标 + 文字按钮） ──
        root.addView(UIKit.sectionLabel(this, "工具"))
        val toolCard = UIKit.card(this, padding = 10)
        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnModels = UIKit.pillButton(this, "", matchWidth = true) { showModelManagerDialog() }
        btnModels.text = UIKit.iconLabel(this, R.drawable.ic_folder, "模型管理")
        val btnBench = UIKit.pillButton(this, "", matchWidth = true) { runBenchmark() }
        btnBench.text = UIKit.iconLabel(this, R.drawable.ic_benchmark, "基准测试")
        toolRow.addView(btnModels, LinearLayout.LayoutParams(0, dp(38), 1f))
        toolRow.addView(btnBench, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            marginStart = dp(10)
        })
        toolCard.addView(toolRow)
        root.addView(toolCard)

        // ── 状态卡片 ──
        root.addView(UIKit.sectionLabel(this, "状态"))
        val statusCard = UIKit.card(this)
        asrView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(2), 0, dp(6))
        }
        tlView = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        statusView = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFF66BB66.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
        }
        statusCard.addView(asrView)
        statusCard.addView(tlView)
        statusCard.addView(statusView)
        root.addView(statusCard)

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        return ContentRoot(frame, scroll)
    }

    private fun refreshModelSpinner() {
        val models = store.models.ifEmpty { listOf(store.activeModel()) }
        val names = models.map { it.name + " · " + it.model + "  ▾" }
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
                this, if (running) UIKit.GREEN else UIKit.TEXT_TERTIARY, 4)
            statusText?.text = if (running) "运行中" else "待机"
            if (running) {
                IOSMotion.breathe(statusDot ?: return@runOnUiThread)
            } else {
                statusDot?.animate()?.cancel()
                statusDot?.alpha = 1f
            }
        }
    }

    // ---------- 模型编辑对话框（对应原项目 ModelEditDialog） ----------

    private fun showModelEditDialog(index: Int, model: ModelConfig) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        fun field(label: String, value: String, singleLine: Boolean = true): EditText {
            val et = EditText(this@MainActivity).apply {
                setText(value)
                isSingleLine = singleLine
                textSize = 14f
                setTextColor(UIKit.TEXT)
                background = UIKit.roundedBg(this@MainActivity, UIKit.CARD_HI, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            container.addView(TextView(this@MainActivity).apply {
                text = label; textSize = 12f; setTextColor(UIKit.TEXT_SECONDARY)
                setPadding(dp(2), dp(10), dp(2), dp(4))
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
            text = "API 协议"; textSize = 12f; setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(10), dp(2), dp(4))
        })
        val protocolSpinner = Spinner(this).apply {
            background = UIKit.roundedBg(this@MainActivity, UIKit.CARD_HI, 8)
        }
        protocolSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("OpenAI 兼容", "Anthropic (Claude)", "Gemini (Google)")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
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
        // 平台预设一键填充（避免手输域名拼写错误，如 apidmx.cn ↔ dmxapi.cn）
        container.addView(TextView(this).apply {
            text = "平台预设（点击自动填充）"
            textSize = 12f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(12), dp(2), dp(4))
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
            setTextColor(0xFFCC7733.toInt())
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
            text = "系统提示词（留空用默认）"; textSize = 12f; setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(10), dp(2), dp(4))
        })
        val etPrompt = EditText(this).apply {
            setText(model.systemPrompt ?: "")
            isSingleLine = false
            minLines = 4
            gravity = Gravity.TOP
            textSize = 14f
            setTextColor(UIKit.TEXT)
            background = UIKit.roundedBg(this@MainActivity, UIKit.CARD_HI, 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        container.addView(etPrompt)

        val scroll = ScrollView(this).apply { addView(container) }
        dialogBuilder()
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
        dialogBuilder()
            .setTitle("选择模型（共 ${sorted.size} 个）")
            .setItems(sorted.toTypedArray()) { _, which ->
                targetEditText.setText(sorted[which])
                appendStatus("已选择模型: ${sorted[which]}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 状态栏/导航栏按主题适配（浅色主题用浅色底+深色图标） */
    private fun applyThemeSystemBars() {
        val t = com.example.livetranslate.ui.ThemeManager.current
        val light = t.name == "light"
        window.statusBarColor = if (light) t.bg else android.graphics.Color.TRANSPARENT
        window.navigationBarColor = t.bg
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            val flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            c?.setSystemBarsAppearance(if (light) flag else 0, flag)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    /** 主题化对话框构建器（浅色主题用浅色对话框） */
    private fun dialogBuilder(): AlertDialog.Builder {
        val res = com.example.livetranslate.ui.ThemeManager.current.dialogThemeRes
        return if (res != 0) AlertDialog.Builder(this, res) else AlertDialog.Builder(this)
    }

    /** 主题切换对话框：三版风格可切换 */
    private fun showThemeDialog() {
        val t = com.example.livetranslate.ui.ThemeManager
        val names = t.themes.map { "${it.icon}  ${it.label}${if (it.name == t.current.name) "  ✓" else ""}" }
            .toTypedArray()
        val builder = if (t.current.dialogThemeRes != 0)
            AlertDialog.Builder(this, t.current.dialogThemeRes) else AlertDialog.Builder(this)
        builder.setTitle("🎨 主题")
            .setItems(names) { _, which ->
                val theme = t.themes[which]
                if (theme.name != t.current.name) {
                    t.set(this, theme.name)
                    appendStatus("主题已切换: ${theme.label}")
                    recreate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 参考图步骤卡：Q版头像 + 按钮横排 */
    private fun stepRow(context: Context, avatar: View, button: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar)
            addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
        }

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
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        container.addView(TextView(this).apply {
            text = "模型存放到应用内部存储（卸载即清）。本地 ASR 需 SenseVoice 模型。"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
        })

        val statusViews = mutableMapOf<String, TextView>()
        val progressViews = mutableMapOf<String, android.widget.ProgressBar>()

        fun addRow(file: ModelFile) {
            container.addView(TextView(this@MainActivity).apply {
                text = "${file.fileName} (${"%.0f".format(file.sizeBytes / 1048576.0)} MB)"
                textSize = 14f
                setTextColor(UIKit.TEXT)
            })
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val status = TextView(this@MainActivity).apply { textSize = 13f }
            val progress = android.widget.ProgressBar(this@MainActivity).apply {
                max = 100
                visibility = android.view.View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(UIKit.IOS_BLUE)
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

        dialogBuilder()
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
                dialogBuilder()
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
