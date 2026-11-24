import type { AxiosInstance, AxiosResponse } from 'axios'
import type { AuthResponse, AuthValidateResult, LoginRequest } from './types'

/**
 * 共享认证接口（两端一致：login / validateToken / logout）。
 * 用户端额外的 register / changePassword 仍保留在各自 api.ts。
 */
export function createAuthApi(client: AxiosInstance) {
  return {
    /** POST /api/auth/login → AuthResponse（凭据由服务端以 HttpOnly Cookie 下发） */
    login(data: LoginRequest): Promise<AxiosResponse<AuthResponse>> {
      return client.post<AuthResponse>('/auth/login', data)
    },

    /**
     * GET /api/auth/validate → { valid, isAdmin? }。
     * 凭据由浏览器以 HttpOnly Cookie 自动携带，前端不持有也不需要持有 token。
     * 后端未下发 isAdmin（返回裸 boolean）时做兼容：归一化为 { valid, isAdmin? }，
     * isAdmin 缺失则前端保留 localStorage 估计值（容错，不误杀登录态）。
     */
    validateToken(): Promise<AxiosResponse<AuthValidateResult>> {
      return client
        .get<AuthValidateResult | boolean>('/auth/validate')
        .then((res) => {
          const d = res.data
          const normalized: AuthValidateResult =
            typeof d === 'boolean'
              ? { valid: d }
              : { valid: !!d?.valid, isAdmin: d?.isAdmin }
          return { ...res, data: normalized }
        })
    },

    /** 登出：服务端清除 HttpOnly 认证 Cookie（JS 无权删除，必须走服务端） */
    logout(): Promise<AxiosResponse<unknown>> {
      return client.post('/auth/logout')
    },
  }
}
