import { createApiClient } from '@audiobook/shared'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import { message, Modal } from 'ant-design-vue'

// 登录过期期间只允许弹一次 re-login 模态，避免并发 401 把多个模态叠到管理员脸上
let sessionExpiredModalOpen = false

/**
 * 本端应用标识。随请求头发给服务端，使管理后台使用专属认证 Cookie 名
 * （AUTH_TOKEN_MANAGER），不再与 :80 用户端（AUTH_TOKEN_WEB）互相顶掉登录态。
 */
export const CLIENT_APP_ID = 'manager'

const apiClient = createApiClient({
  timeout: 10000,
  appId: CLIENT_APP_ID,
  onUnauthorized: (error) => {
    // 仅在拦截器真正接管了错误时置 __handled，
    // 让 21 处调用点的 if (!isHandledByInterceptor(error)) 能区分「已被拦截处理」与「需自行提示」；
    // 对所有错误无条件标记会导致 400/403/500/网络错误被静默吞掉，操作失败无任何反馈。
    ;(error as { __handled?: boolean }).__handled = true

    const msg =
      (error.response?.data as { message?: string } | undefined)?.message || '登录已过期，请重新登录'

    // 登录过期时先弹确认模态，由管理员确认后才真正登出并跳转
    // ——避免正在进行的操作（如编辑中的表单）被无预警打断。
    // 注：本端凭据走 HttpOnly Cookie，客户端拿不到 JWT exp，无法做「过期前 5 分钟软提示」，
    // 故仅在命中 401 时做友好处理。
    if (sessionExpiredModalOpen) return
    sessionExpiredModalOpen = true
    Modal.confirm({
      title: '登录已过期',
      content: '登录状态已失效，请重新登录以继续使用管理后台。',
      okText: '重新登录',
      cancelText: '稍后',
      onOk: () => {
        sessionExpiredModalOpen = false
        const authStore = useAuthStore()
        authStore.logout()
        message.error(msg)
        router.replace('/login')
      },
      onCancel: () => {
        // 管理员选择稍后：保留当前页；复位守卫，后续再次过期可重新提示而非永久静默
        sessionExpiredModalOpen = false
      },
    })
  },
})

export function isHandledByInterceptor(error: unknown): boolean {
  return (error as { __handled?: boolean } | undefined)?.__handled === true
}

export default apiClient
