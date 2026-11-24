package com.audiobook.audiobookplayer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.CreditTransactionResp
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 积分明细（AdminCredits 台账前端展示）。
 * 拉取 /api/credits/transactions 并逐条展示：类型、变动积分（正负着色）、余额快照、时间。
 */
class CreditLedgerActivity : BaseActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private val items = mutableListOf<CreditTransactionResp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit_ledger)
        recycler = findViewById(R.id.recyclerLedger)
        emptyView = findViewById(R.id.tvLedgerEmpty)
        progress = findViewById(R.id.pbLedger)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = LedgerAdapter(items)

        loadTransactions()
    }

    private fun loadTransactions() {
        val token = AuthManager.token
        if (token == null) {
            showEmpty("请先登录后查看积分明细")
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.creditApi.getMyTransactions(page = 0, size = 50)
                val list = if (resp.isSuccessful) resp.body()?.content ?: emptyList() else emptyList()
                withContext(Dispatchers.Main) {
                    items.clear()
                    items.addAll(list)
                    recycler.adapter?.notifyDataSetChanged()
                    progress.visibility = View.GONE
                    if (items.isEmpty()) showEmpty("暂无积分变动记录") else emptyView.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    showEmpty("加载失败：${e.message}")
                }
            }
        }
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
        recycler.visibility = View.GONE
    }

    private class LedgerAdapter(
        private val items: List<CreditTransactionResp>
    ) : RecyclerView.Adapter<LedgerAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvType: TextView = view.findViewById(R.id.tvTxType)
            val tvCredits: TextView = view.findViewById(R.id.tvTxCredits)
            val tvRemark: TextView = view.findViewById(R.id.tvTxRemark)
            val tvBalanceAfter: TextView = view.findViewById(R.id.tvTxBalanceAfter)
            val tvTime: TextView = view.findViewById(R.id.tvTxTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_credit_transaction, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tx = items[position]
            holder.tvType.text = tx.typeLabel ?: tx.type ?: "变动"
            val credits = tx.credits
            val positive = credits >= 0
            holder.tvCredits.text = (if (positive) "+" else "") + credits
            holder.tvCredits.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (positive) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
            holder.tvRemark.text = tx.remark ?: ""
            holder.tvRemark.visibility = if (tx.remark.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.tvBalanceAfter.text = "变动后余额：${tx.balanceAfter}"
            // 服务端给的是 LocalDateTime 原样串（2026-11-02T11:46:51.239836），
            // 直接贴上屏既长又难读，微秒对用户毫无意义 —— 收成 yyyy-MM-dd HH:mm。
            holder.tvTime.text = formatLedgerTime(tx.createdAt)
        }
    }
}
