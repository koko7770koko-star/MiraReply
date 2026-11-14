package com.example.mirareply

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 不具合調査用の動作ログ（直近500行をメモリに持ち、共有時にファイルへ） */
object DebugLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    @Synchronized
    fun d(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > 500) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    fun save(c: Context): File = File(c.filesDir, "debug_log.txt").also { it.writeText(text()) }

    fun saved(c: Context): String {
        val live = text()
        if (live.isNotBlank()) return live
        val f = File(c.filesDir, "debug_log.txt")
        return if (f.exists()) f.readText() else ""
    }
}
