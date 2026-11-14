package com.example.mirareply

import org.json.JSONArray
import org.json.JSONObject

/** 返信候補づくりの依頼内容（端末内AI・API 共通） */
data class ReplyRequest(
    val history: List<ChatMessage>,   // 古い順。最後がキャラの最新メッセージ
    val charName: String,             // 相手キャラ名（不明なら空）
    val style: String,                // 返信の雰囲気
    val userProfile: String,          // 自分の呼び名・設定
    val memory: String = "",          // 古い会話の要約（記憶）
    val styleExamples: List<String> = emptyList(), // 自分が過去に送った文（口調の手本）
    val pickHint: String = "",        // よく選ぶ候補の傾向
    val nudge: Boolean = false,       // true: 返事が来ないので自分から話しかける
)

object ReplyPrompt {

    fun system(req: ReplyRequest, json: Boolean): String = buildString {
        val who = req.charName.ifBlank { "AIキャラクター" }
        appendLine("あなたはチャットの返信を考えるアシスタント。")
        if (req.nudge) {
            appendLine("ユーザーは「$who」と1対1で会話中。しばらく$who から返事が無く会話が止まっている。ユーザーから自然に話しかけるメッセージの候補を3つ作る。")
        } else {
            appendLine("ユーザーは「$who」と1対1で会話中。$who の最新メッセージに対し、ユーザーが次に送る返信の候補を3つ作る。")
        }
        appendLine()
        appendLine("ルール:")
        if (req.nudge) {
            appendLine("- 3つは方向性を変える: 1=さっきの話の続き・気づかい / 2=自分の近況や新しい話題 / 3=質問や遊びの誘い。")
            appendLine("- 催促や責める言い方（「無視？」「返事して」等）はしない。")
        } else {
            appendLine("- 3つは方向性を変える: 1=気持ちに寄り添うリアクション / 2=質問や提案で話を広げる / 3=遊び心・冗談・ちょっと意外な返し。")
            appendLine("- 最新メッセージの具体的な内容（言葉・出来事・問いかけ）に必ず触れる。質問されていたら答える。")
        }
        appendLine("- 記憶にある名前・呼び方・約束・好みなどと矛盾しない。自然な時は記憶の内容に触れてよい。")
        appendLine("- ユーザー本人の発言だけを書く。$who のセリフ、ト書き、（）内の動作説明、解説は書かない。")
        appendLine("- これまでのユーザーの口調・一人称・呼び方に合わせる。")
        appendLine("- ユーザーの過去の発言と同じ言い回しや、毎回同じ始まり方（「そうなんだ」「いいね」等）を繰り返さない。")
        appendLine("- 会話が同じ話題で止まっていたら、候補2か3で自然に新しい話題へつなげる。")
        appendLine("- 不自然な敬語、絵文字の多用、長すぎる文は避ける。")
        if (req.style.isNotBlank()) {
            appendLine()
            appendLine("返信スタイルの希望: ${req.style.trim()}")
        }
        if (req.userProfile.isNotBlank()) {
            appendLine("ユーザー自身の設定: ${req.userProfile.trim()}")
        }
        if (req.memory.isNotBlank()) {
            appendLine()
            appendLine("【$who との これまでの記憶（古い会話の要約）】")
            appendLine(req.memory.trim())
        }
        if (req.styleExamples.isNotEmpty()) {
            appendLine()
            appendLine("【ユーザーが普段送っている文の例】（一人称・語尾・長さ・絵文字や「w」の使い方などの書き癖を真似る。内容はコピーしない）")
            req.styleExamples.forEach { appendLine("- ${it.replace('\n', ' ').take(120)}") }
        }
        if (req.pickHint.isNotBlank()) appendLine(req.pickHint)
        appendLine()
        if (json) {
            appendLine("出力は次のJSONだけ。前置き・コードブロック・解説は不要。")
            appendLine("""{"replies":["返信1","返信2","返信3"]}""")
        } else {
            appendLine("出力は次の形式だけにする。前置きや解説は書かない。")
            appendLine("1. （返信1）")
            appendLine("2. （返信2）")
            appendLine("3. （返信3）")
        }
    }

    fun user(req: ReplyRequest): String = buildString {
        val who = req.charName.ifBlank { "キャラ" }
        appendLine("【これまでの会話】")
        for (m in req.history) {
            val name = if (m.fromUser) "ユーザー" else who
            appendLine("$name: ${m.text.replace('\n', ' ')}")
        }
        appendLine()
        if (req.nudge) {
            append("ユーザーから話しかけるメッセージ候補を3つ:")
        } else {
            appendLine("【$who の最新メッセージ】")
            appendLine(req.history.lastOrNull { !it.fromUser }?.text ?: "")
            appendLine()
            append("ユーザーの返信候補を3つ:")
        }
    }

    // ---------- 記憶（要約） ----------

    fun summarySystem(charName: String) = buildString {
        val who = charName.ifBlank { "キャラ" }
        appendLine("あなたはユーザーと「$who」のチャットの記憶係。今までの記憶と新しい会話を合わせ、今後の会話で役立つ記憶を書き直す。")
        appendLine("残すもの: 互いの呼び方・名前、ユーザーについて分かったこと（好み・予定・出来事）、$who の設定や言ったこと、約束、二人の関係の変化、続いている話題。")
        appendLine("あいさつや一時的な雑談は捨てる。箇条書き（「- 」で始める）で、全体で600字以内。前置き不要。")
    }

    fun summaryUser(charName: String, oldSummary: String, entries: List<LogEntry>) = buildString {
        val who = charName.ifBlank { "キャラ" }
        appendLine("【今までの記憶】")
        appendLine(oldSummary.ifBlank { "（まだ無し）" })
        appendLine()
        appendLine("【新しい会話】")
        for (e in entries) appendLine((if (e.fromUser) "ユーザー" else who) + ": " + e.text.replace('\n', ' '))
        appendLine()
        append("書き直した記憶:")
    }

    // ---------- 出力の解析 ----------

    private val numbered = Regex("""^\s*(?:[1-9１-９][.)．、:：）]|[-・*●])\s*(.+)$""")

    /** モデルの出力（JSON または番号付きリスト）から候補文を取り出し、整える */
    fun parse(raw: String, history: List<ChatMessage>): List<String> {
        val items = parseJson(raw) ?: parseLines(raw)
        val recentMine = history.filter { it.fromUser }.map { norm(it.text) }.toSet()
        return items
            .map { cleanup(it) }
            .filter { it.isNotEmpty() && !it.startsWith("（返信") && !it.startsWith("返信1") }
            .filter { norm(it) !in recentMine }       // 過去の自分の発言そのままは除く
            .distinctBy { norm(it) }
            .take(3)
    }

    private fun parseJson(raw: String): List<String>? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                val arr = JSONObject(raw.substring(s, e + 1)).optJSONArray("replies") ?: return null
                return List(arr.length()) { arr.optString(it) }
            } catch (_: Throwable) {}
        }
        val a = raw.indexOf('['); val b = raw.lastIndexOf(']')
        if (a >= 0 && b > a) {
            try {
                val arr = JSONArray(raw.substring(a, b + 1))
                return List(arr.length()) { arr.optString(it) }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun parseLines(raw: String): List<String> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
        val items = lines.mapNotNull { numbered.find(it)?.groupValues?.get(1) }
        return items.ifEmpty { lines }
    }

    private fun norm(s: String) = s.replace(Regex("""[\s　、。！？!?…〜~ー]"""), "")

    fun cleanup(s: String): String {
        var t = s.trim()
        t = t.removePrefix("**").removeSuffix("**").trim()
        // 「ユーザー:」「返信1:」「共感:」などのラベルを外す
        t = t.replace(Regex("""^(ユーザー|あなた|返信\s*\d?|候補\s*\d?|共感|質問|遊び心|リアクション)\s*[:：]\s*"""), "")
        // 全体を囲む括弧・引用符を外す
        val pairs = listOf("「" to "」", "『" to "』", "\"" to "\"", "“" to "”")
        for ((a, b) in pairs) {
            if (t.length >= 2 && t.startsWith(a) && t.endsWith(b)) t = t.substring(a.length, t.length - b.length).trim()
        }
        return t
    }
}
