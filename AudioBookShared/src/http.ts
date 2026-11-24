import axios, { AxiosError, type AxiosInstance, type AxiosResponse } from 'axios'

/**
 * 应用标识请求头名。与服务端 AuthCookieService.APP_ID_HEADER 必须一致。
 *
 * 为什么要这个头：Cookie 的作用域**不含端口**，用户端站点（生产 :80 / 开发 :3000）与管理后台（:3001）
 * host 相同，历史上共用同一个 AUTH_TOKEN——在用户端登录会直接把管理端的凭据顶掉。
 * 各端带上自己的标识后，服务端改为下发/读取该端专属的 Cookie 名
 * （AUTH_TOKEN_WEB / AUTH_TOKEN_MANAGER），两端登录态彻底隔离。
 */
export const APP_ID_HEADER = 'X-Client-App'

export interface ApiClientOptions {
  /** 默认 30000。上传/下载等可在各自 api 调用里单独覆盖 */
  timeout?: number
  /**
   * 本应用标识（如 'web' / 'manager'），随每个请求以 {@link APP_ID_HEADER} 发给服务端。
   * 不传则服务端退回历史共享 Cookie 行为（该端登录态仍会被另一端顶掉）。
   */
  appId?: string
  /**
   * 业务接口（非 /auth/）遭遇 401 时由拦截器统一调用：清本地登录态 + 提示 + 跳登录页。
   * 由各端自行实现，以保留其差异化处理
   * （如管理端标记 __handled、用户端解析 Blob 错误体后给出具体原因）。
   */
  onUnauthorized: (error: AxiosError) => void | Promise<unknown>
}

/**
 * 创建统一的 axios 实例与 401 拦截骨架。
 * 差异化的「401 后行为」由各端通过 onUnauthorized 注入。
 */
export function createApiClient(opts: ApiClientOptions): AxiosInstance {
  const client = axios.create({
    baseURL: '/api',
    timeout: opts.timeout ?? 30000,
    // 安全前提：认证凭据由服务端下发的 HttpOnly Cookie 携带，
    // 必须允许跨端口/跨域请求携带凭据；前端不再手动注入 Authorization 头。
    withCredentials: true,
    headers: {
      'Content-Type': 'application/json',
      // 应用标识是常量头，写进实例默认头即可覆盖全部请求（含 multipart 上传：
      // 那些调用只覆盖 Content-Type，不会把本头顶掉）。
      ...(opts.appId ? { [APP_ID_HEADER]: opts.appId } : {}),
    },
  })

  // 防止并发 401 触发多次跳转/提示
  let redirecting = false

  client.interceptors.response.use(
    (response: AxiosResponse) => response,
    (error: AxiosError) => {
      const status = error.response?.status
      const url = error.config?.url || ''
      // 仅排除认证接口本身的 401（登录失败/注销），避免死循环
      if (status === 401 && !url.startsWith('/auth/') && !redirecting) {
        redirecting = true
        const finish = () => {
          redirecting = false
        }
        try {
          const result = opts.onUnauthorized(error)
          if (result && typeof (result as Promise<unknown>).then === 'function') {
            ;(result as Promise<unknown>).then(finish).catch(finish)
          } else {
            finish()
          }
        } catch {
          finish()
        }
      }
      return Promise.reject(error)
    },
  )

  return client
}
