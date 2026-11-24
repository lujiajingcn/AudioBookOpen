import { ref, onScopeDispose } from 'vue'

/**
 * 参考音频试听统一封装。
 *
 * 试听逻辑内联在各视图会引入三类问题：
 * 1. 组件卸载时未停止/释放（或只 pause 不释放 blob URL），造成内存泄漏；
 * 2. audio.play() 返回的 promise 未捕获，浏览器自动播放策略拒绝时产生未处理的 rejection；
 * 3. 切换目标时的竞态处理各不相同。
 *
 * 该 composable 统一负责：单实例播放器（先停后播防叠播）、请求竞态守卫、
 * play() 拒绝捕获、blob URL 释放，以及组件卸载（effect scope 销毁）时的自动清理。
 *
 * @param fetchAudio 按 id 拉取音频二进制（返回带 data 的 axios 响应）
 * @param onError    拉取/播放失败时的提示回调（通常是 message.error）
 */
export function useAudioPreview(
  fetchAudio: (id: number) => Promise<{ data: BlobPart }>,
  onError?: () => void,
) {
  const previewingId = ref<number | null>(null)
  let audio: HTMLAudioElement | null = null

  /** 停止播放并释放 blob URL（可重复调用） */
  const stop = () => {
    if (audio) {
      audio.pause()
      if (audio.src) URL.revokeObjectURL(audio.src)
      audio.removeAttribute('src')
      audio = null
    }
    previewingId.value = null
  }

  /** 点击切换：同一项再点即停止，其他项先停后播 */
  const toggle = (id: number) => {
    if (previewingId.value === id) {
      stop()
      return
    }
    stop()

    const targetId = id
    previewingId.value = targetId
    fetchAudio(id)
      .then((res) => {
        // 请求期间用户又点了别的项/停止：丢弃本次结果，防止旧音频抢播
        if (previewingId.value !== targetId) return
        // 校验响应是音频而非错误 JSON：试听接口同样可能返回错误 JSON
        const blob = res.data as Blob
        if (blob && typeof blob.type === 'string' && blob.type.includes('json')) {
          return Promise.reject(new Error('试听资源加载失败'))
        }
        const url = URL.createObjectURL(blob)
        const el = new Audio(url)
        audio = el
        el.onended = () => {
          // 所有权校验：只清理自己创建的实例，避免旧实例的回调误清新实例
          if (audio === el) {
            audio = null
          }
          URL.revokeObjectURL(url)
          if (previewingId.value === targetId) {
            previewingId.value = null
          }
        }
        // 浏览器自动播放策略可能拒绝 play()，必须捕获，否则产生未处理的 promise rejection
        el.play().catch(() => {
          // 所有权校验：请求 A 的 play() 异步拒绝时用户可能已切到 B——
          // 无条件置空会误清正在播放的 B（失去引用、无法停止、再点叠播）
          if (audio === el) {
            audio = null
          }
          URL.revokeObjectURL(url)
          if (previewingId.value === targetId) {
            previewingId.value = null
            onError?.()
          }
        })
      })
      .catch(() => {
        if (previewingId.value === targetId) {
          previewingId.value = null
          onError?.()
        }
      })
  }

  // 组件卸载（或所在 effect scope 销毁）时自动清理，杜绝音频与 blob URL 泄漏
  onScopeDispose(stop)

  return { previewingId, toggle, stop }
}
