import type { RouteLocationNormalized } from 'vue-router'
import type { AuthStateLike } from './types'

export interface AuthGuardOptions {
  useAuthStore: () => AuthStateLike
  /** 未认证用户被导向的登录路由名 */
  loginRouteName: string
  /** 管理端模式：额外拦截 meta.requiresAdmin 且非管理员的访问 */
  adminMode?: boolean
  /** 用户端模式：登录成功后携带 ?redirect=<原路径> 以便原路返回 */
  redirectQueryKey?: string
  /** 已认证时若命中这些路由名则重定向到首页（如 Login / Register） */
  authenticatedRedirectNames?: string[]
}

/**
 * 生成统一的路由守卫（两端共用的「认证拦截」骨架）：
 * - requiresAuth 且未认证 → 跳登录页（用户端带 redirect query）
 * - 管理端：requiresAdmin 且非管理员 → 清登录态跳登录页（避免无限重定向）
 * - 已认证访问登录/注册页 → 回首页
 */
export function createAuthGuard(opts: AuthGuardOptions) {
  const redirectNames = opts.authenticatedRedirectNames ?? [opts.loginRouteName]
  return (to: RouteLocationNormalized) => {
    const auth = opts.useAuthStore()

    // 「本地有候选登录态、但尚未经服务端确认」的窗口期（bootstrap 的 /auth/validate 返回前）。
    // 此时 isAuthenticated 恒为 false，但用户很可能本来就是登录状态（凭据在 HttpOnly Cookie 里）。
    // 若照旧判定，会同时产生两个相反方向的错乱：
    //   1) 把已登录用户从受保护页误踢到登录页（刷新即掉线）；
    //   2) 登录页对已登录用户「放行」，而 bootstrap 一完成 verified 就变 true，
    //      此后任何一次 SPA 内导航都会命中下面的 redirectNames 分支，
    //      把还在登录页上的用户直接弹去首页——表现为「点『立即注册』却进了登录后的页面」。
    // 因此窗口期内一律不做早期判定：受保护页放行（凭据真的无效时由业务请求的 401 拦截器纠正），
    // 登录/注册页也不弹走。
    const pendingVerify = !!auth.username && auth.verified === false

    if (to.meta.requiresAuth && !auth.isAuthenticated && !pendingVerify) {
      if (opts.redirectQueryKey) {
        return { name: opts.loginRouteName, query: { [opts.redirectQueryKey]: to.fullPath } }
      }
      return { name: opts.loginRouteName }
    }

    if (opts.adminMode && to.meta.requiresAdmin && !auth.isAdmin && !pendingVerify) {
      // 后台所有页面都要求管理员，不能重定向到 '/'（会再次落回 requiresAdmin 分支形成死循环），
      // 正确做法是清除这份不具备管理员权限的登录态并回登录页。
      auth.logout()
      return { name: opts.loginRouteName }
    }

    if (redirectNames.includes(to.name as string) && auth.isAuthenticated) {
      return { path: '/' }
    }

    return true
  }
}
