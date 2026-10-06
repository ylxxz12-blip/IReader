package com.example.zreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** 文本选择后的操作弹窗：高亮取色 + 笔记/复制/全选/删除 */
@SuppressLint("ClickableViewAccessibility")
class SelectionPopup(
    context: Context,
    private val onDismiss: () -> Unit,
    private val onColor: (Int) -> Unit,
    private val onNote: () -> Unit,
    private val onCopy: () -> Unit,
    private val onSelectAll: () -> Unit,
    private val onDelete: () -> Unit,
) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val popup: PopupWindow
    private val contentView: LinearLayout

    init {
        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE2B2B30.toInt())
                cornerRadius = dp(10f).toFloat()
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }

        // 第一行：高亮颜色
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ReaderSkins.HIGHLIGHTS.indices.forEach { idx ->
            val circle = View(context)
            val size = dp(26f)
            circle.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(6f); marginEnd = dp(6f)
            }
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ReaderSkins.highlightOpaque(idx))
                setStroke(dp(1f), 0x66FFFFFF)
            }
            circle.setOnClickListener { onColor(idx) }
            colorRow.addView(circle)
        }
        contentView.addView(colorRow)

        // 分割线
        val divider = View(context)
        divider.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1f),
        ).apply { topMargin = dp(6f); bottomMargin = dp(2f) }
        divider.setBackgroundColor(0x33FFFFFF)
        contentView.addView(divider)

        // 第二行：文字操作
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actions = listOf("笔记" to onNote, "复制" to onCopy, "全选" to onSelectAll, "删除" to onDelete)
        for ((label, action) in actions) {
            val tv = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                setOnClickListener { action() }
            }
            actionRow.addView(tv)
        }
        contentView.addView(actionRow)

        popup = PopupWindow(contentView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isOutsideTouchable = true
            // 必须设置背景，点击弹窗外部才会 dismiss
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(6f).toFloat()
            setOnDismissListener { onDismiss() }
        }
    }

    val isShowing: Boolean get() = popup.isShowing

    fun show(anchor: View, selBounds: RectF) {
        val (x, y) = computePosition(anchor, selBounds)
        popup.width = x.second
        popup.height = y.second
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x.first, y.first)
    }

    fun update(anchor: View, selBounds: RectF) {
        if (!popup.isShowing) return
        val (x, y) = computePosition(anchor, selBounds)
        popup.update(x.first, y.first, x.second, y.second)
    }

    private fun computePosition(anchor: View, selBounds: RectF): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = contentView.measuredWidth
        val h = contentView.measuredHeight
        val anchorW = anchor.width
        val x = (selBounds.centerX() - w / 2f).toInt().coerceIn(dp(6f), (anchorW - w - dp(6f)).coerceAtLeast(dp(6f)))
        var y = (selBounds.top - h - dp(12f)).toInt()
        if (y < dp(48f)) {
            y = (selBounds.bottom + dp(12f)).toInt().coerceAtMost(anchor.height - h - dp(8f))
        }
        return (x to w) to (y to h)
    }

    fun dismiss() {
        popup.dismiss()
    }
}
