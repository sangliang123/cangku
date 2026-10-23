package com.baiyun.pet

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 支持「最大高度」的 RecyclerView。
 *
 * 原生 RecyclerView 没有 android:maxHeight（那是 ImageView/TextView 的属性），
 * 所以这里在 onMeasure 里把高度上限换成 AT_MOST 的 MeasureSpec：
 * 消息少时自适应内容高度，消息多时最高就是 [maxHeightPx]（需求：200dp）。
 *
 * 不设这个上限的话，聊得越久气泡越高，最后会顶满整个屏幕。
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 最大高度（像素），0 表示不限制 */
    var maxHeightPx: Int = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (maxHeightPx > 0) {
            val limited = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthSpec, limited)
        } else {
            super.onMeasure(widthSpec, heightSpec)
        }
    }
}
