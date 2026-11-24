/**
 * 基于 fetch + ReadableStream 的 SSE 客户端。
 *
 * 为什么不直接用原生 EventSource：EventSource 无法精细控制凭据与重连策略，
 * 而本服务的 SSE 端点（/api/tts/stream/{taskId}）依赖认证。
 * 这里用 fetch 手动解析 SSE 流：认证走浏览器自动携带的 HttpOnly Cookie
 * （credentials: 'include'，JS 接触不到凭据），并支持断线自动重连（指数退避）。
 *
 * 健壮性设计：
 * 1. 永久性错误（401/403/404）不再重连——凭据失效或任务已删除时，无限重连只会
 *    刷屏、浪费电量，进度也不可能恢复；
 * 2. 网络错误重连有上限（MAX_RETRIES），超限后通过 onGiveUp 通知调用方降级
 *    （例如切换到轮询兜底）；
 * 3. 看门狗：后端有心跳事件，若 WATCHDOG_MS 内没收到任何字节（代理静默断开的典型
 *    表现），主动断开并重连，避免进度永久冻结在最后一次推送上。
 */

export interface SseHandlers {
  /** 收到某条事件（event 名 + 解析后的 data，data 若非 JSON 则原样返回字符串） */
  onMessage: (event: string, data: any) => void
  /** 流正常结束（后端 emitter.complete() 后触发） */
  onComplete?: () => void
  /** 连接异常/网络错误（内部会自动重连，直到达到上限） */
  onError?: (err: any) => void
  /** 重连次数耗尽或永久性错误（401/403/404），客户端已彻底停止，调用方应降级（如轮询） */
  onGiveUp?: (reason: 'permanent' | 'retries-exhausted', err: any) => void
  /**
   * 凭据失效回调：流返回 401/403 时触发（与 onGiveUp 同时调用）。
   * SSE 走 fetch 而非 axios，不会经过 axios 的 401 拦截器，故在此显式暴露，
   * 由调用方注入登出/跳转逻辑，避免共享包直接依赖业务 store / router。
   */
  onUnauthorized?: () => void
}

/** 网络错误最大重连次数（成功收到消息后计数清零） */
const MAX_RETRIES = 10
/** 看门狗：超过该时长未收到任何事件/心跳即认为连接已死（后端心跳间隔应远小于此值） */
const WATCHDOG_MS = 90000

export class SseClient {
  private controller: AbortController | null = null
  private reconnectTimer: number | null = null
  private watchdogTimer: number | null = null
  private retryCount = 0
  private closed = false
  /**
   * 看门狗是否正在主动断开当前连接。
   *
   * 必须区分「看门狗超时 abort」（必须继续走重连）与「close() 主动 abort」（必须静默退出）：
   * 只判 signal.aborted 会把看门狗路径也当成用户主动关闭，使代理静默断连这一最需要自愈的
   * 场景下客户端永久停摆（进度冻结，且该 client 再也不会恢复）。
   */
  private watchdogFiring = false

  constructor(
    private readonly url: string,
    private readonly handlers: SseHandlers,
    /**
     * 附加请求头。用于携带应用标识（X-Client-App）：服务端据此选用本端的专属认证
     * Cookie 名。SSE 走 fetch 而非 axios，不会自动继承 apiClient 的默认头，必须显式传入。
     */
    private readonly headers?: Record<string, string>,
  ) {}

  start(): void {
    this.closed = false
    this.connect()
  }

  close(): void {
    this.closed = true
    this.clearTimers()
    this.controller?.abort()
    this.controller = null
  }

  private clearTimers(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.watchdogTimer !== null) {
      clearTimeout(this.watchdogTimer)
      this.watchdogTimer = null
    }
  }

  private connect(): void {
    if (this.closed) return

    // 每次新连接都重置看门狗标记，否则上一轮留下的 true 会让本轮 close() 的主动 abort
    // 被误判为看门狗超时（虽然 closed 已拦住重连，但语义会错，后续维护容易踩坑）
    this.watchdogFiring = false
    this.controller = new AbortController()
    fetch(this.url, {
      // 认证凭据由浏览器以 HttpOnly Cookie 形式自动携带（与 apiClient 的
      // withCredentials 对齐），前端代码不持有 token
      credentials: 'include',
      headers: this.headers,
      signal: this.controller.signal,
    })
      .then(async (resp) => {
        if (this.closed) return
        if (!resp.ok || !resp.body) {
          // 401/403/404 属于永久性错误：重连一万次也不会成功，直接放弃
          if (resp.status === 401 || resp.status === 403 || resp.status === 404) {
            const err = new Error(`SSE 连接失败: HTTP ${resp.status}`)
            // 401/403 属于凭据失效：通知调用方登出/跳转（axios 拦截器管不到 fetch 流）
            if (resp.status === 401 || resp.status === 403) {
              this.handlers.onUnauthorized?.()
            }
            this.handlers.onGiveUp?.('permanent', err)
            this.close()
            return
          }
          throw new Error(`SSE 连接失败: HTTP ${resp.status}`)
        }
        this.retryCount = 0
        this.armWatchdog()
        await this.parse(resp.body)
        // 流读完 = 后端主动关闭（例如任务完成后的 emitter.complete）
        this.clearTimers()
        if (!this.closed) {
          this.handlers.onComplete?.()
        }
      })
      .catch((err) => {
        if (this.closed) return
        // 只有 close() 主动 abort 才静默退出。看门狗超时同样会 abort，但它属于
        // 「连接已死、需要重连」，必须继续走下面的清理与重连——否则代理静默断连后
        // 这条链路永久停摆：既不 onError、也不重连、也不通知调用方降级。
        if (this.controller?.signal.aborted && !this.watchdogFiring) return
        this.clearTimers()
        this.handlers.onError?.(err)
        this.scheduleReconnect()
      })
  }

  /** 喂狗：每收到任何事件（含心跳）都重置看门狗定时器 */
  private armWatchdog(): void {
    if (this.watchdogTimer !== null) clearTimeout(this.watchdogTimer)
    this.watchdogTimer = window.setTimeout(() => {
      // 超时无消息：代理静默断连的典型表现。主动 abort 触发 catch → 重连。
      // 先打标记，让 catch 里「signal.aborted 就静默退出」的判断能把它与用户主动 close 区分开。
      this.watchdogFiring = true
      this.controller?.abort()
    }, WATCHDOG_MS)
  }

  private async parse(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    // 事件间以空行分隔（SSE 规范），逐个块解析
    const handleBlock = (block: string) => {
      if (!block.trim()) return
      let event = 'message'
      const dataLines: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
      }
      if (dataLines.length === 0) {
        // 无 data 的事件（如心跳的 event: heartbeat 块）也用来喂狗
        this.armWatchdog()
        return
      }
      this.armWatchdog()
      const raw = dataLines.join('\n')
      let data: any = raw
      try {
        data = JSON.parse(raw)
      } catch {
        // 非 JSON 则原样返回字符串
      }
      this.handlers.onMessage(event, data)
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let sep: number
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        handleBlock(block)
      }
    }

    // 收尾：处理可能残留的最后一块
    if (buffer.trim()) handleBlock(buffer)
  }

  private scheduleReconnect(): void {
    if (this.closed) return
    if (this.retryCount >= MAX_RETRIES) {
      this.handlers.onGiveUp?.('retries-exhausted', new Error(`SSE 重连 ${MAX_RETRIES} 次仍失败`))
      this.close()
      return
    }
    this.retryCount += 1
    // 指数退避：1s、2s、4s、8s……上限 15s
    const delay = Math.min(1000 * 2 ** (this.retryCount - 1), 15000)
    this.reconnectTimer = window.setTimeout(() => this.connect(), delay)
  }
}
