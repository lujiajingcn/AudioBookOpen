import { createApiClient } from '@audiobook/shared'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import { message, Modal } from 'ant-design-vue'
import { pauseActivePlayback } from '@/services/playbackControl'

/**
 * 统一的「未认证 → 清登录态 + 跳登录页」处理。
 * axios 401 拦截器与 SSE 的 onUnauthorized 共用同一份逻辑，避免两端各写导致行为漂移。
 * @param reason 可选的错误原因（解析自响应体）；不传则使用默认文案。
 */
// 去重守卫：多连接（axios 401 拦截器 + SSE onUnauthorized）同时 401 时只跳转一次，
// 避免重复 router.replace 把已登出的用户反复弹回登录页
let redirecting = false

// 登录过期期间只允许弹一次 re-login 模态，
// 避免并发 401（如下一段预取 + 下载同时过期）把多个模态叠到用户脸上。
let sessionExpiredModalOpen = false

export function redirectToLogin(reason?: string) {
  if (redirecting) return
  redirecting = true
  const authStore = useAuthStore()
  authStore.logout()

  if (reason) message.error(reason)

  // 带上当前页面地址，登录成功后原路返回
  const current = router.currentRoute.value
  const redirect = current.path !== '/login' ? current.fullPath : undefined
  // 导航完成后复位去重守卫：仅屏蔽「同一时刻并发的多个 401」，
  // 而非永久失效——否则用户重新登录、会话再次过期时，后续 401 将不再被引导回登录页。
  return router
    .replace({ path: '/login', query: redirect ? { redirect } : {} })
    .finally(() => {
      redirecting = false
    })
}

/**
 * 本端应用标识。随请求头发给服务端，使本端使用专属认证 Cookie 名
 * （AUTH_TOKEN_WEB），不再与 :3001 管理后台（AUTH_TOKEN_MANAGER）互相顶掉登录态。
 * SSE 走 fetch 不继承 apiClient 默认头，需显式传（见 TaskList.vue）。
 */
export const CLIENT_APP_ID = 'web'

const apiClient = createApiClient({
  // 普通接口默认 30s。上传/下载等长耗时接口在各自的 api 调用里单独覆盖
  // （见 api.ts 中 timeout: LONG_TIMEOUT / DOWNLOAD_TIMEOUT），避免大文件被 30s 一刀切。
  timeout: 30000,
  appId: CLIENT_APP_ID,
  onUnauthorized: async (error) => {
    // 读取响应体真实原因，区分 token 过期 / 无权限 / 未登录，避免一律显示"登录已过期"掩盖真实错误。
    // blob 响应（试听/下载）的错误体是 Blob，必须解析出文本 JSON 才能读到 message
    let data: unknown = error.response?.data
    if (data instanceof Blob) {
      const ct = (error.response?.headers?.['content-type'] as string | undefined) || data.type || ''
      if (ct.includes('json')) {
        try {
          data = JSON.parse(await data.text())
        } catch {
          data = undefined
        }
      } else {
        data = undefined
      }
    }
    const reason =
      (typeof data === 'string' ? data : (data as { message?: string } | undefined)?.message) ||
      '登录已过期，请重新登录'

    // 登录过期时先暂停正在进行的听书播放，避免过期期间音频继续空放；
    // 再弹 re-login 模态，由用户确认后才真正跳转登录页（redirectToLogin 自带去重守卫）。
    // 纯 <audio> 播放不发起请求，被动听书遇过期不会自动打断；只有并发业务请求（下一段预取 /
    // 下载 / 收藏）命中 401 才会触发本分支——此时先暂停+确认，体验远好于直接踢回登录页。
    pauseActivePlayback()

    if (sessionExpiredModalOpen) return
    sessionExpiredModalOpen = true
    Modal.confirm({
      title: '登录已过期',
      content:
        '登录状态已失效，继续收听需要重新登录。是否现在前往登录？',
      okText: '重新登录',
      cancelText: '稍后',
      onOk: () => {
        sessionExpiredModalOpen = false
        redirectToLogin(reason)
      },
      onCancel: () => {
        // 用户选择稍后：保持当前页，但播放已暂停（不再空放）；
        // 复位守卫，使后续再次过期可重新提示，而非永久静默。
        sessionExpiredModalOpen = false
      },
    })
  },
})

export default apiClient
