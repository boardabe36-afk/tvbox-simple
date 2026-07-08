package com.simple.tvbox.ui.home

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
 * 首页"豆瓣海报" Presenter（用于 DoubanPosterCard）。
 *
 * 与 PosterCardPresenter 风格一致，但独立以避免后续单独调整。
 */
class DoubanPosterPresenter : Presenter() {
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
            setTextColor(Color.rgb(255, 200, 80))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4, 4, 4, 0)
        }
        container.addView(poster)
        container.addView(title)
        container.addView(subTitle)
        return DoubanPosterVH(container, poster, title, subTitle)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as DoubanPosterCard
        val vh = viewHolder as DoubanPosterVH
        vh.title.text = card.title
        vh.subTitle.text = card.subTitle ?: ""
        vh.subTitle.setTextColor(
            if (card.subTitle?.startsWith("豆瓣") == true) Color.rgb(255, 200, 80)
            else Color.LTGRAY
        )
        ImageLoader.load(card.poster, vh.poster)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as DoubanPosterVH
        vh.poster.setImageDrawable(ColorDrawable(Color.rgb(38, 42, 52)))
        vh.title.text = ""
        vh.subTitle.text = ""
    }

    private class DoubanPosterVH(
        view: android.view.View,
        val poster: ImageView,
        val title: TextView,
        val subTitle: TextView
    ) : ViewHolder(view)
}