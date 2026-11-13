package com.example.mirareply

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * チャット画面の上に重ねて表示するパネル。
 * アクセシビリティサービスの TYPE_ACCESSIBILITY_OVERLAY を使うので
 * 「他のアプリの上に重ねて表示」の権限は不要。
 */
class OverlayPanel(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCandidateTapped(index: Int, text: String)
        /** 長押し: 送信せず入力欄に入れるだけ（自分で直してから送る用） */
        fun onCandidateLongPressed(index: Int, text: String)
        fun onRegenerate()
        fun onToggleAutoSend()
        fun onCancelAutoSend()
        fun onDumpScreen()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = context.resources.displayMetrics
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()

    private val accent = Color.parseColor("#7C4DFF")

    // ---- 小さい丸ボタン（ドラッグで移動、タップでパネル開閉） ----
    private val bubble = TextView(context).apply {
        text = "返"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        elevation = dp(6).toFloat()
    }
    private val bubbleParams = baseParams(dp(48), dp(48)).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(8); y = (dm.heightPixels * 0.35).toInt()
    }

    // ---- 候補パネル ----
    private val status = TextView(context).apply {
        setTextColor(Color.parseColor("#555555"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    private val closeBtn = smallButton("✕") { setPanelVisible(false) }
    private val candidateViews = List(3) { i ->
        TextView(context).apply {
            setTextColor(Color.parseColor("#222222"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#F1ECFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { listener.onCandidateTapped(i, candidates.getOrElse(i) { "" }) }
            setOnLongClickListener { listener.onCandidateLongPressed(i, candidates.getOrElse(i) { "" }); true }
            visibility = View.GONE
        }
    }
    private var candidates: List<String> = emptyList()
    private val hint = TextView(context).apply {
        text = "タップ=送信 / 長押し=入力欄に入れるだけ"
        setTextColor(Color.parseColor("#999999"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(4), 0, 0)
        visibility = View.GONE
    }
    private val autoBtn = smallButton("自動会話: OFF") { listener.onToggleAutoSend() }
    private val stopBtn = smallButton("停止") { listener.onCancelAutoSend() }.apply { visibility = View.GONE }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
        elevation = dp(8).toFloat()

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status); addView(closeBtn)
        })
        candidateViews.forEach { addView(it) }
        addView(hint)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(smallButton("再生成") { listener.onRegenerate() })
            addView(autoBtn)
            addView(stopBtn)
            addView(smallButton("画面構造") { listener.onDumpScreen() })
        })
    }
    private val panelParams = baseParams((dm.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(56)
    }

    private var bubbleAdded = false
    private var panelAdded = false
    private var panelVisible = false

    init { setupBubbleTouch() }

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun smallButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouch() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        bubbleParams.x = (startX - dx).toInt() // gravity END なので左右反転
                        bubbleParams.y = (startY + dy).toInt()
                        if (bubbleAdded) wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) setPanelVisible(!panelVisible)
            }
            true
        }
    }

    // ---------- 外から呼ぶ操作（すべてメインスレッドで） ----------

    fun show() {
        if (!bubbleAdded) { wm.addView(bubble, bubbleParams); bubbleAdded = true }
        if (panelVisible && !panelAdded) { wm.addView(panel, panelParams); panelAdded = true }
    }

    fun hide() {
        if (bubbleAdded) { wm.removeView(bubble); bubbleAdded = false }
        if (panelAdded) { wm.removeView(panel); panelAdded = false }
    }

    fun setPanelVisible(v: Boolean) {
        panelVisible = v
        if (v && bubbleAdded && !panelAdded) { wm.addView(panel, panelParams); panelAdded = true }
        if (!v && panelAdded) { wm.removeView(panel); panelAdded = false }
    }

    fun setStatus(text: String) { status.text = text }

    fun setCandidates(list: List<String>) {
        candidates = list
        hint.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        candidateViews.forEachIndexed { i, tv ->
            if (i < list.size) { tv.text = "${i + 1}. ${list[i]}"; tv.visibility = View.VISIBLE }
            else tv.visibility = View.GONE
        }
    }

    fun setAutoSend(on: Boolean, turns: Int = 0, max: Int = 0) {
        autoBtn.text = when {
            !on -> "自動会話: OFF"
            max > 0 -> "自動会話: ON $turns/$max"
            else -> "自動会話: ON $turns"
        }
        bubble.text = if (on) "自" else "返"
        (bubble.background as? GradientDrawable)?.setColor(if (on) Color.parseColor("#E65100") else accent)
    }

    /** 自動会話中に画面が消えないようにする（オーバーレイに KEEP_SCREEN_ON を付ける） */
    fun setKeepScreenOn(on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val newFlags = if (on) bubbleParams.flags or f else bubbleParams.flags and f.inv()
        if (newFlags == bubbleParams.flags) return
        bubbleParams.flags = newFlags
        if (bubbleAdded) wm.updateViewLayout(bubble, bubbleParams)
    }

    fun setCountdownVisible(v: Boolean) { stopBtn.visibility = if (v) View.VISIBLE else View.GONE }
}
