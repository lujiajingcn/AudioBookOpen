/**
 * 听书进度记忆（按 taskId 存「段号 + 段内偏移」）。
 *
 * 显式传入 id 而非在内部读 computed，避免 String(computedRef) 得到 "[object Object]" 的键错配
 * bug（progress 一直在写、永远读不出，断点续听静默失效）。id 必须由调用方传入。
 *
 * 进度同时上送服务端（GET/PUT /api/progress，契约见 AudioBookShared 的
 * ReadingProgressData / GetProgressResponse / SaveProgressRequest），并保留 localStorage
 * 作为离线兜底——只写 localStorage 时换设备/换端后进度会丢失。
 * 读取时以「服务端优先、本地兜底」合并——任一侧失败都不影响另一侧，
 * 保证断点续听永不静默丢失。
 */

import apiClient from '@/services/apiClient'
import type { ApiResult, GetProgressResponse } from '@audiobook/shared'

const PROGRESS_KEY = 'audiobook_progress'

export interface PlayProgress {
  /** 1-based 段号 */
  segmentNumber: number
  /** 段内偏移（秒，本地镜像用秒；上送服务端前 ×1000 转 ms） */
  offset: number
}

type LocalMap = Record<string, { segmentNumber: number; offset: number }>

/**
 * 进度端点的可定制项（默认全部走 TTS 任务私有口径 `/api/progress`）。
 *
 * 书城阅读页必须覆盖：书的音频是**公共资源**，服务端 `store_books.task_id` 指向的
 * 往往是管理员的合成任务，普通用户调 `/api/progress?taskId=` 会因任务归属校验被 403
 * （表现为断点续播静默失效）。书级端点 `/api/store/book/{bookId}/progress` 服务端内部
 * 仍以 (user_id, 书的 task_id) 落库，故与任务播放页的进度互通。
 */
export interface PlayProgressOptions {
  /** 读接口路径（默认 `/progress`） */
  loadUrl?: (id: number) => string
  /** 读接口查询参数（默认 `{ taskId: id }`） */
  loadParams?: (id: number) => Record<string, unknown>
  /** 写接口路径（默认 `/progress`） */
  saveUrl?: (id: number) => string
  /** 写接口请求体（默认 `{ taskId, segment, offsetMs }`） */
  saveBody?: (id: number, segmentNumber: number, offsetSec: number) => Record<string, unknown>
}

function readLocal(): LocalMap {
  try {
    return JSON.parse(localStorage.getItem(PROGRESS_KEY) || '{}') as LocalMap
  } catch {
    return {}
  }
}

function saveLocal(id: number, segmentNumber: number, offsetSec: number) {
  try {
    const map = readLocal()
    map[String(id)] = { segmentNumber, offset: Math.floor(offsetSec) }
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(map))
  } catch {
    // 忽略存储异常
  }
}

function readLocalById(id: number): PlayProgress | null {
  const v = readLocal()[String(id)]
  if (v && typeof v === 'object') {
    return { segmentNumber: v.segmentNumber, offset: v.offset || 0 }
  }
  // 兼容旧格式（只存段号数字）
  if (typeof v === 'number') {
    return { segmentNumber: v, offset: 0 }
  }
  return null
}

/**
 * 清掉指定 id 的本地镜像（让唯一真实进度交给服务端 / 彻底丢弃陈旧记录）。
 * 用法：服务端 confirmed 无进度 / 服务端返回新进度时调用，避免 localStorage 残留覆盖或混入。
 */
function clearLocalById(id: number) {
  try {
    const map = readLocal()
    const k = String(id)
    if (k in map) {
      delete map[k]
      localStorage.setItem(PROGRESS_KEY, JSON.stringify(map))
    }
  } catch {
    // 忽略存储异常
  }
}

export function usePlayProgress(options: PlayProgressOptions = {}) {
  // 默认口径 = 改造前的行为（按 taskId 走 /api/progress），调用方不传 options 时完全不变
  const loadUrlOf = options.loadUrl ?? (() => '/progress')
  const loadParamsOf = options.loadParams ?? ((id: number) => ({ taskId: id }))
  const saveUrlOf = options.saveUrl ?? (() => '/progress')
  const saveBodyOf =
    options.saveBody ??
    ((id: number, segmentNumber: number, offsetSec: number) => ({
      taskId: id,
      segment: segmentNumber,
      offsetMs: offsetSec * 1000,
    }))

  /**
   * 保存进度：写本地 + 上送服务端（并行、互不阻塞）。
   * 上送为 best-effort：失败静默（离线/未登录均由 localStorage 兜底），
   * 不抛异常、不 Toast，避免打断听书。
   * @param id   进度键（默认语义为 taskId；书城阅读页传 bookId，端点由调用方定制）
   * @param segmentNumber 1-based 段号
   * @param offset 段内偏移（秒）
   */
  const save = (id: number, segmentNumber: number, offset = 0) => {
    const offsetSec = Math.floor(offset)
    saveLocal(id, segmentNumber, offsetSec)
    apiClient
      .put(saveUrlOf(id), saveBodyOf(id, segmentNumber, offsetSec))
      .catch(() => {
        // 上送失败不抛、不 Toast：断点续听以本地为准，保证离线可用
      })
  }

  /**
   * 加载进度：服务端优先，本地兜底合并。
   * 先以本地镜像立即返回（保证离线可用、UI 不阻塞），再用服务端结果覆盖（如有）。
   * 返回合并后的进度（offset 统一为秒），两侧均无进度时返回 null。
   * 本地与服务端都有进度时以服务端为准（跨设备续听取最近一次上报）。
   */
  const load = async (id: number): Promise<PlayProgress | null> => {
    const local = readLocalById(id)

    let best: PlayProgress | null = local
    try {
      const res = await apiClient.get<ApiResult<GetProgressResponse>>(loadUrlOf(id), {
        params: loadParamsOf(id),
      })
      const data = res.data
      if (data && data.success && data.hasProgress) {
        best = {
          segmentNumber: data.segment,
          offset: Math.floor(data.offsetMs / 1000),
        }
        // 服务端有进度：清掉对应 id 的本地镜像，避免「Server (10,12) + Local (8,4) 任意一会话命中 Local 后残留陈旧
        // offset=4 之类远在段首的偏移，触发用户察觉不到的「段 8 从中间起播」」。服务端优先后，本地就该交权。
        clearLocalById(id)
      } else if (data && data.success && !data.hasProgress) {
        // 服务端确认无进度：本地那条是**别的账号/设备的残留**，不能让 modal 弹出来误导当前用户。
        // 例：用户在 A 账号听到段 8 第 4 秒，浏览器 localStorage 写了 {8,4}；切到 B 账号登录后服务端 reading_progress
        // 没有 bookId=24 的记录 → hasProgress=false → local 会冒出来触发 playSegment(8,4) 段头丢失。
        // 这里直接清，下次再进来就不会再弹 modal。
        clearLocalById(id)
      }
    } catch {
      // 服务端不可用：保留本地结果（可能为 null），离线场景的唯一兜底
    }
    return best
  }

  return { save, load }
}
