import { defineAuthStoreBase } from '@audiobook/shared'

/**
 * 认证状态（安全前提：JWT 不落前端存储）。
 *
 * 凭据若放在 localStorage，任何 XSS 都能窃取管理员凭据（比普通用户危害更大），
 * 故由服务端下发 HttpOnly + SameSite=Lax 的 AUTH_TOKEN Cookie 承载：
 * - 凭据由浏览器自动携带（见 apiClient 的 withCredentials），JS 读不到也写不了；
 * - 登录响应体里的 token 仅为兼容 Android 端保留，管理后台一律忽略；
 * - 这里只持久化 username / isAdmin（非凭据），用于刷新后恢复 UI 与菜单展示。
 *   真实登录态以服务端为准：启动时 /auth/validate 校验，业务请求 401 由拦截器纠正。
 *
 * 具体实现由 @audiobook/shared 的 defineAuthStoreBase 提供，这里仅声明管理端的差异
 * （localStorage key 与 isAdmin 标记）。
 */
export const useAuthStore = defineAuthStoreBase({
  name: 'auth',
  admin: true,
  storageKeys: {
    username: 'admin_username',
    isAdmin: 'admin_is_admin',
    legacyToken: 'admin_token',
  },
})
