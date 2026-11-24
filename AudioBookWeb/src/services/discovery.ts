import apiClient from './apiClient'
import type { PostListResponse, SharedReferenceListResponse } from '@audiobook/shared'

// 「发现」页公共数据契约统一放在 @audiobook/shared，这里重新导出以保持视图层既有 import 不变。
export type {
  Post,
  PostListResponse,
  SharedReference,
  SharedReferenceListResponse,
} from '@audiobook/shared'

// 「发现」页数据契约：对齐后端 PostController 与 ReferenceAudioController 的真实返回结构。

export interface CreatePostResponse {
  success: boolean
  id: number
  username: string
  content: string
  createdAt: string | null
  message?: string
}

/** POST /api/tts/shared-references/{id}/use 返回 */
export interface UseReferenceResponse {
  success: boolean
  /** 本次是否实际计数（本人使用自己的音频为 false） */
  counted: boolean
  useCount: number
  message?: string
}

/**
 * 发现页接口封装。
 * 试听走 GET .../audio（blob，不计数）；「使用」才上报计数——
 * 与后端设计一致（试听与真正使用走同一下载流，计数只能放在「使用」动作上，否则榜单被试听刷花）。
 */
export const discoveryApi = {
  /** 帖子列表（分页，按发帖时间倒序，id 兜底去重） */
  listPosts: (page = 0, limit = 20) =>
    apiClient.get<PostListResponse>('/posts', { params: { page, limit } }),

  /** 发布纯文字帖（内容 ≤ 500 码点由后端再校验一次） */
  createPost: (content: string) =>
    apiClient.post<CreatePostResponse>('/posts', { content }),

  /** 参考音频分享排行榜（按使用量倒序） */
  listSharedReferences: (limit = 50) =>
    apiClient.get<SharedReferenceListResponse>('/tts/shared-references', { params: { limit } }),

  /** 试听分享的参考音频（blob 流，不计数） */
  previewSharedReference: (id: number) =>
    apiClient.get<Blob>(`/tts/shared-references/${id}/audio`, {
      responseType: 'blob',
      // 单个参考音频最长 120s 转码，给足 10 分钟窗口（与 api.ts 的 DOWNLOAD_TIMEOUT 对齐）
      timeout: 600000,
    }),

  /** 上报一次「使用」：使用量 +1（本人使用自己分享的音频不计数） */
  useSharedReference: (id: number) =>
    apiClient.post<UseReferenceResponse>(`/tts/shared-references/${id}/use`),
}
