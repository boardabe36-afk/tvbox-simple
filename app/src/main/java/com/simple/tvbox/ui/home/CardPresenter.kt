package com.simple.tvbox.ui.home

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.simple.tvbox.R
import com.simple.tvbox.util.ImageLoader

/** 用于顶部"搜索/设置"等按钮的简单 Presenter */
class ActionPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(220, 120)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_action)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 16, 24, 16)
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val action = item as ActionItem
        (viewHolder.view as TextView).text = action.title
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as TextView).text = ""
    }
}

/** 视频卡片 Presenter */
class CardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val container = android.widget.LinearLayout(parent.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(240, 126)
            setPadding(18, 18, 18, 18)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_card)
        }
        val title = TextView(parent.context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subTitle = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(title)
        container.addView(subTitle)
        return CardViewHolder(container, title, subTitle)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as VideoCard
        val vh = viewHolder as CardViewHolder
        vh.title.text = card.title
        vh.subTitle.text = card.subTitle ?: ""
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as CardViewHolder
        vh.title.text = ""
        vh.subTitle.text = ""
    }

    private class CardViewHolder(
        view: android.view.View,
        val title: TextView,
        val subTitle: TextView
    ) : ViewHolder(view)
}

/** 带海报的首页热门影视 Presenter */
class PosterCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val container = android.widget.LinearLayout(parent.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
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
            setTextColor(Color.LTGRAY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4, 4, 4, 0)
        }
        container.addView(poster)
        container.addView(title)
        container.addView(subTitle)
        return PosterViewHolder(container, poster, title, subTitle)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as VideoCard
        val vh = viewHolder as PosterViewHolder
        vh.title.text = card.title
        vh.subTitle.text = card.subTitle ?: ""
        ImageLoader.load(card.poster, vh.poster)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as PosterViewHolder
        vh.poster.setImageDrawable(ColorDrawable(Color.rgb(38, 42, 52)))
        vh.title.text = ""
        vh.subTitle.text = ""
    }

    private class PosterViewHolder(
        view: android.view.View,
        val poster: ImageView,
        val title: TextView,
        val subTitle: TextView
    ) : ViewHolder(view)
}
