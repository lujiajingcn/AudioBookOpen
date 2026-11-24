// 前后端共享的类型定义（与后端 JSON 返回结构保持一致），
// 作为单一可信契约，避免前后端契约不一致。

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
}

export interface AuthResponse {
  token: string
  username: string
  success: boolean
  /** 管理端需要据此判断登录账号是否具备管理员权限；用户端忽略此字段 */
  isAdmin?: boolean
  message?: string
}

/**
 * 后端统一业务返回结构：{ success, message, ...数据字段 }
 * T 承载与具体接口相关的业务字段（如 { content; totalElements }），
 * 调用方用 ApiResult<XxxPayload> 即可获得强类型，避免 [key: string]: unknown
 * 逼出的 as any / as T 强制转换（前端访问 response.data.xxx 拿到 unknown 必须 cast）。
 * 不传 T 时等价于 { success, message? }（用于只返回成功态的写操作端点）。
 */
export type ApiResult<T extends object = object> = {
  success: boolean
  message?: string
} & T

/**
 * /auth/validate 返回结构：是否登录有效 + 服务端当前管理员标记。
 * 后端未下发 isAdmin 时该字段缺失（可选），前端保留 localStorage 估计值作为容错。
 */
export interface AuthValidateResult {
  valid: boolean
  /** 服务端当前管理员标记；后端未返回时缺失，前端保留本地估计值 */
  isAdmin?: boolean
}

/** 认证态的最小契约（两端 auth store 实现；供 guard / bootstrap 复用，避免类型漂移） */
export interface AuthStateLike {
  username: string
  isAdmin: boolean
  isAuthenticated: boolean
  /**
   * 登录态是否已获服务端确认（bootstrap 的 /auth/validate 通过，或本次会话刚登录成功）。
   * 路由守卫用它区分「尚未确认」与「已确认未登录」：
   * username 非空而 verified 为 false 时属于窗口期，不做早期判定，
   * 否则刷新受保护页会被误踢、登录页又会被错误地放行（详见 guard.ts 注释）。
   */
  verified: boolean
  initFromStorage(): void
  /** 启动校验通过后由 bootstrap 调用；服务端回传了可信角色时以其覆盖本地估计 */
  markVerified(serverIsAdmin?: boolean): void
  logout(): void
}

export interface StoreBook {
  id: number
  title: string
  /**
   * 原书名（选填）。管理端上传时填写的存档信息：上架命名之前的原始名称，
   * 与 title 无绑定关系，null / undefined 表示未填写。
   */
  originalTitle?: string | null
  author?: string | null
  category?: string | null
  description?: string | null
  fileName?: string | null
  fileExtension?: string | null
  fileSize?: number | null
  coverColor?: string | null
  coverImage?: string | null
  downloadCount?: number | null
  createdAt?: string | null
  /** 上架状态：onshelf=正常在售，offshelf=已下架（磁盘文件已删，不可下载） */
  status?: string | null
  /**
   * 书城管理 → 合成语音：关联的合成任务 id，NULL 表示尚未合成音频。
   * 用户端下载音频 (GET /api/store/book/{id}/audio/download) 时据此定位 AudioSegment 列表。
   * 前端用以判断「这本书是否有音频」以及管理端表格展示「合成中 / 已合成 N 段」等状态。
   */
  taskId?: number | null
  /**
   * 合成任务的实时状态（仅管理端 `GET /api/admin/books` 返回；用户端书城接口不返回该字段）。
   * 取值与 Server 的 TaskStatus 一致：pending/queued/processing/paused/completed/partial/failed。
   *
   * 管理端据此区分「未合成 / 合成中 / 已暂停 / 已完成」并给出对应按钮：
   * - `pending|queued|processing` → 可「暂停」
   * - `paused` → 可「继续」或「删除语音」
   * - 其余终态 → 可「删除语音」
   */
  taskStatus?: string | null
  /** 已完成段数（合成中/已暂停时用于显示进度） */
  taskCompletedSegments?: number | null
  /** 总段数 */
  taskTotalSegments?: number | null
  /** 合成失败原因（failed/partial 时展示） */
  taskErrorMessage?: string | null
  /** 合成完成时间（仅展示用） */
  synthesizedAt?: string | null
  /** 触发合成的管理员用户名（仅展示用） */
  synthesizedBy?: string | null
  /**
   * 标签（一本书可有多个，与单选式的 `category` 并存）。
   *
   * 服务端存在 `store_book_tags` 关联表里，随书城列表/详情一起返回；
   * 未打标签的书为 `[]`（服务端保证不返回 null），因此前端可直接 `.length`。
   * 写入只能走管理端 `PUT /api/admin/books/{id}/tags`（全量替换语义）。
   */
  tags?: string[]
}

export interface CreditPackage {
  id: number
  name: string
  credits?: number | null
  bonusCredits?: number | null
  totalCredits?: number
  /** 数据库为 BigDecimal，JSON 序列化为 number */
  price: number
  description?: string | null
  enabled?: boolean
  sortOrder?: number
  createdAt?: string | null
  updatedAt?: string | null
}

export interface CreditTransaction {
  id: number
  userId?: number
  username?: string | null
  type?: string | null
  typeLabel?: string | null
  credits?: number | null
  balanceAfter?: number | null
  taskId?: number | null
  orderNo?: string | null
  remark?: string | null
  operator?: string | null
  createdAt?: string | null
}

// 「发现」页（帖 / 分享参考音频）数据契约：对齐后端 PostController 与 ReferenceAudioController 的真实返回。
// Web 端专属的 CreatePostResponse / UseReferenceResponse 仍保留在各自 services/discovery.ts。

/** 讨论帖（PostController#listPosts / createPost） */
export interface Post {
  id: number
  /** 发帖人用户名（冗余存储，避免列表 N+1） */
  username: string
  /** 帖子标题（可选，纯文字/随手记可留空） */
  title?: string | null
  /** 帖子正文 */
  content: string
  /** 图片 URL 列表（服务端 JSON 字段，客户端解为 string[]） */
  imageUrls?: string[]
  /** 嵌入的分享参考音频 id（user_reference_audio.id），未嵌入则为 null */
  refAudioId?: number | null
  /** @提醒的用户名列表（服务端 JSON 字段，客户端解为 string[]） */
  mentions?: string[]
  /** 冗余评论数（服务端维护，避免列表 N+1） */
  commentCount?: number
  /** 冗余收藏数（服务端维护，避免列表 N+1） */
  favoriteCount?: number
  createdAt: string | null
  /** 当前用户是否已收藏（仅登录用户请求时下发） */
  isFavorited?: boolean
}

export interface PostListResponse {
  success: boolean
  posts: Post[]
  total: number
  currentPage: number
  hasMore: boolean
  message?: string
}

/**
 * 分享的参考音频（参考音频排行 Tab）：GET /api/tts/shared-references 返回。
 * 按 useCount 倒序，rank 由后端填充（1 起）。
 */
export interface SharedReference {
  rank: number
  id: number
  name: string
  description?: string | null
  fileName: string
  fileSize: number | null
  useCount: number
  ownerName: string
  sharedAt: string | null
  createdAt: string | null
  /** 是否本人分享 */
  mine: boolean
}

export interface SharedReferenceListResponse {
  success: boolean
  references: SharedReference[]
  total: number
  message?: string
}

// 后台「发现内容审核」数据契约：对齐 AdminContentController（/api/admin/content/**）。
//
// 关键区分：前台的 SharedReference / Post 不带审核字段（下架内容根本不会出现在前台），
// 后台专用 AdminPost / AdminReference 才有 status / adminHidden —— 避免前台误用这两个字段。

/** 后台帖子（逻辑下架可见）：GET /api/admin/content/posts */
export interface AdminPost {
  id: number
  username: string
  userId: number
  title?: string | null
  content: string
  commentCount: number
  favoriteCount: number
  /** active（正常）/ removed（已下架） */
  status: string
  /** status === 'removed' 的冗余布尔，前端直接 v-if 用 */
  removed: boolean
  /** 下架时刻，未下架为 null */
  removedAt: string | null
  createdAt: string | null
}

/** 帖子状态计数（列表页签角标） */
export interface AdminPostStatusCounts {
  active: number
  removed: number
  total: number
}

export interface AdminPostListResponse {
  success: boolean
  posts: AdminPost[]
  total: number
  /** 0-based */
  page: number
  size: number
  hasNext: boolean
  counts: AdminPostStatusCounts
  message?: string
}

/** 后台分享参考音频：GET /api/admin/content/references */
export interface AdminReference {
  id: number
  name: string
  description?: string | null
  fileName: string
  fileSize: number | null
  useCount: number
  ownerName: string
  /** 用户自己的分享开关（管理员下架不会改它） */
  shared: boolean
  /** 管理员下架标记，与 shared 正交 */
  adminHidden: boolean
  sharedAt: string | null
  createdAt: string | null
}

export interface AdminReferenceListResponse {
  success: boolean
  references: AdminReference[]
  total: number
  message?: string
}

/** 下架 / 恢复动作的返回：只回受影响的那一个实体的最新审核状态 */
export interface AdminModerationResultResponse {
  success: boolean
  post?: AdminPost
  reference?: {
    id: number
    shared: boolean
    adminHidden: boolean
    visibleInShareList: boolean
  }
  deletedId?: number
  message?: string
}

// 播放进度跨端同步
//
// 对齐服务端 ProgressController 的真实返回结构：
//   GET  /api/progress?taskId=...      → ApiResult<GetProgressResponse>
//   PUT  /api/progress  (body)         → ApiResult（无业务字段）

/** GET /api/progress 成功时 hasProgress=true 时的载荷 */
export interface ReadingProgressData {
  /** 1-based，与 Android 端 seg_ 进度键一致 */
  segment: number
  /** 段内播放偏移（毫秒） */
  offsetMs: number
  /** 服务端时间戳 ISO 字符串，由 ReadingProgress.@PreUpdate 维护 */
  updatedAt: string | null
}

/** GET /api/progress 响应载荷（不带 success/message —— 由调用方用 ApiResult 合并） */
export type GetProgressResponse =
  | { hasProgress: false }
  | { hasProgress: true; segment: number; offsetMs: number; updatedAt: string | null }

/** PUT /api/progress 入参（与 Server 端 ProgressController#saveProgress 字段一一对应） */
export interface SaveProgressRequest {
  taskId: number
  /** 1-based 段号 */
  segment: number
  /** 段内偏移（毫秒），与 Android 端 offsetMs 一致 */
  offsetMs: number
}
