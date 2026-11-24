/**
 * 全局播放暂停总线。
 *
 * 401 拦截器在「业务请求遭遇登录过期」时，需要暂停正在进行的听书播放，
 * 但 apiClient 是模块级单例，不持有播放引擎实例。这里用一个极简的发布/订阅，
 * 由 Player.vue 在拿到 useAudioEngine 实例后注册自己的 pause，
 * 拦截器/其它模块通过 pauseActivePlayback() 触发，避免在模块间直接传递引擎引用。
 */

type Pauser = () => void

let activePauser: Pauser | null = null

/** Player.vue 在引擎就绪后调用，注册「暂停当前播放」的回调 */
export function setActivePauser(p: Pauser | null): void {
  activePauser = p
}

/** 暂停当前正在进行的播放（未播放/未注册时为空操作） */
export function pauseActivePlayback(): void {
  activePauser?.()
}
