package com.example.mirareply

import android.content.Context
import java.io.File

/** 設定値（設定画面で変更できる） */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()

    /** 対象アプリのパッケージ名（MiraiMind の Android 版） */
    var targetPackage: String
        get() = str("targetPackage", DEFAULT_PACKAGE)
        set(v) = putStr("targetPackage", v.trim())

    // ---------- 生成エンジン ----------

    /** 返信を作るAI: local / claude / openai / gemini */
    var engine: String
        get() = str("engine", ENGINE_LOCAL)
        set(v) = putStr("engine", v)

    fun apiKey(provider: String): String = str("apiKey_$provider", "")
    fun setApiKey(provider: String, v: String) = putStr("apiKey_$provider", v.trim())

    fun apiModel(provider: String): String = str("apiModel_$provider", DEFAULT_API_MODELS[provider] ?: "").ifBlank { DEFAULT_API_MODELS[provider] ?: "" }
    fun setApiModel(provider: String, v: String) = putStr("apiModel_$provider", v.trim())

    /** API が失敗した時、端末内モデルがあればそちらで作る */
    var localFallback: Boolean
        get() = sp.getBoolean("localFallback", true)
        set(v) = sp.edit().putBoolean("localFallback", v).apply()

    // ---------- 返信の中身 ----------

    /** 返信の雰囲気・自分のキャラ設定など（プロンプトに入る） */
    var styleHint: String
        get() = str("styleHint", DEFAULT_STYLE)
        set(v) = putStr("styleHint", v)

    /** 自分（ユーザー）の呼び名・設定 */
    var userProfile: String
        get() = str("userProfile", "")
        set(v) = putStr("userProfile", v)

    /** 相手キャラの名前（空欄なら画面上部から自動で拾う） */
    var charNameOverride: String
        get() = str("charName", "")
        set(v) = putStr("charName", v.trim())

    /** AIに読ませる直近メッセージ数 */
    var contextCount: Int
        get() = sp.getInt("contextCount", 12)
        set(v) = sp.edit().putInt("contextCount", v.coerceIn(1, 40)).apply()

    // ---------- 自動送信・自動会話 ----------

    /** true なら候補を自動送信する（＝自動会話） */
    var autoSend: Boolean
        get() = sp.getBoolean("autoSend", false)
        set(v) = sp.edit().putBoolean("autoSend", v).apply()

    /** 自動送信までの待ち秒数（最小〜最大の間でランダム。人っぽい間をあける） */
    var autoSendDelaySec: Int
        get() = sp.getInt("autoSendDelaySec", 5)
        set(v) = sp.edit().putInt("autoSendDelaySec", v.coerceIn(1, 120)).apply()

    var autoSendDelayMaxSec: Int
        get() = sp.getInt("autoSendDelayMaxSec", 10).coerceAtLeast(autoSendDelaySec)
        set(v) = sp.edit().putInt("autoSendDelayMaxSec", v.coerceIn(1, 300)).apply()

    /** 自動送信でどの候補を選ぶか: true=3つからランダム / false=いつも1 */
    var autoPickRandom: Boolean
        get() = sp.getBoolean("autoPickRandom", true)
        set(v) = sp.edit().putBoolean("autoPickRandom", v).apply()

    /** 自動会話で送る最大回数（0=無制限）。到達したら自動送信をオフにする */
    var autoMaxTurns: Int
        get() = sp.getInt("autoMaxTurns", 30)
        set(v) = sp.edit().putInt("autoMaxTurns", v.coerceIn(0, 9999)).apply()

    /** 自動会話中は画面を消さない */
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()

    /** 自動会話する時間帯（例: 8-23。空欄=いつでも） */
    var autoHours: String
        get() = str("autoHours", "")
        set(v) = putStr("autoHours", v.trim())

    /** 今が自動会話してよい時間帯か */
    fun inAutoHours(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Boolean {
        val m = Regex("""^(\d{1,2})\s*[-〜~]\s*(\d{1,2})$""").find(autoHours) ?: return true
        val a = m.groupValues[1].toInt() % 24; val b = m.groupValues[2].toInt()
        return if (a < b) hour in a until b else hour >= a || hour < b % 24
    }

    /** キャラから返事が無い時、何分後に自分から話しかけるか（0=しない） */
    var nudgeMin: Int
        get() = sp.getInt("nudgeMin", 0)
        set(v) = sp.edit().putInt("nudgeMin", v.coerceIn(0, 1440)).apply()

    // ---------- 記憶・学習 ----------

    /** キャラ別に会話を記録し、古い会話を要約して覚えておく */
    var memoryEnabled: Boolean
        get() = sp.getBoolean("memoryEnabled", true)
        set(v) = sp.edit().putBoolean("memoryEnabled", v).apply()

    /** 自分が送った文を手本にして、候補の口調を自分に寄せる */
    var styleLearn: Boolean
        get() = sp.getBoolean("styleLearn", true)
        set(v) = sp.edit().putBoolean("styleLearn", v).apply()

    // ---------- API料金 ----------

    /** 100万トークンあたりの料金（ドル）「入力,出力」 */
    fun price(provider: String): Pair<Double, Double> {
        val s = str("price2_$provider", DEFAULT_PRICES[provider] ?: "0,0")
        val p = s.split(',', '/', ' ').mapNotNull { it.trim().toDoubleOrNull() }
        return (p.getOrNull(0) ?: 0.0) to (p.getOrNull(1) ?: 0.0)
    }
    fun priceText(provider: String) = str("price2_$provider", DEFAULT_PRICES[provider] ?: "0,0")
    fun setPrice(provider: String, v: String) = putStr("price2_$provider", v.trim())

    // ---------- 端末内モデル ----------

    /** モデルが無い時、Wi-Fi接続中なら自動でダウンロードする */
    var autoModelDownload: Boolean
        get() = sp.getBoolean("autoModelDownload", true)
        set(v) = sp.edit().putBoolean("autoModelDownload", v).apply()

    /** GPUを使う（失敗したら自動でCPUに切り替え） */
    var useGpu: Boolean
        get() = sp.getBoolean("useGpu", true)
        set(v) = sp.edit().putBoolean("useGpu", v).apply()

    /** 使うモデル（ModelInfo.id） */
    var modelId: String
        get() = str("modelId", MODELS.first().id)
        set(v) = putStr("modelId", v)

    val model: ModelInfo get() = MODELS.firstOrNull { it.id == modelId } ?: MODELS.first()

    fun modelFile(m: ModelInfo = model): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, m.fileName)

    fun modelReady(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() >= model.sizeBytes * 0.98
    }

    // ---------- 読み取りの手動指定 ----------

    /** 自動判定がうまくいかない時に手動指定する View ID（例: com.immomo.miraimind:id/et_input） */
    var inputViewId: String
        get() = str("inputViewId", "")
        set(v) = putStr("inputViewId", v.trim())

    var sendButtonId: String
        get() = str("sendButtonId", "")
        set(v) = putStr("sendButtonId", v.trim())

    /** メッセージ一覧の View ID（任意） */
    var listViewId: String
        get() = str("listViewId", "")
        set(v) = putStr("listViewId", v.trim())

    /** 読み取りで無視する文字列（改行区切り。ボタン名などが吹き出しとして拾われる時に使う） */
    var ignoreTexts: String
        get() = str("ignoreTexts", "")
        set(v) = putStr("ignoreTexts", v)

    fun ignoreSet(): Set<String> = ignoreTexts.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        const val DEFAULT_PACKAGE = "com.immomo.miraimind"
        const val DEFAULT_STYLE = "親しみやすく自然なタメ口。1〜2文くらいの短めの返信。"

        const val ENGINE_LOCAL = "local"
        const val ENGINE_CLAUDE = "claude"
        const val ENGINE_OPENAI = "openai"
        const val ENGINE_GEMINI = "gemini"

        val ENGINE_LABELS = linkedMapOf(
            ENGINE_LOCAL to "端末内AI（Gemma・無料・オフライン）",
            ENGINE_CLAUDE to "Claude API（高品質・おすすめ）",
            ENGINE_OPENAI to "OpenAI API",
            ENGINE_GEMINI to "Gemini API",
        )

        val DEFAULT_API_MODELS = mapOf(
            ENGINE_CLAUDE to "claude-haiku-5-5",
            ENGINE_OPENAI to "gpt-5-mini",
            ENGINE_GEMINI to "gemini-2.5-flash",
        )

        /** 100万トークンあたりの目安料金（ドル）。設定で変更可 */
        val DEFAULT_PRICES = mapOf(
            ENGINE_CLAUDE to "0.1,0.5",   // Claude Haiku 5.5（10万トークン以下）
            ENGINE_OPENAI to "0.25,2",
            ENGINE_GEMINI to "0.3,2.5",
        )

        val MODELS = listOf(
            ModelInfo(
                id = "gemma4-e2b",
                label = "Gemma 4 E2B（推奨・約2.6GB・RAM 8GB以上）",
                fileName = "gemma-4-E2B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
                sizeBytes = 2_588_147_712L,
            ),
            ModelInfo(
                id = "gemma3-1b",
                label = "Gemma 3 1B（軽量・約0.6GB・RAM 6GB程度でも可、日本語は弱め、要Hugging Faceログイン）",
                fileName = "gemma3-1b-it-int4.litertlm",
                url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm?download=true",
                sizeBytes = 584_417_280L,
                needsLogin = true,
                pageUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            ),
        )
    }
}

data class ModelInfo(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val needsLogin: Boolean = false,
    val pageUrl: String = "",
)
