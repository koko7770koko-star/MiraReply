package com.example.mirareply

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * MiraiMind の画面を見張り、キャラクターの新しいメッセージが届いたら
 * 返信候補を3つ作ってオーバーレイに出す。自動会話がオンなら少し待って送信する。
 */
class ReplyAccessibilityService : AccessibilityService(), OverlayPanel.Listener {

    private lateinit var prefs: Prefs
    private lateinit var reader: ChatReader
    private lateinit var overlay: OverlayPanel
    private lateinit var llm: LlmReplyEngine
    private lateinit var api: ApiReplyEngine

    private val main = Handler(Looper.getMainLooper())
    // 生成は1本のスレッドで順番に動かす
    private val genDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var overlayShown = false
    private var lastHandledKey: String? = null      // 返信案を作り終えた最新メッセージ
    private var generatingKey: String? = null
    private var genJob: Job? = null
    private var genSeq = 0
    private var lastRequest: ReplyRequest? = null
    private var candidates: List<String> = emptyList()
    private var countdown: Runnable? = null

    // キャラの返事が「書き終わった」かを見るため、同じ文が一定時間変わらないのを待つ
    private var seenKey: String? = null
    private var seenAt = 0L

    // 自動会話
    private var autoTurns = 0
    private var autoErrors = 0

    private val scanRunnable = Runnable { scan(force = false) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        reader = ChatReader(prefs)
        llm = LlmReplyEngine(applicationContext)
        api = ApiReplyEngine(prefs)
        overlay = OverlayPanel(this, this)
        instance = this
        applyAutoUi()
        overlay.setStatus("待機中")
        prepareEngine()
    }

    private val usingApi get() = prefs.engine != Prefs.ENGINE_LOCAL
    private val engineLabel get() = when (prefs.engine) {
        Prefs.ENGINE_CLAUDE -> "Claude"
        Prefs.ENGINE_OPENAI -> "OpenAI"
        Prefs.ENGINE_GEMINI -> "Gemini"
        else -> "端末AI"
    }

    /** 端末内AIを使う時だけモデルを読み込む（API利用時はメモリを使わない） */
    private fun prepareEngine() {
        if (usingApi) {
            scope.launch { withContext(genDispatcher) { llm.close() } }
            overlay.setStatus("準備OK（$engineLabel）")
            scan(force = false)
            return
        }
        scope.launch {
            overlay.setStatus("AIモデルを読み込み中…")
            try {
                withContext(genDispatcher) {
                    installBundledIfNeeded()
                    llm.ensureLoaded(prefs.modelFile(), prefs.useGpu)
                }
                overlay.setStatus("準備OK（端末AI・${llm.backendName}）")
                scan(force = false)
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                overlay.setStatus(t.message ?: "モデル読み込みエラー")
            }
        }
    }

    /** APK 内蔵のモデルを初回だけ展開する（生成スレッドから呼ぶ） */
    private fun installBundledIfNeeded() {
        if (prefs.modelId != BundledModel.MODEL_ID || prefs.modelReady()) return
        BundledModel.ensureInstalled(applicationContext) { done, total ->
            main.post { overlay.setStatus("内蔵AIモデルを準備中… ${done * 100 / total}%（初回だけ）") }
        }
    }

    /** 設定画面で設定を変えた時に呼ばれる */
    fun reloadSettings(reloadModel: Boolean) {
        main.post {
            applyAutoUi()
            if (!reloadModel) return@post
            scope.launch {
                withContext(genDispatcher) { llm.close() }
                prepareEngine()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 自分のオーバーレイ由来

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val active = rootInActiveWindow?.packageName?.toString()
            if (active == prefs.targetPackage) showOverlay() else if (active != null && active != packageName && !isSystemUi(active)) hideOverlay()
        }
        if (pkg != prefs.targetPackage) return
        showOverlay()
        // キャラの返信は少しずつ表示されることがあるので、変化が落ち着いてから読む
        main.removeCallbacks(scanRunnable)
        main.postDelayed(scanRunnable, SETTLE_MS)
    }

    /** 通知シェードやキーボードでオーバーレイを消さない */
    private fun isSystemUi(pkg: String) =
        pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun showOverlay() {
        if (!overlayShown) {
            overlay.show(); overlayShown = true; applyAutoUi()
            // 他のアプリから戻ってきた時、自動会話の途中なら続ける
            val key = lastHandledKey
            if (prefs.autoSend && countdown == null && candidates.isNotEmpty() && key != null) startCountdown(key)
        }
    }

    private fun hideOverlay() {
        if (overlayShown) { overlay.hide(); overlayShown = false }
        cancelCountdown()
    }

    private fun readScreen(): ChatScreen? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != prefs.targetPackage) return null
        return reader.read(root, resources.displayMetrics.widthPixels)
    }

    /** 画面を読んで、キャラクターの新着メッセージがあれば返信案を作る */
    private fun scan(force: Boolean) {
        val screen = readScreen() ?: return
        val msgs = screen.messages
        if (msgs.isEmpty()) return
        val last = msgs.last()
        if (last.fromUser && !force) return // 最後が自分の発言ならキャラの返事待ち
        val lastChar = msgs.lastOrNull { !it.fromUser } ?: return

        val key = lastChar.text
        if (!force && (key == lastHandledKey || key == generatingKey)) return

        // 文字が増えている途中なら、止まるまで待つ
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (key != seenKey) {
                seenKey = key; seenAt = now
                main.removeCallbacks(scanRunnable)
                main.postDelayed(scanRunnable, STABLE_MS)
                return
            }
            if (now - seenAt < STABLE_MS) {
                main.removeCallbacks(scanRunnable)
                main.postDelayed(scanRunnable, STABLE_MS - (now - seenAt))
                return
            }
        }
        if (!usingApi && !llm.isReady) { overlay.setStatus("AIモデル未読み込み"); return }

        generate(key, buildRequest(screen))
    }

    private fun buildRequest(screen: ChatScreen) = ReplyRequest(
        history = screen.messages.takeLast(prefs.contextCount),
        charName = screen.title,
        style = prefs.styleHint,
        userProfile = prefs.userProfile,
    )

    private fun generate(key: String, req: ReplyRequest) {
        cancelCountdown()
        genJob?.cancel()
        val my = ++genSeq
        generatingKey = key
        lastRequest = req
        overlay.setStatus("返信を考え中…（$engineLabel）")
        overlay.setCandidates(emptyList())
        overlay.setPanelVisible(true)
        genJob = scope.launch {
            try {
                val start = System.currentTimeMillis()
                val (list, used) = withContext(genDispatcher) { runEngine(req) }
                val sec = (System.currentTimeMillis() - start) / 100 / 10.0
                if (genSeq != my) return@launch
                candidates = list
                lastHandledKey = key
                overlay.setCandidates(list)
                if (list.isEmpty()) {
                    overlay.setStatus("候補を作れませんでした（再生成を押してください）")
                    onAutoError()
                } else {
                    autoErrors = 0
                    overlay.setStatus("候補（$used・${sec}秒）タップ=送信")
                    if (prefs.autoSend) startCountdown(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (genSeq != my) return@launch
                Log.e(TAG, "generate failed", t)
                overlay.setStatus(t.message ?: "生成エラー")
                onAutoError()
            } finally {
                if (genSeq == my) generatingKey = null
            }
        }
    }

    /** 選ばれたエンジンで生成。API失敗時は端末内モデルがあればそちらで作る */
    private fun runEngine(req: ReplyRequest): Pair<List<String>, String> {
        if (!usingApi) return llm.generateReplies(req) to "端末AI"
        return try {
            api.generate(prefs.engine, req) to engineLabel
        } catch (t: Throwable) {
            if (!prefs.localFallback) throw t
            if (!prefs.modelReady() && !(prefs.modelId == BundledModel.MODEL_ID && BundledModel.isBundled(this))) throw t
            Log.w(TAG, "api failed, fallback to local", t)
            installBundledIfNeeded()
            llm.ensureLoaded(prefs.modelFile(), prefs.useGpu)
            llm.generateReplies(req) to "端末AI（API失敗のため）"
        }
    }

    // ---------- 自動会話 ----------

    private fun applyAutoUi() {
        if (!::overlay.isInitialized) return
        overlay.setAutoSend(prefs.autoSend, autoTurns, prefs.autoMaxTurns)
        overlay.setKeepScreenOn(prefs.autoSend && prefs.keepScreenOn)
    }

    private fun startCountdown(key: String) {
        cancelCountdown()
        val min = prefs.autoSendDelaySec
        val max = prefs.autoSendDelayMaxSec
        var remain = if (max > min) Random.nextInt(min, max + 1) else min
        val pick = if (prefs.autoPickRandom && candidates.size > 1) Random.nextInt(candidates.size) else 0
        overlay.setCountdownVisible(true)
        val r = object : Runnable {
            override fun run() {
                if (!prefs.autoSend) { cancelCountdown(); return }
                if (remain <= 0) {
                    overlay.setCountdownVisible(false)
                    countdown = null
                    autoSendNow(key, pick)
                    return
                }
                overlay.setStatus("${remain}秒後に ${pick + 1} を自動送信（別の候補タップ／停止も可）")
                remain--
                main.postDelayed(this, 1000)
            }
        }
        countdown = r
        main.post(r)
    }

    private fun autoSendNow(key: String, pick: Int) {
        // 待っている間にキャラが続けて話していたら、その内容で作り直す
        val screen = readScreen()
        val nowKey = screen?.messages?.lastOrNull { !it.fromUser }?.text
        if (screen != null && nowKey != null && nowKey != key) {
            lastHandledKey = null
            scan(force = false)
            return
        }
        val text = candidates.getOrNull(pick) ?: candidates.firstOrNull() ?: return
        sendText(text, auto = true)
    }

    private fun onAutoError() {
        if (!prefs.autoSend) return
        autoErrors++
        if (autoErrors > MAX_AUTO_RETRY) {
            stopAuto("エラーが続いたので自動会話を止めました")
            return
        }
        overlay.setStatus("失敗。${AUTO_RETRY_SEC}秒後にやり直します（$autoErrors/$MAX_AUTO_RETRY）")
        main.postDelayed({ if (prefs.autoSend) onRegenerate() }, AUTO_RETRY_SEC * 1000L)
    }

    private fun stopAuto(msg: String) {
        prefs.autoSend = false
        cancelCountdown()
        applyAutoUi()
        overlay.setStatus(msg)
        toast(msg)
    }

    private fun cancelCountdown() {
        countdown?.let { main.removeCallbacks(it) }
        countdown = null
        if (::overlay.isInitialized) overlay.setCountdownVisible(false)
    }

    // ---------- 入力・送信 ----------

    /** 入力欄に文字を入れる。成功したら true */
    private fun putText(text: String): Boolean {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != prefs.targetPackage) {
            toast("MiraiMindの画面が開いていません"); return false
        }
        val input = reader.findInput(root)
        if (input == null) {
            toast("入力欄が見つかりません（設定で入力欄IDを指定してください）"); return false
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            toast("入力欄に文字を入れられませんでした"); return false
        }
        return true
    }

    private fun sendText(text: String, auto: Boolean = false) {
        if (!putText(text)) { if (auto) stopAuto("入力できないので自動会話を止めました"); return }
        // 文字が入ると送信ボタンが出るアプリが多いので少し待ってから押す
        main.postDelayed({ clickSend(text, auto, attempt = 1) }, 400)
    }

    private fun clickSend(text: String, auto: Boolean, attempt: Int) {
        val r2 = rootInActiveWindow ?: return
        val btn = reader.findSendButton(r2, reader.findInput(r2))
        if (btn == null || !btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            overlay.setStatus("入力欄に入れました。送信ボタンを押してください")
            if (auto) stopAuto("送信ボタンが押せないので自動会話を止めました")
            return
        }
        // 本当に送れたか確認（入力欄が空になっている or 最後の発言が自分）
        main.postDelayed({
            val root = rootInActiveWindow
            val inputText = root?.let { reader.findInput(it)?.text?.toString() }.orEmpty()
            val sent = inputText.isBlank() || inputText != text
            if (!sent && attempt < 2) { clickSend(text, auto, attempt + 1); return@postDelayed }
            if (!sent) {
                overlay.setStatus("送信を確認できませんでした")
                if (auto) stopAuto("送信できないので自動会話を止めました")
                return@postDelayed
            }
            candidates = emptyList()
            overlay.setCandidates(emptyList())
            overlay.setPanelVisible(false)
            if (auto) {
                autoTurns++
                applyAutoUi()
                val max = prefs.autoMaxTurns
                if (max in 1..autoTurns) {
                    stopAuto("自動会話 $autoTurns 回送ったので止めました")
                } else {
                    overlay.setStatus("送信しました（自動 $autoTurns 回目）。返事待ち…")
                }
            } else {
                overlay.setStatus("送信しました")
            }
        }, 1200)
    }

    // ---------- OverlayPanel.Listener ----------

    override fun onCandidateTapped(index: Int, text: String) {
        if (text.isBlank()) return
        cancelCountdown()
        sendText(text)
    }

    override fun onCandidateLongPressed(index: Int, text: String) {
        if (text.isBlank()) return
        cancelCountdown()
        if (putText(text)) {
            overlay.setPanelVisible(false)
            toast("入力欄に入れました。直して送信してください")
        }
    }

    override fun onRegenerate() {
        cancelCountdown()
        val screen = readScreen()
        val req = if (screen != null && screen.messages.isNotEmpty()) buildRequest(screen) else lastRequest
        val key = req?.history?.lastOrNull { !it.fromUser }?.text
        if (req == null || key == null) { overlay.setStatus("キャラクターのメッセージが見つかりません"); return }
        if (!usingApi && !llm.isReady) { overlay.setStatus("AIモデル未読み込み"); return }
        generate(key, req)
    }

    override fun onToggleAutoSend() {
        prefs.autoSend = !prefs.autoSend
        if (prefs.autoSend) {
            autoTurns = 0; autoErrors = 0
            toast("自動会話オン（最大${if (prefs.autoMaxTurns > 0) "${prefs.autoMaxTurns}回" else "無制限"}）")
            // 候補がすでに出ていればそこから始める
            val key = lastHandledKey
            if (candidates.isNotEmpty() && key != null) startCountdown(key) else scan(force = false)
        } else {
            cancelCountdown()
            overlay.setStatus("自動会話を止めました")
        }
        applyAutoUi()
    }

    override fun onCancelAutoSend() {
        cancelCountdown()
        overlay.setStatus("今回の自動送信を止めました。候補をタップで送信")
    }

    override fun onDumpScreen() {
        val root = rootInActiveWindow ?: return
        val screen = reader.read(root, resources.displayMetrics.widthPixels)
        val text = buildString {
            appendLine("package=${root.packageName}")
            appendLine("title=${screen.title}")
            appendLine("--- 読み取ったメッセージ ---")
            screen.messages.forEach {
                appendLine((if (it.fromUser) "[自分] " else "[キャラ] ") + it.text.replace("\n", " "))
            }
            appendLine("--- 画面構造 ---")
            append(ChatReader.dump(root))
        }
        File(filesDir, DUMP_FILE).writeText(text)
        toast("画面構造を保存しました（設定画面から共有できます）")
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) hideOverlay()
        scope.cancel()
        if (::llm.isInitialized) {
            val l = llm
            Thread { l.close(); genDispatcher.close() }.start()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReplyService"
        private const val SETTLE_MS = 1500L
        private const val STABLE_MS = 1200L
        private const val MAX_AUTO_RETRY = 3
        private const val AUTO_RETRY_SEC = 8
        const val DUMP_FILE = "screen_dump.txt"

        @Volatile var instance: ReplyAccessibilityService? = null
    }
}
