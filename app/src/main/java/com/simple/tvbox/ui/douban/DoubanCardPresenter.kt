package com.simple.tvbox.ui.douban

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.simple.tvbox.R
import com.simple.tvbox.util.ImageLoader

/**
 * 豆瓣卡片 Presenter：海报 + 标题 + 副标题
 *
 * 跟 HomeFragment 的 PosterCardPresenter 类似，但独立实现以便定制：
 *  - 副标题加豆瓣评分标签
 *  - placeholder 时只显示标题
 */
class DoubanCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(220, 330)
            setPadding(10, 10, 10, 10)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_card)
        }
        val poster = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 230)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(ColorDrawable(Color.rgb(38, 42, 52)))
            setBackgroundColor(Color.rgb(38, 42, 52))
        }
        val title = TextView(parent.context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4, 8, 4, 0)
        }
        val subTitle = TextView(parent.context).apply {
            textSize = 11f
            setTextColor(Color.rgb(255, 200, 80))   // 豆瓣评分用偏金黄色
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4, 4, 4, 0)
        }
        container.addView(poster)
        container.addView(title)
        container.addView(subTitle)
        return DoubanViewHolder(container, poster, title, subTitle)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as DoubanCard
        val vh = viewHolder as DoubanViewHolder
        vh.title.text = card.title
        vh.subTitle.text = card.subTitle ?: ""
        vh.subTitle.setTextColor(
            if (card.subTitle?.startsWith("豆瓣") == true) Color.rgb(255, 200, 80)
            else Color.LTGRAY
        )
        ImageLoader.load(card.poster, vh.poster)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as DoubanViewHolder
        vh.poster.setImageDrawable(ColorDrawable(Color.rgb(38, 42, 52)))
        vh.title.text = ""
        vh.subTitle.text = ""
    }

    private class DoubanViewHolder(
        view: android.view.View,
        val poster: ImageView,
        val title: TextView,
        val subTitle: TextView
    ) : ViewHolder(view)
}