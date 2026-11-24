package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.Favorite

/**
 * 我的收藏列表适配器，支持点击查看与删除。
 */
class FavoritesAdapter(
    private val onItemClick: (Favorite) -> Unit,
    private val onDeleteClick: (Favorite) -> Unit
) : ListAdapter<Favorite, FavoritesAdapter.FavoriteViewHolder>(FavoriteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return FavoriteViewHolder(view)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBookName: TextView = itemView.findViewById(R.id.tvFavoriteBookName)
        private val tvContent: TextView = itemView.findViewById(R.id.tvFavoriteContent)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteFavorite)

        fun bind(favorite: Favorite) {
            // 显示书名 + 段落号
            tvBookName.text = if (favorite.lineNumber > 0) {
                "${favorite.bookName} · 第${favorite.lineNumber}段"
            } else {
                favorite.bookName
            }
            tvContent.text = favorite.content

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
            btnDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDeleteClick(getItem(position))
                }
            }
        }
    }

    class FavoriteDiffCallback : DiffUtil.ItemCallback<Favorite>() {
        override fun areItemsTheSame(oldItem: Favorite, newItem: Favorite): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Favorite, newItem: Favorite): Boolean {
            return oldItem.bookName == newItem.bookName &&
                    oldItem.lineNumber == newItem.lineNumber &&
                    oldItem.content == newItem.content
        }
    }
}
