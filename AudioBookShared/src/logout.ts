export interface LogoutOptions {
  /**
   * 通知服务端清除 HttpOnly 认证 Cookie。
   * HttpOnly Cookie 前端 JS 无权删除，必须走服务端；本地清理不能替代这一步。
   */
  serverLogout: () => Promise<unknown>
  /** 清理本地登录态（Pinia auth store） */
  clearAuth: () => void
  /** 跳转到登录页 */
  navigateToLogin: () => void
}

/**
 * 统一的登出流程（管理端与用户端共用）：
 *   1. 通知服务端清除 HttpOnly Cookie
 *   2. 清理本地登录态
 *   3. 跳转登录页
 *
 * 关键约定：**第 1 步失败不能阻塞后两步**。
 * 服务端可能已不可用或 Cookie 本就过期，此时若直接抛错，用户会被卡在已登录页面
 * 无法退出。所以这里吞掉服务端异常，本地状态照常清理。
 *
 * 依赖全部通过回调注入（不直接引用 vue-router / pinia），保持共享包无副作用。
 */
export async function performLogout(opts: LogoutOptions): Promise<void> {
  try {
    await opts.serverLogout()
  } catch {
    // 忽略：本地状态照常清除，不阻塞用户离开
  }
  opts.clearAuth()
  opts.navigateToLogin()
}
