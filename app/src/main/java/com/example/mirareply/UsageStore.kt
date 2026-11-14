package com.example.mirareply

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** APIの使用量（トークン数）を月ごとに記録し、概算料金を出す */
class UsageStore(context: Context) {
    private val sp = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val prefs = Prefs(context)

    private fun month(d: Date = Date()) = SimpleDateFormat("yyyy-MM", Locale.US).format(d)

    @Synchronized
    fun add(provider: String, input: Long, output: Long) {
        val m = month()
        sp.edit()
            .putLong("$m|$provider|in", sp.getLong("$m|$provider|in", 0) + input)
            .putLong("$m|$provider|out", sp.getLong("$m|$provider|out", 0) + output)
            .putLong("$m|$provider|calls", sp.getLong("$m|$provider|calls", 0) + 1)
            .apply()
    }

    data class Row(val provider: String, val calls: Long, val input: Long, val output: Long, val usd: Double)

    fun rows(m: String = month()): List<Row> =
        listOf(Prefs.ENGINE_CLAUDE, Prefs.ENGINE_OPENAI, Prefs.ENGINE_GEMINI).map { p ->
            val i = sp.getLong("$m|$p|in", 0); val o = sp.getLong("$m|$p|out", 0)
            val (pi, po) = prefs.price(p)
            Row(p, sp.getLong("$m|$p|calls", 0), i, o, i / 1e6 * pi + o / 1e6 * po)
        }

    fun monthUsd(): Double = rows().sumOf { it.usd }

    fun costOf(provider: String, input: Long, output: Long): Double {
        val (pi, po) = prefs.price(provider)
        return input / 1e6 * pi + output / 1e6 * po
    }

    fun report(): String = buildString {
        val m = month()
        appendLine("$m の使用量（概算）")
        val rows = rows(m).filter { it.calls > 0 }
        if (rows.isEmpty()) { append("まだ使っていません"); return@buildString }
        for (r in rows) {
            val name = Prefs.ENGINE_LABELS[r.provider]!!.substringBefore("（")
            appendLine("・$name: ${r.calls}回 入力${r.input / 1000}k 出力${r.output / 1000}k → \$${"%.3f".format(r.usd)}（約${(r.usd * 155).toInt()}円）")
        }
        append("合計 \$${"%.3f".format(rows.sumOf { it.usd })}")
    }

    fun resetMonth() {
        val m = month()
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("$m|") }.forEach { e.remove(it) }
        e.apply()
    }
}
