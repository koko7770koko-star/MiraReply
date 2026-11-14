package com.example.mirareply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会話ログの1件 */
data class LogEntry(val time: Long, val fromUser: Boolean, val text: String, val byApp: Boolean = false)

/** キャラ1人分の記憶 */
class CharMemory(val name: String) {
    val log = ArrayList<LogEntry>()
    /** 古い会話の要約（＝記憶）。設定画面で手直しもできる */
    var summary: String = ""
    /** log の先頭から何件までを要約に取り込んだか */
    var summarizedCount: Int = 0
    /** 候補1/2/3 を手動で選んだ回数（口調学習用） */
    val picks = IntArray(3)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("summary", summary)
        .put("summarizedCount", summarizedCount)
        .put("picks", JSONArray(picks.toList()))
        .put("log", JSONArray().also { a ->
            log.forEach { e ->
                a.put(JSONObject().put("t", e.time).put("u", e.fromUser).put("x", e.text).put("a", e.byApp))
            }
        })

    companion object {
        fun fromJson(j: JSONObject) = CharMemory(j.optString("name")).apply {
            summary = j.optString("summary")
            summarizedCount = j.optInt("summarizedCount")
            j.optJSONArray("picks")?.let { a -> for (i in 0 until minOf(3, a.length())) picks[i] = a.optInt(i) }
            j.optJSONArray("log")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    log.add(LogEntry(o.optLong("t"), o.optBoolean("u"), o.optString("x"), o.optBoolean("a")))
                }
            }
            summarizedCount = summarizedCount.coerceIn(0, log.size)
        }
    }
}

/**
 * キャラ別の会話ログ・記憶を保存する（アプリ内の files/chars/ に JSON）。
 * 画面に映っている分しか読めないので、見えた順にログへ足していく。
 */
class CharStore(context: Context) {
    private val dir = File(context.filesDir, "chars").apply { mkdirs() }
    private val cache = HashMap<String, CharMemory>()

    private fun fileOf(name: String): File {
        val safe = name.ifBlank { UNKNOWN }.replace(Regex("""[\\/:*?"<>|\s]"""), "_").take(40)
        return File(dir, "${safe}_${name.hashCode().toUInt().toString(16)}.json")
    }

    @Synchronized
    fun load(name: String): CharMemory = cache.getOrPut(name) {
        val f = fileOf(name)
        if (f.exists()) runCatching { CharMemory.fromJson(JSONObject(f.readText())) }.getOrNull() ?: CharMemory(name)
        else CharMemory(name)
    }

    @Synchronized
    fun save(m: CharMemory) {
        // ログは最大 MAX_LOG 件（古い分は要約済みなら捨てる）
        if (m.log.size > MAX_LOG) {
            val drop = minOf(m.log.size - MAX_LOG, m.summarizedCount)
            if (drop > 0) { repeat(drop) { m.log.removeAt(0) }; m.summarizedCount -= drop }
        }
        val f = fileOf(m.name)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(m.toJson().toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun list(): List<CharMemory> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { CharMemory.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .map { cache[it.name] ?: it }
            .sortedByDescending { it.log.lastOrNull()?.time ?: 0 }

    @Synchronized
    fun delete(name: String) { cache.remove(name); fileOf(name).delete() }

    /**
     * 画面に見えているメッセージをログに足す。新しく足した件数を返す。
     * @param appSent アプリが送った文（正規化済み）。自分の口調の手本から除くため
     */
    @Synchronized
    fun merge(m: CharMemory, screen: List<ChatMessage>, appSent: Set<String>): Int {
        if (screen.isEmpty()) return 0
        val log = m.log
        var start = 0
        if (log.isNotEmpty()) {
            // 画面のどこまでがログの末尾と一致するか（大きい方から探す）
            var found = -1
            for (j in screen.size downTo 1) {
                val k = minOf(j, 4, log.size)
                var ok = true
                for (i in 1..k) {
                    val s = screen[j - i]; val l = log[log.size - i]
                    if (s.fromUser != l.fromUser || norm(s.text) != norm(l.text)) { ok = false; break }
                }
                if (ok) { found = j; break }
            }
            if (found >= 0) start = found
            else {
                // 一致しない: 上にスクロールして古い所を見ているだけなら足さない
                val known = log.takeLast(400).map { norm(it.text) }.toHashSet()
                if (screen.all { norm(it.text) in known }) return 0
                // 最後の1件が書き途中で伸びた場合は置き換える
                val lastLog = log.last()
                val first = screen.indexOfFirst { it.fromUser == lastLog.fromUser && norm(it.text).startsWith(norm(lastLog.text)) }
                if (first >= 0 && log.size > m.summarizedCount) { log.removeAt(log.size - 1); start = first }
            }
        }
        val now = System.currentTimeMillis()
        var added = 0
        for (s in screen.drop(start)) {
            log.add(LogEntry(now, s.fromUser, s.text, s.fromUser && norm(s.text) in appSent))
            added++
        }
        if (added > 0) save(m)
        return added
    }

    /** 自分の口調の手本（自分で打った文を優先し、足りなければ選んで送った文） */
    fun styleExamples(m: CharMemory, n: Int): List<String> {
        val mine = m.log.filter { it.fromUser }
        val typed = mine.filter { !it.byApp }.map { it.text }
        val list = if (typed.size >= n) typed else typed + mine.filter { it.byApp }.map { it.text }
        return list.distinct().takeLast(n)
    }

    fun pickHint(m: CharMemory): String {
        val total = m.picks.sum()
        if (total < 5) return ""
        val labels = listOf("1（寄り添う）", "2（質問・話を広げる）", "3（遊び心）")
        val best = m.picks.indices.maxBy { m.picks[it] }
        val pct = m.picks[best] * 100 / total
        return if (pct >= 45) "ユーザーは候補${labels[best]}系を選ぶことが多い（$pct%）。その方向の案を特に良くする。" else ""
    }

    fun exportText(m: CharMemory): String = buildString {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
        appendLine("■ ${m.name.ifBlank { "（名前不明）" }} との会話ログ（${m.log.size}件）")
        if (m.summary.isNotBlank()) { appendLine(); appendLine("【記憶】"); appendLine(m.summary) }
        appendLine()
        for (e in m.log) {
            val who = if (e.fromUser) (if (e.byApp) "自分(アプリ)" else "自分") else m.name.ifBlank { "キャラ" }
            appendLine("[${fmt.format(Date(e.time))}] $who: ${e.text}")
        }
    }

    companion object {
        const val UNKNOWN = "_unknown"
        const val MAX_LOG = 3000
        fun norm(s: String) = s.replace(Regex("""[\s　]"""), "")
    }
}
