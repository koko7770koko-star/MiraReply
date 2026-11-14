package com.example.mirareply

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * 端末内で LLM（Gemma）を動かして返信候補を作る。
 * ネットワークには一切アクセスしない。
 * 呼び出しはすべてバックグラウンドスレッドから行うこと（重い処理のため）。
 */
class LlmReplyEngine(private val context: Context) {

    private var engine: Engine? = null
    private var loadedPath: String? = null
    var backendName: String = "-"
        private set

    val isReady: Boolean get() = engine != null

    /** モデルを読み込む（初回は10秒ほどかかる）。読み込み済みなら何もしない。 */
    @Synchronized
    fun ensureLoaded(modelFile: File, useGpu: Boolean) {
        if (engine != null && loadedPath == modelFile.absolutePath) return
        close()
        require(modelFile.exists() && modelFile.length() > 0) {
            "モデルファイルがありません。設定画面からダウンロードしてください。"
        }
        val backends = if (useGpu) listOf<Backend>(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var lastError: Throwable? = null
        for (b in backends) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = b,
                        maxNumTokens = 4096,
                        cacheDir = context.cacheDir.path,
                    )
                )
                e.initialize()
                engine = e
                loadedPath = modelFile.absolutePath
                backendName = b.name
                Log.i(TAG, "model loaded with ${b.name}")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "backend ${b.name} failed", t)
                lastError = t
            }
        }
        throw IllegalStateException("モデルを読み込めませんでした: ${lastError?.message}", lastError)
    }

    /** 汎用: system と user を渡して文章を作る（記憶の要約などに使う） */
    @Synchronized
    fun complete(system: String, user: String): String {
        val e = engine ?: throw IllegalStateException("モデルが読み込まれていません")
        return createConversation(e, system).use { it.sendMessage(user).toString() }
    }

    /** 会話履歴から返信候補を3つ作る */
    @Synchronized
    fun generateReplies(req: ReplyRequest): List<String> {
        val e = engine ?: throw IllegalStateException("モデルが読み込まれていません")
        val conv = createConversation(e, ReplyPrompt.system(req, json = false))
        val raw = try {
            conv.use { it.sendMessage(ReplyPrompt.user(req)).toString() }
        } catch (t: Throwable) {
            throw IllegalStateException("返信の生成に失敗しました: ${t.message}", t)
        }
        Log.d(TAG, "raw output:\n$raw")
        return ReplyPrompt.parse(raw, req.history)
    }

    private fun createConversation(e: Engine, system: String): Conversation {
        // 毎回違う候補が出るように seed を変える（「再生成」用）
        val seed = (System.nanoTime() and 0x7fffffff).toInt()
        val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9, seed = seed)
        return try {
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = sampler,
                    maxOutputToken = 400,
                    // 返信案作りに「考える」時間は不要なのでオフ（速くなる）
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                )
            )
        } catch (t: Throwable) {
            // 思考モード非対応モデル向けのフォールバック
            Log.w(TAG, "fallback conversation config", t)
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = sampler,
                    maxOutputToken = 400,
                )
            )
        }
    }

    @Synchronized
    fun close() {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "LlmReplyEngine"
    }
}
