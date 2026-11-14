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

    private lateinit var hoursEt: EditText
    private lateinit var nudgeEt: EditText
    private lateinit var memorySw: Switch
    private lateinit var styleSw: Switch
    private lateinit var autoDlSw: Switch
    private val priceEts = HashMap<String, EditText>()
    private lateinit var usageText: TextView
    private lateinit var charsBox: LinearLayout
    private lateinit var store: CharStore
    private lateinit var usage: UsageStore

    private var downloadId = -1L
    private var copying = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = CharStore(this)
        usage = UsageStore(this)
        downloadId = ModelDownloader.currentId(this)

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
        col.addView(note("Wi-Fi につながっていれば、初回に自動でダウンロードします（約2.6GB）。終わるまではAPIキーがあればAPIで動きます。"))
        autoDlSw = Switch(this).apply { text = "Wi-Fi接続時にモデルを自動ダウンロード"; isChecked = prefs.autoModelDownload }
        col.addView(autoDlSw)
        col.addView(button("今すぐダウンロード（モバイル回線でも）") { startDownload() })
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
        hoursEt = field(col, "自動会話する時間帯（例: 8-23。空欄=いつでも）", prefs.autoHours)
        nudgeEt = field(col, "返事が無い時、何分後に自分から話しかけるか（0=しない）", prefs.nudgeMin.toString(), number = true)
        col.addView(note("通知を下ろしたクイック設定に「自動会話」タイルを追加すると、そこからもオン/オフできます（パネルの鉛筆マーク → タイルを追加）。"))

        // ⑤ 記憶と学習
        col.addView(header("⑤ 記憶と口調の学習"))
        memorySw = Switch(this).apply { text = "キャラ別に会話を記録して覚えておく"; isChecked = prefs.memoryEnabled }
        col.addView(memorySw)
        styleSw = Switch(this).apply { text = "自分の書き方を学習して候補を寄せる"; isChecked = prefs.styleLearn }
        col.addView(styleSw)
        col.addView(note("会話はこのスマホの中に保存されます。古い会話は${40}件たまるごとにAIが要約して「記憶」にします（APIか端末AIを使用）。記憶は下で直せます。"))
        charsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(charsBox)

        // ⑥ API料金
        col.addView(header("⑥ APIの使用量と料金"))
        usageText = note("")
        col.addView(usageText)
        col.addView(note("料金の目安（100万トークンあたりのドル「入力,出力」）。使うモデルに合わせて直せます。"))
        for (e in listOf(Prefs.ENGINE_CLAUDE, Prefs.ENGINE_OPENAI, Prefs.ENGINE_GEMINI)) {
            priceEts[e] = field(col, Prefs.ENGINE_LABELS[e]!!.substringBefore("（"), prefs.priceText(e))
        }
        col.addView(button("今月の使用量をリセット") { usage.resetMonth(); refreshUsage() })

        col.addView(header("詳細設定（うまく読み取れない時だけ）", 15f))
        pkgEt = field(col, "対象アプリのパッケージ名", prefs.targetPackage)
        listIdEt = field(col, "メッセージ一覧のView ID（空欄=自動）", prefs.listViewId)
        inputIdEt = field(col, "入力欄のView ID（空欄=自動）", prefs.inputViewId)
        sendIdEt = field(col, "送信ボタンのView ID（空欄=自動）", prefs.sendButtonId)
        ignoreEt = field(col, "読み取りで無視する文字（1行に1つ。ボタン名などが拾われる時）", prefs.ignoreTexts, multi = true)
        col.addView(note("チャット画面で丸い「返」ボタン →「画面構造」を押すと画面の構造が保存されます。下のボタンで共有して中身を見ると、正しいIDがわかります。"))
        col.addView(button("保存した画面構造を共有") { shareDump() })
        col.addView(button("動作ログを共有（自動会話の不具合調査用）") {
            val text = DebugLog.saved(this).ifBlank { "ログはまだありません" }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text.takeLast(90_000))
            }, "動作ログを共有"))
        })

        col.addView(button("設定を保存") { save() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (ModelDownloader.autoStartIfNeeded(this)) downloadId = ModelDownloader.currentId(this)
        pollDownload()
        refreshChars()
        refreshUsage()
    }

    private fun refreshUsage() { usageText.text = usage.report() }

    /** キャラ一覧（記憶の編集・ログ共有・削除） */
    private fun refreshChars() {
        charsBox.removeAllViews()
        val list = store.list()
        if (list.isEmpty()) { charsBox.addView(note("まだ記録はありません")); return }
        for (m in list) {
            charsBox.addView(header("${m.name.ifBlank { "（名前不明）" }}　ログ${m.log.size}件", 15f))
            val et = field(charsBox, "記憶（AIに渡す要約。自由に書き換え可）", m.summary, multi = true)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun small(t: String, f: () -> Unit) = Button(this).apply {
                text = t; isAllCaps = false; setOnClickListener { f() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(small("記憶を保存") {
                val mem = store.load(m.name); mem.summary = et.text.toString().trim(); store.save(mem); toast("保存しました")
            })
            row.addView(small("ログ共有") {
                val text = store.exportText(store.load(m.name)).takeLast(150_000)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "${m.name} 会話ログ"); putExtra(Intent.EXTRA_TEXT, text)
                }, "会話ログを共有"))
            })
            row.addView(small("削除") {
                android.app.AlertDialog.Builder(this)
                    .setMessage("「${m.name.ifBlank { "名前不明" }}」の会話ログと記憶を消します。元に戻せません。")
                    .setPositiveButton("消す") { _, _ -> store.delete(m.name); refreshChars() }
                    .setNegativeButton("やめる", null).show()
            })
            charsBox.addView(row)
        }
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
                    "モデル：準備済み ✅（${mb(f.length())}）"
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
        ModelDownloader.start(this)
        downloadId = ModelDownloader.currentId(this)
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
                    ModelDownloader.clear(this)
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
        prefs.autoHours = hoursEt.text.toString()
        prefs.nudgeMin = nudgeEt.text.toString().toIntOrNull() ?: 0
        prefs.memoryEnabled = memorySw.isChecked
        prefs.styleLearn = styleSw.isChecked
        prefs.autoModelDownload = autoDlSw.isChecked
        for ((e, et) in priceEts) prefs.setPrice(e, et.text.toString())
        AutoChatTileService.refresh(this)
        refreshUsage()
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
