import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { authApi } from '@/services/api'
import { bootstrapAuth } from '@audiobook/shared'
// 全局样式：基础 reset + 夜间模式下 body 底色，
// 以及静态 message/notification/Modal 浮层的暗色覆盖
//（这些浮层挂在 body 下、拿不到 ConfigProvider 的 darkAlgorithm，只能靠 CSS 兜底）。
// 此前本文件从未被 import，App.vue 里引用的「静态浮层暗色覆盖」实际一直未生效，
// 夜间模式下 toast / 确认弹窗仍是亮色白底——补上这行导入即修复。
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)

// 启动时恢复显示与阅读偏好（夜间模式 / 字号 / 缩放），在任何视图渲染前套用
useSettingsStore(pinia).init()

// Antd 按需导入（各组件自行 import），不做全局注册，以支持 tree-shaking

/**
 * 启动顺序是这里的关键：必须「先向服务端确认登录态 → 再安装 router → 最后挂载」。
 *
 * vue-router 在 `app.use(router)` 时会立刻发起首次导航（install 内部直接 push 当前地址）。
 * 若此时登录态尚未确认（verified 仍为 false），守卫会把已登录用户当成未登录：
 *   1) 已登录用户刷新受保护页 → 被误踢到登录页；
 *   2) 登录页反而被放行；等 /auth/validate 返回把 verified 置为 true 后，
 *      用户仍停留在登录页上，此后任何一次 SPA 内导航（例如点「立即注册」）
 *      都会命中守卫的「已认证访问登录/注册页」分支，被直接弹去首页。
 *      这正是「登录提示用户名或密码错误、点注册却进了登录成功后页面」的来源。
 *
 * 因此 router 的安装必须排在 bootstrapAuth 之后，让首次导航看到的是已确认的登录态。
 */
async function bootstrap() {
  try {
    await bootstrapAuth({
      authStore: useAuthStore(),
      validate: () => authApi.validateToken().then((res) => res.data),
    })
  } finally {
    // 即使校验过程抛错也必须完成 install + mount，否则页面白屏。
    // 此时守卫按「未登录」处理，用户仍可正常进入登录页重新登录。
    app.use(router)
    app.mount('#app')
  }
}

bootstrap()
