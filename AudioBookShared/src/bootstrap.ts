import type { AuthStateLike, AuthValidateResult } from './types'

export interface BootstrapOptions {
  authStore: AuthStateLike
  /** 包装 authApi.validateToken().then(r => r.data)，返回 { valid, isAdmin? } */
  validate: () => Promise<AuthValidateResult>
  /**
   * 校验请求出错时的处理（差异化）：
   * - 管理端：401→logout；5xx→保留登录态并提示；
   * - 用户端：网络/服务不可达→暂按已验证保留（交由后续 401 拦截纠正）。
   * 不传则默认「保留登录态」（markVerified）。
   */
  onError?: (error: unknown) => void
}

/**
 * 启动前恢复登录态并向服务端校验有效性。
 */
export async function bootstrapAuth(opts: BootstrapOptions): Promise<void> {
  const store = opts.authStore
  store.initFromStorage()

  if (store.username) {
    try {
      const result = await opts.validate()
      if (result.valid) {
        // 用服务端角色覆盖本地估计（isAdmin 为 boolean 时才覆盖，缺失则保留本地值）
        store.markVerified(result.isAdmin)
      } else {
        store.logout()
      }
    } catch (error) {
      if (opts.onError) {
        opts.onError(error)
      } else {
        // 默认：不误杀登录态，交由后续业务请求的 401 拦截器纠正
        store.markVerified()
      }
    }
  }
}
