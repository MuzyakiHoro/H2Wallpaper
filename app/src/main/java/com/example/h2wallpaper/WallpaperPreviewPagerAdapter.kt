package com.example.h2wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class WallpaperPreviewPagerAdapter(
    private val context: Context,
    private var originalBitmap: Bitmap?,
    private var page1BackgroundColor: Int,
    private var page1ImageHeightRatio: Float // 新增：用于控制第一页图片高度的比例
) : RecyclerView.Adapter<WallpaperPreviewPagerAdapter.PreviewPageViewHolder>() {

    private val NUM_PAGES = 3 // 假设我们预览3页，你可以根据需要调整

    companion object {
        private const val PAGE_TYPE_FIRST = 0
        private const val PAGE_TYPE_OTHER = 1
    }

    // 更新所有数据的方法
    fun updateData(newBitmap: Bitmap?, newColor: Int, newHeightRatio: Float) {
        var dataChanged = false
        if (originalBitmap != newBitmap) {
            originalBitmap = newBitmap
            dataChanged = true
        }
        if (page1BackgroundColor != newColor) {
            page1BackgroundColor = newColor
            dataChanged = true
        }
        if (page1ImageHeightRatio != newHeightRatio) {
            page1ImageHeightRatio = newHeightRatio
            dataChanged = true
        }

        if (dataChanged) {
            notifyDataSetChanged() // 通知适配器数据已更新
        }
    }

    // 仅更新第一页图片高度比例并重绘第一页 (如果可见)
    fun updatePage1ImageHeightRatio(newHeightRatio: Float) {
        if (this.page1ImageHeightRatio != newHeightRatio) {
            this.page1ImageHeightRatio = newHeightRatio
            // 确保只在第一页可见时才尝试重绘特定项，否则可能导致不必要的计算
            // 或者更简单地，总是通知数据集变化，让ViewPager2处理
            // notifyDataSetChanged()
            notifyItemChanged(0) // 刷新第一项 (position 0)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) PAGE_TYPE_FIRST else PAGE_TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewPageViewHolder {
        val inflater = LayoutInflater.from(context)
        return if (viewType == PAGE_TYPE_FIRST) {
            val view = inflater.inflate(R.layout.item_wallpaper_preview_page1, parent, false)
            PreviewPage1ViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_wallpaper_preview_page_other, parent, false)
            PreviewPageOtherViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: PreviewPageViewHolder, position: Int) {
        if (originalBitmap == null && position == 0) { // 对于第一页，即使没图也显示背景色
            (holder as? PreviewPage1ViewHolder)?.bindEmpty(page1BackgroundColor, page1ImageHeightRatio)
            return
        } else if (originalBitmap == null) { // 其他页没图则清空
            holder.clear()
            return
        }

        when (holder) {
            is PreviewPage1ViewHolder -> holder.bind(originalBitmap!!, page1BackgroundColor, page1ImageHeightRatio)
            is PreviewPageOtherViewHolder -> holder.bind(originalBitmap!!)
        }
    }

    override fun getItemCount(): Int = NUM_PAGES


    // --- ViewHolders ---
    abstract class PreviewPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun clear()
    }

    class PreviewPage1ViewHolder(itemView: View) : PreviewPageViewHolder(itemView) {
        private val topImageView: ImageView = itemView.findViewById(R.id.previewPage1_topImageView)
        private val bottomColorView: View = itemView.findViewById(R.id.previewPage1_bottomColorView)

        fun bind(bitmap: Bitmap, backgroundColor: Int, heightRatio: Float) {
            itemView.post { // 使用 post 确保在 itemView 布局完成后获取正确尺寸
                val totalHeight = itemView.height
                if (totalHeight == 0) return@post // 避免在布局未完成时计算

                val topPartHeight = (totalHeight * heightRatio).toInt()

                val topParams = topImageView.layoutParams
                topParams.height = topPartHeight
                topImageView.layoutParams = topParams
                topImageView.setImageBitmap(bitmap)
                topImageView.visibility = View.VISIBLE

                // bottomColorView 作为整个页面的背景，然后 topImageView 覆盖其上部
                bottomColorView.setBackgroundColor(backgroundColor)
                val bottomViewParams = bottomColorView.layoutParams
                bottomViewParams.height = totalHeight //确保背景色视图总是铺满
                bottomColorView.layoutParams = bottomViewParams
            }
        }

        fun bindEmpty(backgroundColor: Int, heightRatio: Float) {
            itemView.post {
                val totalHeight = itemView.height
                if (totalHeight == 0) return@post

                val topPartHeight = (totalHeight * heightRatio).toInt()

                val topParams = topImageView.layoutParams
                topParams.height = topPartHeight
                topImageView.layoutParams = topParams
                topImageView.setImageBitmap(null) // 清空图片
                topImageView.setBackgroundColor(Color.DKGRAY) // 可以给图片区域一个默认深色背景
                topImageView.visibility = View.VISIBLE


                bottomColorView.setBackgroundColor(backgroundColor)
                val bottomViewParams = bottomColorView.layoutParams
                bottomViewParams.height = totalHeight
                bottomColorView.layoutParams = bottomViewParams
            }
        }

        override fun clear() {
            topImageView.setImageBitmap(null)
            topImageView.setBackgroundColor(Color.TRANSPARENT) // 或者一个占位色
            topImageView.visibility = View.INVISIBLE // 或者 GONE
            bottomColorView.setBackgroundColor(Color.LTGRAY) // 默认背景色
        }
    }

    class PreviewPageOtherViewHolder(itemView: View) : PreviewPageViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.previewPageOther_imageView)

        fun bind(bitmap: Bitmap) {
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
        }

        override fun clear() {
            imageView.setImageBitmap(null)
            imageView.visibility = View.INVISIBLE // 或者 GONE
        }
    }
}