import apiClient from './apiClient'
import type { PostListResponse, SharedReferenceListResponse } from '@audiobook/shared'

// 「发现」页（后台内容审核视图）数据契约统一放在 @audiobook/shared，
// 这里重新导出以保持视图层既有 import 不变。
export type {
  Post,
  PostListResponse,
  SharedReference,
  SharedReferenceListResponse,
} from '@audiobook/shared'

// 后台审核专用契约（带 status / adminHidden，前台契约里没有这两个字段）
export type {
  AdminPost,
  AdminPostListResponse,
  AdminPostStatusCounts,
  AdminReference,
  AdminReferenceListResponse,
  AdminModerationResultResponse,
} from '@audiobook/shared'

// 「发现」页（后台内容审核视图）数据契约：对齐后端 PostController 与 ReferenceAudioController 的真实返回。
// 与 Web 端 services/discovery.ts 字段一致，但后台只做只读审核，不提供发帖/使用动作。

/**
 * 发现页（后台审核）接口封装。
 * 后台不发文、不使用；审核列表实际走 adminContentApi，本模块主要用于试听
 * （GET .../audio，blob，不计数）。listPosts / listSharedReferences 目前未被 Manager 调用。
 */
export const discoveryApi = {
  /** 帖子列表（分页，按发帖时间倒序，id 兜底去重） */
  listPosts: (page = 0, limit = 20) =>
    apiClient.get<PostListResponse>('/posts', { params: { page, limit } }),

  /** 参考音频分享排行榜（按使用量倒序） */
  listSharedReferences: (limit = 50) =>
    apiClient.get<SharedReferenceListResponse>('/tts/shared-references', { params: { limit } }),

  /** 试听分享的参考音频（blob 流，不计数，用于内容审核） */
  previewSharedReference: (id: number) =>
    apiClient.get<Blob>(`/tts/shared-references/${id}/audio`, {
      responseType: 'blob',
      // 单个参考音频最长 120s 转码，给足 10 分钟窗口（与 Web 端 discovery 服务对齐）
      timeout: 600000,
    }),
}

/**
 * 后台内容审核接口（/api/admin/content/**）。
 *
 * 与上面的 discoveryApi 分开放：前者是「看」，这里是「改」，
 * 混在一个对象里容易被误用于只读场景。
 *
 * 审核端点全部挂在 /api/admin/** 下，由服务端 SecurityConfig 统一要求 ADMIN 角色。
 */
export const adminContentApi = {
  /** 帖子审核列表：status = all（默认） / active / removed */
  listPosts: (status: 'all' | 'active' | 'removed' = 'all', page = 0, size = 20) =>
    apiClient.get<import('@audiobook/shared').AdminPostListResponse>('/admin/content/posts', {
      params: { status, page, size },
    }),

  /** 逻辑下架：前台立即不可见，数据保留可恢复 */
  removePost: (id: number) =>
    apiClient.post<import('@audiobook/shared').AdminModerationResultResponse>(
      `/admin/content/posts/${id}/remove`,
    ),

  /** 恢复上架 */
  restorePost: (id: number) =>
    apiClient.post<import('@audiobook/shared').AdminModerationResultResponse>(
      `/admin/content/posts/${id}/restore`,
    ),

  /** 彻底删除（不可恢复，连带评论与收藏） */
  deletePost: (id: number) =>
    apiClient.delete<import('@audiobook/shared').AdminModerationResultResponse>(
      `/admin/content/posts/${id}`,
    ),

  /** 参考音频审核列表：onlyHidden=true 只看被管理员下架的 */
  listReferences: (onlyHidden = false, limit = 100) =>
    apiClient.get<import('@audiobook/shared').AdminReferenceListResponse>(
      '/admin/content/references',
      { params: { onlyHidden, limit } },
    ),

  /** 下架 / 恢复参考音频（只改 admin_hidden，不动用户的 shared 开关） */
  setReferenceHidden: (id: number, hidden: boolean) =>
    apiClient.post<import('@audiobook/shared').AdminModerationResultResponse>(
      `/admin/content/references/${id}/hidden`,
      null,
      { params: { hidden } },
    ),
}
