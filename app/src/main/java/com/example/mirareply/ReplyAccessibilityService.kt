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
    private lateinit var store: CharStore
    private lateinit var usage: UsageStore

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
    private var nudgesInRow = 0
    private val idleRunnable = Runnable { onIdle() }

    // 記憶・学習
    private var currentMem: CharMemory? = null
    /** アプリが送った文（口調の手本から除くため） */
    private val appSent = LinkedHashSet<String>()

    private val scanRunnable = Runnable { scan(force = false) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        reader = ChatReader(prefs)
        llm = LlmReplyEngine(applicationContext)
        usage = UsageStore(this)
        api = ApiReplyEngine(prefs, usage)
        store = CharStore(this)
        overlay = OverlayPanel(this, this)
        instance = this
        applyAutoUi()
        overlay.setStatus("待機中")
        ModelDownloader.autoStartIfNeeded(this)
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
        if (!prefs.modelReady()) {
            ModelDownloader.autoStartIfNeeded(this)
            overlay.setStatus(if (firstApiWithKey() != null) "端末AIのモデル準備中（それまでAPIで動作）" else "端末AIのモデルをダウンロード中…")
            return
        }
        scope.launch {
            overlay.setStatus("AIモデルを読み込み中…")
            try {
                withContext(genDispatcher) { llm.ensureLoaded(prefs.modelFile(), prefs.useGpu) }
                overlay.setStatus("準備OK（端末AI・${llm.backendName}）")
                scan(force = false)
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                overlay.setStatus(t.message ?: "モデル読み込みエラー")
            }
        }
    }

    /** 古い会話がたまったら記憶（要約）にまとめる。生成スレッドの空き時間に動かす */
    private var summarizing = false
    private fun maybeSummarize(mem: CharMemory) {
        if (!prefs.memoryEnabled || summarizing) return
        val end = mem.log.size - prefs.contextCount
        if (end - mem.summarizedCount < SUMMARY_BATCH) return
        val provider = if (usingApi) prefs.engine else firstApiWithKey()
        if (provider == null && !llm.isReady) return
        summarizing = true
        // ログは画面読み取り（メインスレッド）で増えるので、ここで写しを取ってから渡す
        val entries = mem.log.subList(mem.summarizedCount, end).toList().take(if (provider != null) 150 else 50)
        val oldSummary = mem.summary
        scope.launch {
            try {
                val text = withContext(genDispatcher) {
                    if (provider != null) api.summarize(provider, mem.name, oldSummary, entries)
                    else llm.complete(ReplyPrompt.summarySystem(mem.name), ReplyPrompt.summaryUser(mem.name, oldSummary, entries)).trim()
                }
                // メインスレッドに戻ってから反映
                if (text.length > 20 && mem.summary == oldSummary) {
                    mem.summary = text.take(1500)
                    mem.summarizedCount = (mem.summarizedCount + entries.size).coerceAtMost(mem.log.size)
                    store.save(mem)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "summarize failed", t)
            } finally {
                summarizing = false
            }
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
            if (active == prefs.targetPackage) showOverlay()
            else if (active != null && active != packageName && !isSystemUi(active)) {
                // 一瞬だけ他のウィンドウが前に出ることがある（ゲームスペース・フローティング等）ので、少し待ってから消す
                if (overlayShown) {
                    DebugLog.d("他の画面が前面: $active（2秒後に確認）")
                    main.removeCallbacks(hideRunnable)
                    main.postDelayed(hideRunnable, HIDE_DELAY_MS)
                }
            }
        }
        if (pkg != prefs.targetPackage) return
        main.removeCallbacks(hideRunnable)
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

    private val hideRunnable = Runnable {
        val active = rootInActiveWindow?.packageName?.toString()
        if (active != prefs.targetPackage) hideOverlay()
    }

    /** オーバーレイを隠す。自動送信の待ちは消さない（戻ってきたら続きから送る） */
    private fun hideOverlay() {
        if (overlayShown) { overlay.hide(); overlayShown = false; DebugLog.d("オーバーレイを隠した") }
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
        nudgesInRow = 0
        main.removeCallbacks(idleRunnable)
        DebugLog.d("新着: 「${key.take(20)}」 自動=${prefs.autoSend}")
        generate(key, buildRequest(screen))
    }

    private fun buildRequest(screen: ChatScreen, nudge: Boolean = false): ReplyRequest {
        var history = screen.messages.takeLast(prefs.contextCount)
        var memory = ""
        var examples = emptyList<String>()
        var pickHint = ""
        val mem = if (prefs.memoryEnabled || prefs.styleLearn) store.load(screen.title) else null
        currentMem = mem
        if (mem != null) {
            store.merge(mem, screen.messages, appSent)
            // ログの末尾が画面と一致していれば、画面外の分も含めてログから読ませる
            val last = mem.log.lastOrNull()
            if (last != null && CharStore.norm(last.text) == CharStore.norm(screen.messages.last().text)) {
                history = mem.log.takeLast(prefs.contextCount).map { ChatMessage(it.text, it.fromUser, 0) }
            }
            if (prefs.memoryEnabled) memory = mem.summary
            if (prefs.styleLearn) {
                examples = store.styleExamples(mem, if (usingApi) 12 else 5)
                pickHint = store.pickHint(mem)
            }
        }
        return ReplyRequest(
            history = history,
            charName = screen.title,
            style = prefs.styleHint,
            userProfile = prefs.userProfile,
            memory = memory,
            styleExamples = examples,
            pickHint = pickHint,
            nudge = nudge,
        )
    }

    private fun firstApiWithKey(): String? =
        listOf(Prefs.ENGINE_CLAUDE, Prefs.ENGINE_OPENAI, Prefs.ENGINE_GEMINI).firstOrNull { prefs.apiKey(it).isNotBlank() }

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
                if (genSeq != my) { DebugLog.d("生成結果を破棄（新しい生成あり）"); return@launch }
                DebugLog.d("候補${list.size}件（$used） 自動=${prefs.autoSend}")
                candidates = list
                lastHandledKey = key
                overlay.setCandidates(list)
                if (list.isEmpty()) {
                    overlay.setStatus("候補を作れませんでした（再生成を押してください）")
                    onAutoError()
                } else {
                    autoErrors = 0
                    val cost = if (used.startsWith("端末")) "" else "・\$${"%.4f".format(api.lastCostUsd)}／今月\$${"%.2f".format(usage.monthUsd())}"
                    overlay.setStatus("候補（$used・${sec}秒$cost）")
                    if (prefs.autoSend) startCountdown(key)
                    currentMem?.let { maybeSummarize(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (genSeq != my) return@launch
                Log.e(TAG, "generate failed", t)
                DebugLog.d("生成エラー: ${t.message}")
                overlay.setStatus(t.message ?: "生成エラー")
                onAutoError()
            } finally {
                if (genSeq == my) generatingKey = null
            }
        }
    }

    /** 選ばれたエンジンで生成。API失敗時は端末内モデルがあればそちらで作る */
    private fun runEngine(req: ReplyRequest): Pair<List<String>, String> {
        if (!usingApi) {
            if (!llm.isReady && prefs.modelReady()) llm.ensureLoaded(prefs.modelFile(), prefs.useGpu)
            if (llm.isReady) return llm.generateReplies(req) to "端末AI"
            // モデルがまだ無い間は、キーのあるAPIで代わりに作る
            val alt = firstApiWithKey() ?: throw IllegalStateException("端末AIのモデルがまだありません（ダウンロード中なら完了をお待ちください）")
            return api.generate(alt, req) to "${Prefs.ENGINE_LABELS[alt]!!.substringBefore(" ")}（モデル準備中のため）"
        }
        return try {
            api.generate(prefs.engine, req) to engineLabel
        } catch (t: Throwable) {
            DebugLog.d("API失敗: ${t.message}")
            main.post { toast("API失敗: ${t.message?.take(80)}") }
            if (!prefs.localFallback) throw t
            if (!prefs.modelReady()) throw t
            Log.w(TAG, "api failed, fallback to local", t)
            llm.ensureLoaded(prefs.modelFile(), prefs.useGpu)
            llm.generateReplies(req) to "端末AI（API失敗のため）"
        }
    }

    // ---------- 自動会話 ----------

    private fun applyAutoUi() {
        if (!::overlay.isInitialized) return
        AutoChatTileService.refresh(this)
        overlay.setAutoSend(prefs.autoSend, autoTurns, prefs.autoMaxTurns)
        overlay.setKeepScreenOn(prefs.autoSend && prefs.keepScreenOn)
    }

    private fun startCountdown(key: String) {
        cancelCountdown()
        if (!prefs.inAutoHours()) {
            // 時間外: 5分ごとに見直して、時間になったら送る
            overlay.setStatus("時間外（${prefs.autoHours}時）なので自動送信は待機中。タップで送信可")
            val r = Runnable { countdown = null; if (prefs.autoSend && candidates.isNotEmpty() && lastHandledKey == key) startCountdown(key) }
            countdown = r
            main.postDelayed(r, 5 * 60_000L)
            return
        }
        val min = prefs.autoSendDelaySec
        val max = prefs.autoSendDelayMaxSec
        var remain = if (max > min) Random.nextInt(min, max + 1) else min
        val pick = if (prefs.autoPickRandom && candidates.size > 1) Random.nextInt(candidates.size) else 0
        overlay.setCountdownVisible(true)
        DebugLog.d("カウントダウン開始 ${remain}秒 候補${pick + 1}")
        val r = object : Runnable {
            override fun run() {
                if (!prefs.autoSend) { DebugLog.d("カウントダウン中に自動会話オフ"); cancelCountdown(); return }
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

    private var regenInTurn = 0
    private var waitFront = 0

    private fun autoSendNow(key: String, pick: Int) {
        val screen = readScreen()
        if (screen == null) {
            // MiraiMind が前面に無い → 戻るまで3秒ごとに待つ（最大10分）
            if (waitFront++ < 200 && prefs.autoSend) {
                overlay.setStatus("MiraiMindのチャット画面に戻ると自動送信します")
                val r = Runnable { countdown = null; autoSendNow(key, pick) }
                countdown = r
                main.postDelayed(r, 3000)
            }
            DebugLog.d("自動送信: MiraiMindが前面に無いので待機($waitFront)")
            return
        }
        waitFront = 0
        // 待っている間にキャラが続けて話していたら、その内容で作り直す（1ターン2回まで）
        if (hasNewCharMessage(screen, key) && regenInTurn < 2) {
            regenInTurn++
            DebugLog.d("自動送信: キャラの新しい発言があるので作り直し($regenInTurn)")
            lastHandledKey = null
            seenKey = null
            scan(force = false)
            return
        }
        val text = candidates.getOrNull(pick) ?: candidates.firstOrNull()
        if (text == null) { DebugLog.d("自動送信: 候補が空"); return }
        regenInTurn = 0
        DebugLog.d("自動送信: 候補${pick + 1}を送信 「${text.take(20)}」")
        sendText(text, auto = true)
    }

    /** key（候補を作った時のキャラの発言）より後に、キャラの新しい発言があるか */
    private fun hasNewCharMessage(screen: ChatScreen, key: String): Boolean {
        val msgs = screen.messages
        if (key.startsWith(NUDGE_KEY)) return msgs.lastOrNull()?.fromUser == false
        val k = CharStore.norm(key)
        val idx = msgs.indexOfLast { !it.fromUser && CharStore.norm(it.text).let { t -> t == k || t.startsWith(k) || k.startsWith(t) } }
        if (idx < 0) return false // 見つからない（スクロール等）→ そのまま送る
        return msgs.drop(idx + 1).any { !it.fromUser }
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
        DebugLog.d("自動会話停止: $msg")
        prefs.autoSend = false
        cancelCountdown()
        main.removeCallbacks(idleRunnable)
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
        appSent.add(CharStore.norm(text))
        while (appSent.size > 300) appSent.remove(appSent.first())
        if (!putText(text)) { if (auto) stopAuto("入力できないので自動会話を止めました"); return }
        // 文字が入ると送信ボタンが出るアプリが多いので少し待ってから押す
        main.postDelayed({ clickSend(text, auto, attempt = 0) }, 400)
    }

    /**
     * 送信ボタンを押す。アプリによって効く押し方が違うので、順番に試して
     * 入力欄が空になったら成功とみなす。成功した方法は覚えて次から最初に使う。
     */
    private fun clickSend(text: String, auto: Boolean, attempt: Int) {
        val order = SEND_METHODS.indices.sortedBy { if (it == prefs.sendMethod) -1 else it }
        if (attempt >= order.size) {
            DebugLog.d("送信: すべての方法で失敗")
            overlay.setStatus("入力欄に入れました。送信ボタンを押してください")
            if (auto) stopAuto("送信ボタンが押せないので自動会話を止めました（設定の「動作ログを共有」で原因を送ってください）")
            return
        }
        val method = order[attempt]
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != prefs.targetPackage) {
            DebugLog.d("送信: MiraiMindが前面に無い"); return
        }
        val input = reader.findInput(root)
        val cands = reader.findSendCandidates(root, input)
        val ok = try { trySend(method, root, input, cands, text) } catch (t: Throwable) { DebugLog.d("送信方法${method}で例外: ${t.message}"); false }
        DebugLog.d("送信: 方法${method}（${SEND_METHODS[method]}）実行=$ok 候補${cands.size}個" +
            (cands.firstOrNull()?.let { " 1番目=${it.viewIdResourceName ?: it.className} desc=${it.contentDescription}" } ?: ""))
        if (!ok) { clickSend(text, auto, attempt + 1); return }
        checkSent(text, auto, method, attempt, check = if (method == 6) -1 else 0)
    }

    /** 送れたか確認。入力欄が空でも画面に出るまで少し待つ（二重送信を防ぐ） */
    private fun checkSent(text: String, auto: Boolean, method: Int, attempt: Int, check: Int) {
        main.postDelayed({
            val st = sendState(text)
            DebugLog.d("送信確認: 方法$method $st")
            when {
                st == "sent" -> {
                    if (prefs.sendMethod != method) { prefs.sendMethod = method; DebugLog.d("送信方法${method}を記憶") }
                    onSent(auto)
                }
                // ↵のあと入力欄が空のまま → 送れている（MiraiMindは自分の発言がすぐ画面に出ないことがある）
                st == "cleared" && check >= 1 -> {
                    if (prefs.sendMethod != method) { prefs.sendMethod = method; DebugLog.d("送信方法${method}を記憶") }
                    onSent(auto)
                }
                st == "cleared" -> checkSent(text, auto, method, attempt, check + 1)
                st == "same" && check < 0 -> checkSent(text, auto, method, attempt, check + 1) // 方法6は時間がかかる
                else -> {
                    // 入力欄が変わってしまっていたら入れ直してから次の方法へ
                    if (st != "same") putText(text)
                    clickSend(text, auto, attempt + 1)
                }
            }
        }, 1000)
    }

    private fun trySend(method: Int, root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?, cands: List<AccessibilityNodeInfo>, text: String): Boolean {
        fun tap(n: AccessibilityNodeInfo?): Boolean {
            n ?: return false
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            if (r.isEmpty) return false
            val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val g = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build()
            return dispatchGesture(g, null, null)
        }
        fun click(n: AccessibilityNodeInfo?) = n?.let { reader.clickableParent(it) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        return when (method) {
            0 -> click(cands.getOrNull(0))
            1 -> tap(cands.getOrNull(0))
            2 -> input?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
            3 -> { // 貼り付けで入れ直す（アプリが文字入力を認識していない場合）
                if (input == null) return false
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                })
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                input.refresh()
                if (CharStore.norm(input.text?.toString().orEmpty()) != CharStore.norm(text)) {
                    // 貼り付けできなかった → 元に戻して次の方法へ
                    putText(text)
                    return false
                }
                // 送信ボタンが現れるのを待ってから押す
                main.postDelayed({
                    val r = rootInActiveWindow ?: return@postDelayed
                    val c = reader.findSendCandidates(r, reader.findInput(r))
                    if (!click(c.getOrNull(0))) tap(c.getOrNull(0))
                }, 400)
                true
            }
            6 -> { // 入力欄をタップしてキーボードを開き、そこへ文を入れてキーボードの「↵」で送る（MiraiMind方式）
                val box = input ?: return false
                if (!(box.performAction(AccessibilityNodeInfo.ACTION_CLICK) || reader.clickableParent(box)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)) tap(box)
                main.postDelayed({
                    val r = rootInActiveWindow ?: return@postDelayed
                    val field = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: reader.findInput(r)
                    if (field == null) { DebugLog.d("方法6: キーボード後の入力欄が見つからない"); return@postDelayed }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })
                    main.postDelayed({
                        val r2 = rootInActiveWindow ?: return@postDelayed
                        val f2 = r2.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: field
                        val ok = f2.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        DebugLog.d("方法6: ↵ 実行=$ok 入力欄=${f2.viewIdResourceName}")
                    }, 350)
                }, 800)
                true
            }
            4 -> tap(cands.getOrNull(1))
            5 -> click(cands.getOrNull(1))
            else -> false
        }
    }

    /**
     * sent=画面の最後に自分のその発言が出た / same=入力欄にまだ同じ文 /
     * cleared=入力欄は空だが画面にまだ出ない / changed=入力欄の文が変わった
     */
    private fun sendState(text: String): String {
        val screen = readScreen() ?: return "same"
        val t = CharStore.norm(text)
        val lastMine = screen.messages.lastOrNull()?.takeIf { it.fromUser }?.let { CharStore.norm(it.text) }
        if (lastMine != null && (lastMine == t || lastMine.startsWith(t.take(15)) || t.startsWith(lastMine.take(15)))) return "sent"
        val input = rootInActiveWindow?.let { reader.findInput(it) }
        val cur = CharStore.norm(input?.text?.toString().orEmpty())
        val hint = CharStore.norm(input?.hintText?.toString().orEmpty())
        return when {
            cur == t -> "same"
            cur.isEmpty() || cur == hint -> "cleared"
            else -> "changed"
        }
    }

    private fun onSent(auto: Boolean) {
        DebugLog.d("送信成功")
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
                if (prefs.nudgeMin > 0) {
                    main.removeCallbacks(idleRunnable)
                    main.postDelayed(idleRunnable, prefs.nudgeMin * 60_000L)
                }
            }
        } else {
            overlay.setStatus("送信しました")
        }
    }

    // ---------- OverlayPanel.Listener ----------

    override fun onCandidateTapped(index: Int, text: String) {
        if (text.isBlank()) return
        cancelCountdown()
        recordPick(index)
        sendText(text)
    }

    override fun onCandidateLongPressed(index: Int, text: String) {
        if (text.isBlank()) return
        cancelCountdown()
        appSent.add(CharStore.norm(text)) // そのまま送られた時用（直して送れば自分の文として学習）
        recordPick(index)
        if (putText(text)) {
            overlay.setPanelVisible(false)
            toast("入力欄に入れました。直して送信してください")
        }
    }

    private fun recordPick(index: Int) {
        val mem = currentMem ?: return
        if (index in 0..2 && prefs.styleLearn) { mem.picks[index]++; store.save(mem) }
    }

    /** 自動会話中、キャラから返事が無いまま時間がたった時 */
    private fun onIdle() {
        if (!prefs.autoSend || prefs.nudgeMin <= 0 || nudgesInRow >= 1 || !prefs.inAutoHours()) return
        val screen = readScreen() ?: return
        if (screen.messages.lastOrNull()?.fromUser != true) return
        nudgesInRow++
        overlay.setStatus("返事が無いので、話しかける案を作ります")
        generate(NUDGE_KEY + System.currentTimeMillis(), buildRequest(screen, nudge = true))
    }

    /** クイック設定タイルから */
    fun toggleAutoFromOutside() { main.post { onToggleAutoSend() } }

    override fun onRegenerate() {
        cancelCountdown()
        val screen = readScreen()
        val req = if (screen != null && screen.messages.isNotEmpty()) buildRequest(screen) else lastRequest
        val key = req?.history?.lastOrNull { !it.fromUser }?.text
        if (req == null || key == null) { overlay.setStatus("キャラクターのメッセージが見つかりません"); return }
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
            main.removeCallbacks(idleRunnable)
            overlay.setStatus("自動会話を止めました")
        }
        applyAutoUi()
    }

    override fun onCancelAutoSend() {
        DebugLog.d("停止ボタン")
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
            appendLine("--- 動作ログ ---")
            appendLine(DebugLog.text().lines().takeLast(80).joinToString("\n"))
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
        private const val HIDE_DELAY_MS = 2000L
        private val SEND_METHODS = listOf("ボタンを押す", "ボタン位置をタップ", "キーボードの送信", "貼り付けで入れ直して押す", "2番目の候補をタップ", "2番目の候補を押す", "入力欄を開いてキーボードの↵")
        private const val MAX_AUTO_RETRY = 3
        private const val AUTO_RETRY_SEC = 8
        private const val SUMMARY_BATCH = 40
        private const val NUDGE_KEY = "\u0000nudge:"
        const val DUMP_FILE = "screen_dump.txt"

        @Volatile var instance: ReplyAccessibilityService? = null
    }
}
