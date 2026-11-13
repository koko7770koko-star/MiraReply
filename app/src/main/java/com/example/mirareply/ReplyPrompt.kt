package com.example.mirareply

import org.json.JSONArray
import org.json.JSONObject

/** 返信候補づくりの依頼内容（端末内AI・API 共通） */
data class ReplyRequest(
    val history: List<ChatMessage>,   // 古い順。最後がキャラの最新メッセージ
    val charName: String,             // 相手キャラ名（不明なら空）
    val style: String,                // 返信の雰囲気
    val userProfile: String,          // 自分の呼び名・設定
)

object ReplyPrompt {

    fun system(req: ReplyRequest, json: Boolean): String = buildString {
        val who = req.charName.ifBlank { "AIキャラクター" }
        appendLine("あなたはチャットの返信を考えるアシスタント。")
        appendLine("ユーザーは「$who」と1対1で会話中。$who の最新メッセージに対し、ユーザーが次に送る返信の候補を3つ作る。")
        appendLine()
        appendLine("ルール:")
        appendLine("- 3つは方向性を変える: 1=気持ちに寄り添うリアクション / 2=質問や提案で話を広げる / 3=遊び心・冗談・ちょっと意外な返し。")
        appendLine("- 最新メッセージの具体的な内容（言葉・出来事・問いかけ）に必ず触れる。質問されていたら答える。")
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
        appendLine("【$who の最新メッセージ】")
        appendLine(req.history.lastOrNull { !it.fromUser }?.text ?: "")
        appendLine()
        append("ユーザーの返信候補を3つ:")
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
