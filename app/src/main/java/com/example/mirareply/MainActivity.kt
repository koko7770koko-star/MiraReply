package com.example.mirareply

import android.app.Activity
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 設定画面：モデルの準備、ユーザー補助の有効化、各種設定 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var serviceStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelGroup: RadioGroup
    private lateinit var engineGroup: RadioGroup
    private val keyEts = HashMap<String, EditText>()
    private val modelEts = HashMap<String, EditText>()
    private lateinit var fallbackSw: Switch
    private lateinit var charEt: EditText
    private lateinit var profileEt: EditText
    private lateinit var autoSendSw: Switch
    private lateinit var delayEt: EditText
    private lateinit var delayMaxEt: EditText
    private lateinit var maxTurnsEt: EditText
    private lateinit var randomPickSw: Switch
    private lateinit var screenOnSw: Switch
    private lateinit var ignoreEt: EditText
    private lateinit var styleEt: EditText
    private lateinit var ctxEt: EditText
    private lateinit var gpuSw: Switch
    private lateinit var pkgEt: EditText
    private lateinit var inputIdEt: EditText
    private lateinit var sendIdEt: EditText
    private lateinit var listIdEt: EditText

    private var downloadId = -1L
    private var copying = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        downloadId = getSharedPreferences("dl", MODE_PRIVATE).getLong("id", -1L)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })

        col.addView(header("ミラ返信アシスト", 22f))
        col.addView(note("MiraiMindのチャット画面に返信候補を3つ表示します。自動会話もできます。"))

        // ⓪ 返信を作るAI
        col.addView(header("① 返信を作るAIを選ぶ"))
        engineGroup = RadioGroup(this)
        val engines = Prefs.ENGINE_LABELS.keys.toList()
        engines.forEachIndexed { i, e ->
            engineGroup.addView(RadioButton(this).apply {
                id = 2000 + i; text = Prefs.ENGINE_LABELS[e]; isChecked = prefs.engine == e
            })
        }
        col.addView(engineGroup)
        col.addView(note("API を使うと返信がかなり自然になります。直近の会話（設定した件数）だけが各社のAPIに送られます。端末内AIは通信しません。"))
        for (e in engines.drop(1)) {
            col.addView(header(Prefs.ENGINE_LABELS[e]!!.substringBefore("（"), 15f))
            keyEts[e] = field(col, "APIキー", prefs.apiKey(e), secret = true)
            modelEts[e] = field(col, "モデル名（空欄=${Prefs.DEFAULT_API_MODELS[e]}）", prefs.apiModel(e))
        }
        fallbackSw = Switch(this).apply { text = "APIが失敗したら端末内AIで作る（モデルがある時だけ）"; isChecked = prefs.localFallback }
        col.addView(fallbackSw)

        // ① モデル
        col.addView(header("端末内AIのモデル（端末内AIを使う時だけ）", 15f))
        modelGroup = RadioGroup(this)
        Prefs.MODELS.forEachIndexed { i, m ->
            modelGroup.addView(RadioButton(this).apply {
                id = 1000 + i; text = m.label; isChecked = prefs.modelId == m.id
            })
        }
        modelGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.modelId = Prefs.MODELS[checkedId - 1000].id
            refresh()
            ReplyAccessibilityService.instance?.reloadSettings(reloadModel = true)
        }
        col.addView(modelGroup)
        modelStatus = note("")
        col.addView(modelStatus)
        col.addView(note("Gemma 4 E2B はアプリに内蔵済み。初回に自動で準備するのでダウンロード不要。"))
        col.addView(button("モデルをダウンロード（Gemma 3 1B など内蔵以外の時）") { startDownload() })
        col.addView(button("ダウンロード済みのファイルを選ぶ（.litertlm）") { pickFile() })

        // ② ユーザー補助
        col.addView(header("② ユーザー補助（アクセシビリティ）をオンにする"))
        serviceStatus = note("")
        col.addView(serviceStatus)
        col.addView(button("ユーザー補助の設定を開く") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(note("「制限付き設定」と出てオンにできない場合：下のボタン → 右上の︙ →「制限付き設定を許可」を押してから、もう一度オンにしてください。"))
        col.addView(button("このアプリの情報を開く") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        // ③ 設定
        col.addView(header("③ 返信の設定"))
        styleEt = field(col, "返信の雰囲気（AIへの指示）", prefs.styleHint, multi = true)
        profileEt = field(col, "自分の設定（呼ばれ方・一人称・立場など。任意）", prefs.userProfile, multi = true)
        charEt = field(col, "相手キャラの名前（空欄=画面上部から自動）", prefs.charNameOverride)
        ctxEt = field(col, "AIに読ませる直近のメッセージ数", prefs.contextCount.toString(), number = true)
        gpuSw = Switch(this).apply { text = "端末内AIでGPUを使う（速い。動かない時はオフ）"; isChecked = prefs.useGpu }
        col.addView(gpuSw)

        // ④ 自動会話
        col.addView(header("④ 自動会話"))
        col.addView(note("オンにすると、キャラの返事が届くたびに候補を作り、少し待ってから自動で送ります。チャット画面の「返」→「自動会話」でも切り替えられます（オン中はボタンがオレンジの「自」になります）。"))
        autoSendSw = Switch(this).apply { text = "自動会話をオンにする"; isChecked = prefs.autoSend }
        col.addView(autoSendSw)
        delayEt = field(col, "送るまでの待ち時間・最小（秒）", prefs.autoSendDelaySec.toString(), number = true)
        delayMaxEt = field(col, "送るまでの待ち時間・最大（秒）", prefs.autoSendDelayMaxSec.toString(), number = true)
        maxTurnsEt = field(col, "自動で送る最大回数（0=無制限）", prefs.autoMaxTurns.toString(), number = true)
        randomPickSw = Switch(this).apply { text = "3つの候補からランダムに選ぶ（オフ=いつも1）"; isChecked = prefs.autoPickRandom }
        col.addView(randomPickSw)
        screenOnSw = Switch(this).apply { text = "自動会話中は画面を消さない"; isChecked = prefs.keepScreenOn }
        col.addView(screenOnSw)

        col.addView(header("詳細設定（うまく読み取れない時だけ）", 15f))
        pkgEt = field(col, "対象アプリのパッケージ名", prefs.targetPackage)
        listIdEt = field(col, "メッセージ一覧のView ID（空欄=自動）", prefs.listViewId)
        inputIdEt = field(col, "入力欄のView ID（空欄=自動）", prefs.inputViewId)
        sendIdEt = field(col, "送信ボタンのView ID（空欄=自動）", prefs.sendButtonId)
        ignoreEt = field(col, "読み取りで無視する文字（1行に1つ。ボタン名などが拾われる時）", prefs.ignoreTexts, multi = true)
        col.addView(note("チャット画面で丸い「返」ボタン →「画面構造」を押すと画面の構造が保存されます。下のボタンで共有して中身を見ると、正しいIDがわかります。"))
        col.addView(button("保存した画面構造を共有") { shareDump() })

        col.addView(button("設定を保存") { save() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        pollDownload()
        installBundled()
    }

    /** APK 内蔵モデルを展開（初回だけ。数十秒〜数分） */
    private fun installBundled() {
        if (copying || prefs.modelId != BundledModel.MODEL_ID || prefs.modelReady()) return
        if (!BundledModel.isBundled(this)) return
        copying = true
        modelStatus.text = "内蔵モデルを準備中…"
        Thread {
            try {
                BundledModel.ensureInstalled(applicationContext) { done, total ->
                    runOnUiThread { modelStatus.text = "内蔵モデルを準備中… ${done * 100 / total}%（初回だけ）" }
                }
                runOnUiThread {
                    copying = false; refresh()
                    ReplyAccessibilityService.instance?.reloadSettings(reloadModel = true)
                }
            } catch (t: Throwable) {
                runOnUiThread { copying = false; modelStatus.text = t.message ?: "内蔵モデルの準備に失敗" }
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        serviceStatus.text = if (enabled) "状態：オン ✅" else "状態：オフ（「ミラ返信アシスト」をオンにしてください）"
        serviceStatus.setTextColor(if (enabled) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        if (!copying) {
            val f = prefs.modelFile()
            val m = prefs.model
            modelStatus.text = when {
                f.exists() && f.length() >= m.sizeBytes * 0.98 ->
                    "モデル：準備済み ✅（${mb(f.length())}${if (m.id == BundledModel.MODEL_ID && BundledModel.isBundled(this)) "・アプリに内蔵" else ""}）"
                f.exists() -> "モデル：途中（${mb(f.length())} / ${mb(m.sizeBytes)}）"
                else -> "モデル：未ダウンロード"
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ReplyAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------- モデルのダウンロード ----------

    private fun startDownload() {
        val m = prefs.model
        if (m.needsLogin) {
            // 利用規約への同意が必要なモデルはブラウザで落としてもらう
            toast("Hugging Faceにログインして規約に同意後ダウンロードし、「ファイルを選ぶ」で指定してください")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.pageUrl)))
            return
        }
        val f = prefs.modelFile()
        if (f.exists()) f.delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("AIモデル ${m.fileName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, null, m.fileName)
            .setAllowedOverMetered(true)
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(req)
        getSharedPreferences("dl", MODE_PRIVATE).edit().putLong("id", downloadId).apply()
        toast("ダウンロードを開始しました")
        pollDownload()
    }

    private fun pollDownload() {
        if (downloadId < 0) return
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (!c.moveToFirst()) return
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadId = -1
                    getSharedPreferences("dl", MODE_PRIVATE).edit().remove("id").apply()
                    refresh()
                    ReplyAccessibilityService.instance?.reloadSettings(reloadModel = true)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadId = -1
                    modelStatus.text = "ダウンロード失敗。もう一度お試しください"
                    return
                }
                else -> {
                    val pct = if (total > 0) done * 100 / total else 0
                    modelStatus.text = "ダウンロード中… $pct%（${mb(done)} / ${mb(if (total > 0) total else prefs.model.sizeBytes)}）"
                }
            }
        }
        main.postDelayed({ pollDownload() }, 1000)
    }

    // ---------- ファイルから選ぶ ----------

    @Deprecated("Activity#onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { copyModel(it) }
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_PICK)
    }

    private fun copyModel(uri: Uri) {
        val dest = prefs.modelFile()
        copying = true
        Thread {
            try {
                val tmp = File(dest.path + ".part")
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L; var lastUi = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); total += n
                            if (total - lastUi > (50L shl 20)) {
                                lastUi = total
                                val t = total
                                runOnUiThread { modelStatus.text = "コピー中… ${mb(t)}" }
                            }
                        }
                    }
                }
                dest.delete(); tmp.renameTo(dest)
                runOnUiThread {
                    copying = false; refresh()
                    ReplyAccessibilityService.instance?.reloadSettings(reloadModel = true)
                }
            } catch (t: Throwable) {
                runOnUiThread { copying = false; modelStatus.text = "コピー失敗: ${t.message}" }
            }
        }.start()
    }

    // ---------- 設定 ----------

    private fun save() {
        val engines = Prefs.ENGINE_LABELS.keys.toList()
        val newEngine = engines.getOrElse(engineGroup.checkedRadioButtonId - 2000) { Prefs.ENGINE_LOCAL }
        val gpuChanged = prefs.useGpu != gpuSw.isChecked
        val engineChanged = prefs.engine != newEngine
        prefs.engine = newEngine
        for ((e, et) in keyEts) prefs.setApiKey(e, et.text.toString())
        for ((e, et) in modelEts) prefs.setApiModel(e, et.text.toString())
        prefs.localFallback = fallbackSw.isChecked
        prefs.styleHint = styleEt.text.toString()
        prefs.userProfile = profileEt.text.toString()
        prefs.charNameOverride = charEt.text.toString()
        prefs.contextCount = ctxEt.text.toString().toIntOrNull() ?: 12
        prefs.autoSend = autoSendSw.isChecked
        prefs.autoSendDelaySec = delayEt.text.toString().toIntOrNull() ?: 5
        prefs.autoSendDelayMaxSec = delayMaxEt.text.toString().toIntOrNull() ?: 10
        prefs.autoMaxTurns = maxTurnsEt.text.toString().toIntOrNull() ?: 30
        prefs.autoPickRandom = randomPickSw.isChecked
        prefs.keepScreenOn = screenOnSw.isChecked
        prefs.ignoreTexts = ignoreEt.text.toString()
        prefs.useGpu = gpuSw.isChecked
        prefs.targetPackage = pkgEt.text.toString().ifBlank { Prefs.DEFAULT_PACKAGE }
        prefs.listViewId = listIdEt.text.toString()
        prefs.inputViewId = inputIdEt.text.toString()
        prefs.sendButtonId = sendIdEt.text.toString()
        ReplyAccessibilityService.instance?.reloadSettings(reloadModel = gpuChanged || engineChanged)
        if (newEngine != Prefs.ENGINE_LOCAL && prefs.apiKey(newEngine).isBlank()) toast("APIキーが空です")
        else toast("保存しました")
    }

    private fun shareDump() {
        val f = File(filesDir, ReplyAccessibilityService.DUMP_FILE)
        if (!f.exists()) { toast("まだ保存されていません"); return }
        // テキストとして共有（メモアプリやメールに送れる）
        val text = f.readText().take(90_000)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "画面構造を共有"))
    }

    // ---------- UI部品 ----------

    private fun header(s: String, size: Float = 17f) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.parseColor("#4527A0")); setPadding(0, dp(20), 0, dp(6))
        paint.isFakeBoldText = true
    }

    private fun note(s: String) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Color.parseColor("#555555")); setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun field(parent: LinearLayout, label: String, value: String, number: Boolean = false, multi: Boolean = false, secret: Boolean = false): EditText {
        parent.addView(note(label))
        return EditText(this).apply {
            setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                number -> InputType.TYPE_CLASS_NUMBER
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multi) { minLines = 2; maxLines = 5 }
            if (secret) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            parent.addView(this)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun mb(b: Long) = "%.0fMB".format(b / 1024.0 / 1024.0)

    companion object { private const val REQ_PICK = 1 }
}
