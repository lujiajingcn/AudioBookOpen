package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.audiobook.audiobookplayer.model.StoreBook
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient

/**
 * 书城书籍网格 Adapter：封面（服务端动态生成）+ 书名 + 作者。
 */
class BookStoreAdapter(
    private val onItemClick: (StoreBook) -> Unit
) : ListAdapter<StoreBook, BookStoreAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvAuthor)
        private val tvTags: TextView = itemView.findViewById(R.id.tvTags)

        fun bind(book: StoreBook) {
            tvTitle.text = book.title
            tvAuthor.text = book.author ?: "佚名"

            // 标签：卡片只有一行位置，最多显示 2 个 + 「+N」；没有标签整行 gone
            val tags = book.tags?.filter { it.isNotBlank() }.orEmpty()
            if (tags.isEmpty()) {
                tvTags.visibility = View.GONE
            } else {
                tvTags.visibility = View.VISIBLE
                val shown = tags.take(2).joinToString(" · ")
                val rest = tags.size - 2
                tvTags.text = if (rest > 0) "$shown  +$rest" else shown
            }

            // 封面：服务端动态生成，需要带 JWT
            val coverUrl = RetrofitClient.BASE_URL + "api/store/book/${book.id}/cover"
            val token = AuthManager.token
            ivCover.load(coverUrl) {
                if (token != null) {
                    addHeader("Authorization", "Bearer $token")
                }
                crossfade(true)
            }

            itemView.setOnClickListener { onItemClick(book) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<StoreBook>() {
        override fun areItemsTheSame(oldItem: StoreBook, newItem: StoreBook) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: StoreBook, newItem: StoreBook) = oldItem == newItem
    }
}
