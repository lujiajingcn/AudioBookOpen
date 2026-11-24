package com.audiobook.audiobookplayer.network

import android.util.Log
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

private const val TAG = "TaskProgress"

class TaskProgressListener(
    private val onProgressUpdate: (Int, Int, String) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit
) : EventSourceListener() {

    /**
     * 本条流是否已经【正常收尾】（收到 complete 或 error）。
     *
     * 必须的三个原因：
     * 1. okhttp-sse 的 `RealEventSource` 在 `onEvent` 里调用 `cancel()`，会让下一次读取抛异常
     *    并走到 `onFailure` —— 即「任务成功」也会被当成「连接失败」上报一次假错误。
     *    （`RealEventSource.kt:73-81`：`onEvent` 抛异常 → `onFailure`，且之后直接 return，
     *     不再走 `onClosed`。）
     * 2. 服务端下发 `error` 事件时若没有对应分支，会被 `when` 直接忽略，
     *    任务实际已失败但客户端永远停在「进行中」，只能靠杀进程重置。
     * 3. 服务端正常关闭流（`onClosed`）而此前没有 complete/error 时，
     *    `onClosed` 是空实现 → 任务状态未知却无任何降级动作。
     *
     * 多线程可见：`onEvent` `onFailure` `onClosed` 均由 OkHttp 的读线程回调，
     * 这里用 [@Volatile] 保证状态对其它线程立即可见。
     */
    @Volatile
    private var finished = false

    override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
        super.onOpen(eventSource, response)
    }

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        super.onEvent(eventSource, id, type, data)

        try {
            val json = JSONObject(data)
            
            when (type) {
                "heartbeat" -> {
                    return
                }
                "progress" -> {
                    val completed = json.getInt("completedSegments")
                    val total = json.getInt("totalSegments")
                    val status = json.getString("status")
                    onProgressUpdate(completed, total, status)
                }
                "complete" -> {
                    // 先置 finished 再回调：onComplete() 内部可能触发刷新/关闭界面，
                    // 若顺序颠倒，cancel() 引发的 onFailure 会抢在标记之前读到旧值，
                    // 仍然弹出一条「连接失败」的假错误。
                    finished = true
                    onComplete()
                    eventSource.cancel()
                }
                "error" -> {
                    val message = json.optString("message")
                        .takeIf { it.isNotBlank() } ?: "服务端处理任务失败"
                    finished = true
                    onError(message)
                    eventSource.cancel()
                }
                "init" -> {
                    val completed = json.getInt("completedSegments")
                    val total = json.getInt("totalSegments")
                    val status = json.getString("status")
                    onProgressUpdate(completed, total, status)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSE parse error: ${e.message}", e)
        }
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
        super.onFailure(eventSource, t, response)
        // complete/error 分支里主动 cancel() 会走到这里，那不是失败，静默忽略。
        if (finished) return
        val errorMsg = t?.message ?: response?.message ?: "连接失败"
        onError(errorMsg)
    }

    override fun onClosed(eventSource: EventSource) {
        super.onClosed(eventSource)
        // 流被关闭但从未收到 complete/error：任务状态未知。
        // 必须上报，否则上层会一直等一个永远不会来的事件，任务永久卡在「进行中」。
        if (!finished) {
            finished = true
            onError("连接已关闭")
        }
    }
}

object SseClient {
    // 复用 RetrofitClient.sseClient：既拿到动态 base URL / 401 / 402 三件套，
    // 也继承它的超时分级（见 RetrofitClient 的 SSE_* 常量）——连接超时过长会让
    // 服务器不可达时界面挂起很久才报错。
    fun connectToTaskStream(baseUrl: String, taskId: Long, listener: TaskProgressListener): EventSource {
        val url = "$baseUrl/api/tts/stream/$taskId"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()

        val eventSourceFactory = EventSources.createFactory(RetrofitClient.sseClient)
        return eventSourceFactory.newEventSource(request, listener)
    }
}

