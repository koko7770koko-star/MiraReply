package com.example.mirareply

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class ChatMessage(val text: String, val fromUser: Boolean, val top: Int, val bottom: Int = top)

data class ChatScreen(
    val messages: List<ChatMessage>,
    val input: AccessibilityNodeInfo?,
    val title: String = "",          // 画面上部のキャラ名らしき文字（取れなければ空）
)

/**
 * 画面のアクセシビリティ情報からチャットの吹き出しを読み取る。
 * MiraiMind の内部構造は公開されていないため、
 *   ・一番大きいスクロール領域 = メッセージ一覧
 *   ・吹き出しが右寄り = 自分、左寄り = キャラクター、中央寄せ = システム表示（無視）
 * という見た目の位置で判定する。うまくいかない場合は設定画面で View ID を指定する。
 */
class ChatReader(private val prefs: Prefs) {

    fun read(root: AccessibilityNodeInfo, screenWidth: Int): ChatScreen {
        val input = findInput(root)
        val list = findMessageList(root)
        val listRect = Rect().also { (list ?: root).getBoundsInScreen(it) }
        val inputTop = input?.let { Rect().also { r -> it.getBoundsInScreen(r) }.top } ?: Int.MAX_VALUE
        val ignore = prefs.ignoreSet()
        val minSide = (screenWidth * 0.04).toInt()

        val texts = ArrayList<Pair<String, Rect>>()
        collectTexts(list ?: root, texts, depth = 0)

        val msgs = texts
            .filter { (_, r) -> r.top >= listRect.top - 4 && r.bottom <= minOf(listRect.bottom, inputTop) + 4 }
            .filter { (t, _) -> !isNoise(t) && t.trim() !in ignore }
            .mapNotNull { (t, r) ->
                val leftGap = r.left - listRect.left
                val rightGap = listRect.right - r.right
                // 左右の余白がほぼ同じで両側に余白がある短い表示 = 日付・システム表示 → 無視
                val centered = t.trim().length <= 20 && leftGap > minSide && rightGap > minSide &&
                    kotlin.math.abs(leftGap - rightGap) < screenWidth * 0.06 && r.width() < screenWidth * 0.75
                if (centered) return@mapNotNull null
                // 右側の余白が左側よりはっきり小さい → 自分の吹き出し
                val fromUser = rightGap + screenWidth * 0.05 < leftGap
                ChatMessage(t.trim(), fromUser, r.top, r.bottom)
            }
            .sortedBy { it.top }

        val title = prefs.charNameOverride.ifBlank { findTitle(root, listRect.top) }
        return ChatScreen(mergeAdjacent(dedupe(msgs)), input, title)
    }

    /** 親子ノードが同じ文字を持っている場合の重複を除く */
    private fun dedupe(msgs: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        for (m in msgs) {
            val dup = out.any { it.text == m.text && kotlin.math.abs(it.top - m.top) < 30 }
            if (!dup) out.add(m)
        }
        return out
    }

    /** 同じ側に連続し、すき間がほとんどない断片（1つの吹き出しが複数のTextViewに分かれている場合）をまとめる */
    private fun mergeAdjacent(msgs: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        for (m in msgs) {
            val last = out.lastOrNull()
            if (last != null && last.fromUser == m.fromUser && m.top - last.bottom < 12) {
                out[out.size - 1] = last.copy(text = last.text + "\n" + m.text, bottom = maxOf(last.bottom, m.bottom))
            } else out.add(m)
        }
        return out
    }

    /** メッセージ一覧より上にある、いちばん大きい文字（＝ヘッダーのキャラ名）を探す */
    private fun findTitle(root: AccessibilityNodeInfo, listTop: Int): String {
        var best = ""
        var bestH = 0
        walk(root) { n ->
            val t = n.text?.toString()?.trim() ?: return@walk
            if (t.isEmpty() || t.length > 24 || n.isEditable || !n.isVisibleToUser) return@walk
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.bottom > listTop + 4 || r.top < 0) return@walk
            if (isNoise(t)) return@walk
            if (r.height() > bestH) { bestH = r.height(); best = t }
        }
        return best
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>, depth: Int) {
        if (depth > 40) return
        if (!node.isVisibleToUser) return
        if (node.isEditable) return
        val cls = node.className?.toString() ?: ""
        // ボタン類は吹き出しではない
        val isButtonLike = cls.endsWith("Button") || cls.endsWith("ImageView")
        val text = node.text?.toString()
        if (!isButtonLike && !text.isNullOrBlank()) {
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out.add(text to r)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectTexts(c, out, depth + 1)
        }
    }

    private val timeRegex = Regex("""^\s*(\d{1,2}[:：]\d{2}|\d{1,2}/\d{1,2}.*|昨日.*|今日.*|午前.*|午後.*)\s*$""")

    private fun isNoise(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return true
        if (timeRegex.matches(s)) return true
        // 既読表示やUIラベルなど
        if (s in UI_WORDS) return true
        // 「入力中…」などの表示
        if (s.endsWith("入力中…") || s.endsWith("入力中...") || s.matches(Regex("""^[.・…]+$"""))) return true
        return false
    }

    fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (prefs.inputViewId.isNotBlank()) {
            root.findAccessibilityNodeInfosByViewId(prefs.inputViewId).firstOrNull()?.let { return it }
        }
        return findFirst(root) { it.isEditable && it.isVisibleToUser }
    }

    private fun findMessageList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (prefs.listViewId.isNotBlank()) {
            root.findAccessibilityNodeInfosByViewId(prefs.listViewId).firstOrNull()?.let { return it }
        }
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        walk(root) { n ->
            val cls = n.className?.toString() ?: ""
            if (n.isScrollable || cls.contains("RecyclerView") || cls.contains("ListView")) {
                val r = Rect(); n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
        }
        return best
    }

    /** 送信ボタンを探す（ID指定 → 文字/説明に「送信」 → 入力欄の右側のクリック可能な要素） */
    fun findSendButton(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findSendCandidates(root, input).firstOrNull()

    /**
     * 送信ボタンらしいものを、可能性の高い順に返す。
     * 押せる（clickable）でなくても候補に入れる（タップ操作で押すため）。
     */
    fun findSendCandidates(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun add(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.isEmpty) return
            if (out.none { o -> Rect().also { o.getBoundsInScreen(it) } == r }) out.add(n)
        }
        if (prefs.sendButtonId.isNotBlank()) {
            root.findAccessibilityNodeInfosByViewId(prefs.sendButtonId).forEach { add(it) }
        }
        val words = listOf("送信", "send", "Send", "SEND", "発送", "送る")
        walk(root) { n ->
            if (!n.isVisibleToUser || n.isEditable) return@walk
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val txt = n.text?.toString().orEmpty()
            if (id.contains("send") || words.any { w -> desc.contains(w) || txt == w }) add(n)
        }
        if (input != null) {
            val inRect = Rect(); input.getBoundsInScreen(inRect)
            // 入力欄と同じ行で、入力欄の右側（または右端寄り）にある小さめの部品
            val row = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
            walk(root) { n ->
                if (!n.isVisibleToUser || n.isEditable) return@walk
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.isEmpty || r.width() > inRect.width() / 2 || r.height() > inRect.height() * 3) return@walk
                val sameRow = r.centerY() in (inRect.top - 40)..(inRect.bottom + 40)
                if (sameRow && r.centerX() > inRect.centerX() + inRect.width() / 4) {
                    row.add((kotlin.math.abs(r.left - inRect.right) - if (n.isClickable) 50 else 0) to n)
                }
            }
            row.sortedBy { it.first }.forEach { add(it.second) }
        }
        return out
    }

    fun clickableParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 5) {
            if (cur.isClickable) return cur
            cur = cur.parent; i++
        }
        return null
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent; i++
        }
        return n
    }

    companion object {
        private val UI_WORDS = setOf("既読", "送信", "Send", "…", "...", "コピー", "再生成", "通報", "共有", "いいね")

        fun walk(node: AccessibilityNodeInfo, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
            if (depth > 40) return
            f(node)
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                walk(c, depth + 1, f)
            }
        }

        fun findFirst(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            var found: AccessibilityNodeInfo? = null
            walk(node) { if (found == null && pred(it)) found = it }
            return found
        }

        /** 調整用：画面構造をテキストに書き出す */
        fun dump(root: AccessibilityNodeInfo): String {
            val sb = StringBuilder()
            fun rec(n: AccessibilityNodeInfo, d: Int) {
                if (d > 40) return
                val r = Rect(); n.getBoundsInScreen(r)
                sb.append("  ".repeat(d))
                    .append(n.className?.toString()?.substringAfterLast('.'))
                    .append(" id=").append(n.viewIdResourceName ?: "-")
                    .append(" [").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append(']')
                if (n.isClickable) sb.append(" clickable")
                if (n.isEditable) sb.append(" editable")
                if (n.isScrollable) sb.append(" scrollable")
                n.text?.let { sb.append(" text=\"").append(it.toString().replace("\n", "\\n").take(80)).append('"') }
                n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().take(40)).append('"') }
                sb.append('\n')
                for (i in 0 until n.childCount) n.getChild(i)?.let { rec(it, d + 1) }
            }
            rec(root, 0)
            return sb.toString()
        }
    }
}
