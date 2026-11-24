import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface AuthStoreOptions {
  /** Pinia store 名（两端都用 'auth'，各自独立实例） */
  name: string
  storageKeys: {
    username: string
    /** 仅管理端需要：持久化 isAdmin 标记 */
    isAdmin?: string
    /** 早期版本遗留的明文 token，initFromStorage 时清理 */
    legacyToken: string
  }
  /** 是否为管理端（决定是否持久化/暴露 isAdmin） */
  admin?: boolean
}

/**
 * 认证状态工厂（安全前提：JWT 不落前端存储，凭据在 HttpOnly Cookie 里）。
 * Manager / Web 两端共用，仅通过 options 注入 localStorage key 与 isAdmin 的差异。
 */
export function defineAuthStoreBase(opts: AuthStoreOptions) {
  return defineStore(opts.name, () => {
    const username = ref<string>('')
    const isAdmin = ref<boolean>(false)
    /**
     * 登录态是否已获服务端确认。
     * username 只是「候选标记」，本地存储可被伪造，必须经本轮确认才成为真实登录态：
     * 1) 本次会话登录刚成功；2) 启动时 /auth/validate 校验通过。
     */
    const verified = ref<boolean>(false)

    const isAuthenticated = computed(() => !!username.value && verified.value)

    function login(userName: string, userIsAdmin = false) {
      username.value = userName
      if (opts.admin) isAdmin.value = userIsAdmin
      verified.value = true // 登录刚成功，服务端已确认
      localStorage.setItem(opts.storageKeys.username, userName)
      if (opts.admin && opts.storageKeys.isAdmin) {
        localStorage.setItem(opts.storageKeys.isAdmin, userIsAdmin ? '1' : '0')
      }
    }

    function logout() {
      username.value = ''
      isAdmin.value = false
      verified.value = false
      localStorage.removeItem(opts.storageKeys.username)
      if (opts.storageKeys.isAdmin) localStorage.removeItem(opts.storageKeys.isAdmin)
      // 清理遗留的明文 token：凭据已由 HttpOnly Cookie 承载，本地明文 token 无意义
      localStorage.removeItem(opts.storageKeys.legacyToken)
    }

    function initFromStorage() {
      localStorage.removeItem(opts.storageKeys.legacyToken)
      const storedUsername = localStorage.getItem(opts.storageKeys.username)
      if (storedUsername) {
        username.value = storedUsername
        if (opts.admin && opts.storageKeys.isAdmin) {
          isAdmin.value = localStorage.getItem(opts.storageKeys.isAdmin) === '1'
        }
        // 注意：这里故意不置 verified——本地存储可被伪造，须等 bootstrap 的 /auth/validate 确认
      }
    }

    /**
     * 启动校验（/auth/validate）通过后由 bootstrap 调用，把本地候选标记升级为真实登录态。
     * @param serverIsAdmin 服务端回传的当前管理员标记（可选）。为 boolean 时以其覆盖本地值——
     * 本地 localStorage 的 isAdmin 仅作初始估计（可被伪造），改以服务端角色为准。
     */
    function markVerified(serverIsAdmin?: boolean) {
      verified.value = true
      if (opts.admin && typeof serverIsAdmin === 'boolean') {
        isAdmin.value = serverIsAdmin
      }
    }

    return {
      username,
      isAdmin,
      verified,
      isAuthenticated,
      login,
      logout,
      initFromStorage,
      markVerified,
    }
  })
}
