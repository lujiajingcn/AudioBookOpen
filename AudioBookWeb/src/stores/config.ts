import { defineStore } from 'pinia'
import { ref } from 'vue'
import { configApi } from '@/services/api'

// 与后端 application.yml 中 spring.servlet.multipart.max-file-size 默认值一致，
// 作为配置未加载完成时的兜底，保证即使接口未返回也不会误拦合法文件。
const FALLBACK_MAX_FILE_SIZE = 50 * 1024 * 1024

// 单段合成平均耗时 / 并发数兜底（与后端 application.yml 默认值一致），用于耗时预估
const FALLBACK_AVG_SEGMENT_SECONDS = 8
const FALLBACK_TTS_CONCURRENCY = 2

export const useConfigStore = defineStore('config', () => {
  const maxFileSizeBytes = ref<number>(FALLBACK_MAX_FILE_SIZE)
  const avgSegmentSeconds = ref<number>(FALLBACK_AVG_SEGMENT_SECONDS)
  const ttsConcurrency = ref<number>(FALLBACK_TTS_CONCURRENCY)
  const loaded = ref(false)

  async function fetchConfig() {
    if (loaded.value) return
    try {
      const res = await configApi.getConfig()
      if (res.data.success && res.data.maxFileSize > 0) {
        maxFileSizeBytes.value = res.data.maxFileSize
      }
      if (res.data.avgSegmentSeconds && res.data.avgSegmentSeconds > 0) {
        avgSegmentSeconds.value = res.data.avgSegmentSeconds
      }
      if (res.data.ttsConcurrency && res.data.ttsConcurrency > 0) {
        ttsConcurrency.value = res.data.ttsConcurrency
      }
    } catch (e) {
      // 接口失败时保留兜底值，不阻断页面
    } finally {
      loaded.value = true
    }
  }

  /** 预估合成 N 段所需总秒数（段数 × 单段耗时 / 并发） */
  function estimateSeconds(totalSegments: number): number {
    if (totalSegments <= 0) return 0
    return (totalSegments * avgSegmentSeconds.value) / Math.max(1, ttsConcurrency.value)
  }

  return { maxFileSizeBytes, avgSegmentSeconds, ttsConcurrency, loaded, fetchConfig, estimateSeconds }
})
