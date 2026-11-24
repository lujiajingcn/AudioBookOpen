import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/services/api'
import { bootstrapAuth } from '@audiobook/shared'
import { message } from 'ant-design-vue'
import { isAxiosError } from 'axios'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)

/**
 * 启动前先恢复登录态，并向后端校验其有效性（L-04：凭据在 HttpOnly Cookie 里，
 * JS 只能依据本地 username 候选标记先假定登录态，再向服务端校验纠正）：
 * 校验通过才升级为真实登录态（路由守卫放行）；
 * Cookie 已失效（401 / validate 返回 false）则清除本地标记；
 * 网络异常/服务故障时暂按已验证保留，让后续业务请求的 401 拦截器自然纠正。
 *
 * 注意 router 必须在校验之后才 install：vue-router 在 install 时会立刻发起首次导航，
 * 若登录态还没确认（verified=false），已登录用户刷新受保护页会被误踢到登录页，
 * 而登录页又被放行——随后 verified 变 true，用户在登录页上的任何跳转都会被弹去首页。
 */
async function bootstrap() {
  try {
    await bootstrapAuth({
      authStore: useAuthStore(),
      validate: () => authApi.validateToken().then((res) => res.data),
      onError: (error) => {
        // 401 = 真正未认证 → 清除登录态
        // 5xx / 其它 4xx / 网络错误 → 不误杀：暂按已验证保留登录态并提示，
        // 服务恢复前一切请求本就不可用，首个业务请求的 401 拦截器会负责纠正
        if (isAxiosError(error) && error.response?.status === 401) {
          useAuthStore().logout()
        } else {
          useAuthStore().markVerified()
          if (isAxiosError(error)) {
            const status = error.response?.status
            if (status !== undefined && status >= 500) {
              message.error('服务异常，登录态校验失败，请稍后重试')
            } else if (status !== undefined) {
              message.warning('登录态校验失败，请重新登录')
            } else {
              message.warning('网络异常，登录态校验失败，请稍后重试')
            }
          } else {
            message.warning('网络异常，登录态校验失败，请稍后重试')
          }
        }
      },
    })
  } finally {
    // 校验过程抛错也必须完成 install + mount，否则后台白屏
    app.use(router)
    app.mount('#app')
  }
}

bootstrap()
