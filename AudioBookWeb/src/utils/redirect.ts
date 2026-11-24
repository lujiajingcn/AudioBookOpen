/**
 * 登录页 / 注册页处理 `?redirect=` 的公共逻辑。
 *
 * 背景：路由守卫在拦截「访问受保护页」时会跳 `/login?redirect=/store`（401 拦截器同理），
 * 登录/注册成功后按该参数原路返回。但两个页面之间互跳（"还没有账号？立即注册" /
 * "已有账号？立即登录"）若直接 `router.push('/register')`，参数就被丢掉了 ——
 * 「被拦到 /store → 去注册 → 注册成功」会落到首页而不是原本要去的 /store。
 *
 * 同时必须过滤非法值：只接受以 `/` 开头的站内路径。
 * 否则 `?redirect=https://evil.com` 会在登录成功后把用户带去外站（开放重定向）。
 */

/** 取出可用的站内回跳地址；缺失、非字符串或以 `//` 开头的协议相对地址一律返回 undefined */
export function sanitizeRedirect(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  // '//evil.com' 会被浏览器当成协议相对 URL 跳到外站，必须排除
  if (!value.startsWith('/') || value.startsWith('//')) return undefined
  return value
}

/**
 * 页面互跳时保留 redirect 参数：合法则返回 `{ redirect }`，否则返回空对象。
 * 配合 `router.push({ path: '/register', query: keepRedirectQuery(route.query.redirect) })` 使用。
 */
export function keepRedirectQuery(value: unknown): Record<string, string> {
  const redirect = sanitizeRedirect(value)
  return redirect ? { redirect } : {}
}
