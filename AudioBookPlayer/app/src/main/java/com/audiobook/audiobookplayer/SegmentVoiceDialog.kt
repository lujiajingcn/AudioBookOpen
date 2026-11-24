package com.audiobook.audiobookplayer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 逐段音色绑定对话框（仿 Web Player.vue 的 per-segment voice 编辑器）。
 *
 * 拉取任务的可选音色清单与片段列表（含每段当前绑定音色），逐段用 Spinner 选择预设音色；
 * 点「应用并重新合成」时只把「相对原始值有变化」的段通过
 * POST /api/tts/task/{taskId}/voices 上报，服务端仅对变更段重新合成（与 Web 的 changedVoices 一致）。
 */
class SegmentVoiceDialog(
    context: Context,
    private val taskId: Long
) : Dialog(context) {

    /** 协程作用域绑定到宿主 Activity 生命周期，避免泄漏 */
    private val scope = (context as? AppCompatActivity)?.lifecycleScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 音色清单：占位项（空 id = 沿用克隆参考音频）+ 服务端返回的预设音色
    private val voiceDisplay = mutableListOf<String>()
    private val voiceIds = mutableListOf<String>()

    // 片段列表：初始化时记录 originalVoiceId，应用前用它与 selectedVoiceId 比对算出变更
    private val segments = mutableListOf<SegmentVoiceItem>()

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnApply: Button
    private lateinit var tvHint: TextView

    private val loading = AtomicBoolean(false)
    private val applying = AtomicBoolean(false)

    data class SegmentVoiceItem(
        val segmentNumber: Int,
        val text: String,
        val originalVoiceId: String?,
        var selectedVoiceId: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_segment_voices)
        setCanceledOnTouchOutside(true)

        recyclerView = findViewById(R.id.recyclerViewVoices)
        progressBar = findViewById(R.id.progressBar)
        btnApply = findViewById(R.id.btnApplyVoices)
        tvHint = findViewById(R.id.tvHint)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = SegmentVoiceAdapter()

        btnApply.setOnClickListener { applyVoices() }

        loadData()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadData() {
        if (!loading.compareAndSet(false, true)) return
        progressBar.visibility = View.VISIBLE
        btnApply.isEnabled = false
        scope.launch(Dispatchers.IO) {
            try {
                // 1) 可选音色清单
                val vResp = RetrofitClient.apiService.getVoices()
                if (vResp.isSuccessful) {
                    val list = (vResp.body()?.get("voices") as? List<Map<String, Any>>)
                    if (!list.isNullOrEmpty()) {
                        voiceDisplay.clear()
                        voiceIds.clear()
                        // 占位项：沿用克隆参考音频
                        voiceDisplay.add("（沿用克隆参考音频）")
                        voiceIds.add("")
                        for (m in list) {
                            val id = m["id"] as? String ?: continue
                            val name = (m["name"] as? String) ?: id
                            voiceDisplay.add("$name ($id)")
                            voiceIds.add(id)
                        }
                    }
                }

                // 2) 片段列表（含每段当前 voice）
                val sResp = RetrofitClient.apiService.getTaskSegmentsList(taskId)
                if (sResp.isSuccessful) {
                    val segs = (sResp.body()?.get("segments") as? List<Map<String, Any>>)
                    if (!segs.isNullOrEmpty()) {
                        segments.clear()
                        for (s in segs) {
                            val num = (s["segmentNumber"] as? Number)?.toInt() ?: continue
                            val text = (s["textContent"] as? String) ?: "段落 $num"
                            val voice = s["voice"] as? String?
                            segments.add(SegmentVoiceItem(num, text, voice, voice))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (segments.isEmpty()) {
                        tvHint.text = "未找到可编辑的音频片段（任务可能仍在合成中）"
                    }
                    recyclerView.adapter?.notifyDataSetChanged()
                    btnApply.isEnabled = segments.isNotEmpty()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvHint.text = "加载失败：${e.message}"
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun applyVoices() {
        if (!applying.compareAndSet(false, true)) return
        // 仅收集发生变化的段（与 Web 的 changedVoices 一致），避免无谓重合成
        val changed = mutableMapOf<Int, String?>()
        for (item in segments) {
            if (item.selectedVoiceId != item.originalVoiceId) {
                changed[item.segmentNumber] = item.selectedVoiceId
            }
        }
        if (changed.isEmpty()) {
            Toast.makeText(context, "没有修改，无需重新合成", Toast.LENGTH_SHORT).show()
            applying.set(false)
            return
        }
        btnApply.isEnabled = false
        scope.launch(Dispatchers.IO) {
            try {
                // 与 Web Player.vue 对齐：defaultVoiceId 传 null（保持任务级默认不变由服务端处理），
                // 只上报变更段的映射（value 为 null 表示清除该段预设）。
                val body = mutableMapOf<String, Any?>(
                    "defaultVoiceId" to null,
                    "segmentVoices" to changed
                )
                val resp = RetrofitClient.apiService.updateTaskVoices(taskId, body)
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        Toast.makeText(context, "音色已更新，正在重新合成受影响的片段", Toast.LENGTH_LONG).show()
                        dismiss()
                    } else {
                        btnApply.isEnabled = true
                        val msg = try {
                            org.json.JSONObject(resp.errorBody()?.string() ?: "").optString("message", "应用失败")
                        } catch (_: Exception) { "应用失败（${resp.code()}）" }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnApply.isEnabled = true
                    Toast.makeText(context, "应用失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                applying.set(false)
            }
        }
    }

    private inner class SegmentVoiceAdapter : RecyclerView.Adapter<SegmentVoiceViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SegmentVoiceViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_segment_voice, parent, false)
            return SegmentVoiceViewHolder(v)
        }

        override fun getItemCount(): Int = segments.size

        override fun onBindViewHolder(holder: SegmentVoiceViewHolder, position: Int) {
            val item = segments[position]
            holder.tvSegNo.text = "第${item.segmentNumber}段"
            holder.tvSegText.text = item.text

            // 该段当前绑定的音色可能已经不在清单里（音色下线 / 服务端数据变更）。
            // 这时不能简单落到占位项 0 —— Spinner 在 layout 阶段就会回调 onItemSelected，
            // 会把 selectedVoiceId 改写成 null：用户什么都没改，点「应用」却把所有未知音色的
            // 段全部清空并触发重合成。所以为未知 id 额外补一个显示项，选中它即等于保留原值。
            val currentId = item.selectedVoiceId
            val ids: List<String>
            val display: List<String>
            if (!currentId.isNullOrEmpty() && currentId !in voiceIds) {
                ids = voiceIds + currentId
                display = voiceDisplay + "$currentId（当前音色，已不在清单中）"
            } else {
                ids = voiceIds
                display = voiceDisplay
            }

            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, display)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            holder.spinner.adapter = adapter

            val selIdx = ids.indexOf(currentId ?: "")
            holder.spinner.setSelection(if (selIdx >= 0) selIdx else 0)

            // 注意：setSelection **会**触发 onItemSelected（Spinner 在 layout 阶段就回调一次，
            // 并非只有用户操作才触发），并不是原注释说的「无反馈环风险」。
            // 因此监听器必须按位置映射回上面同一个 [ids]，保证回写的值 = 当前显示项。
            holder.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                    item.selectedVoiceId = if (pos in ids.indices) ids[pos].ifEmpty { null } else null
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
    }

    private class SegmentVoiceViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvSegNo: TextView = v.findViewById(R.id.tvSegNo)
        val tvSegText: TextView = v.findViewById(R.id.tvSegText)
        val spinner: Spinner = v.findViewById(R.id.spinnerVoice)
    }
}
