/**
 * 轻量长按手势（给「单词本列表」这类任意容器用）。
 *
 * 与 `useTextSelection.ts` 的区别，务必看清：
 *  - useTextSelection 绑的是**阅读正文**，管的是「选区 + 浮动菜单」，与播放/目录耦合；
 *  - 本 composable 只做一件事：在任意列表项上「按住 500ms → 回调」，不碰选区、不碰正文。
 *    单词本列表是另一个容器，直接把 useTextSelection 挂上去会影响阅读页的既有绑定，
 *    所以这里单独实现，且不改动 useTextSelection。
 *
 * 交互细节（与 useTextSelection 的长按判定逐条对齐，避免两端手感漂移）：
 *  - 500ms pointerdown 定时器；pointermove 位移 > 8px 判定为滚动/拖选 ⇒ 取消；
 *  - 触发时 `navigator.vibrate?.(12)` 给一次轻反馈（不支持的浏览器忽略）；
 *  - contextmenu preventDefault：长按/右键不弹系统菜单，只认我们自己的浮层；
 *  - **防 click 穿透**：长按后抬手浏览器仍会补一个 `click`，若不吞掉会触发整项的点击
 *    （例如「直接跳转」），绕过长按浮层里的「跳转到原书」按钮、也与禁用提示冲突。
 *    `consumeClick()` 消费一次「刚发生过长按」标记，整项 click 回调开头调用即可。
 */
import { onBeforeUnmount } from 'vue'

export interface UseLongPressOptions<T> {
  /** 长按触发：回传绑定的条目与 pointerdown 时的视口坐标（供浮层定位） */
  onLongPress: (payload: T, x: number, y: number) => void
  /** 判定时长，默认 500ms（与 Android / useTextSelection 对齐） */
  ms?: number
}

export interface LongPressBinding<T> {
  onPointerdown: (e: PointerEvent, payload: T) => void
  onPointermove: (e: PointerEvent) => void
  onPointerup: () => void
  onPointercancel: () => void
  onPointerleave: () => void
  onContextmenu: (e: MouseEvent) => void
  /** 整项 click 回调开头调用：返回 true 表示这次 click 是长按的补发，应直接吞掉 */
  consumeClick: () => boolean
}

export function useLongPress<T>(options: UseLongPressOptions<T>): LongPressBinding<T> {
  const ms = options.ms ?? 500

  let timer: number | null = null
  let start: { x: number; y: number } | null = null
  /** 本次手势是否已触发长按；留给紧随其后的 click 消费，pointerdown 时复位 */
  let fired = false

  const clear = () => {
    if (timer != null) {
      window.clearTimeout(timer)
      timer = null
    }
  }

  const onPointerdown = (e: PointerEvent, payload: T) => {
    // 鼠标只认左键：右键交给 contextmenu 分支
    if (e.pointerType === 'mouse' && e.button !== 0) return
    start = { x: e.clientX, y: e.clientY }
    fired = false
    clear()
    timer = window.setTimeout(() => {
      timer = null
      fired = true
      // 轻震一下作为「长按已生效」的反馈（不支持的浏览器没有 vibrate，忽略）
      navigator.vibrate?.(12)
      if (start) options.onLongPress(payload, start.x, start.y)
    }, ms)
  }

  const onPointermove = (e: PointerEvent) => {
    if (!start || timer == null) return
    const dx = e.clientX - start.x
    const dy = e.clientY - start.y
    // 手指/鼠标移开就是在滚动或拖选，取消长按判定
    if (dx * dx + dy * dy > 8 * 8) clear()
  }

  // 注意：pointerup 只清定时器，**不清 fired** —— 紧接着的 click 还要靠它判定是否吞掉
  const onPointerup = () => clear()
  const onPointercancel = () => clear()
  const onPointerleave = () => clear()

  const onContextmenu = (e: MouseEvent) => {
    // 长按/右键弹出的原生菜单一律拦掉，避免与自绘浮层同时出现两套
    e.preventDefault()
  }

  const consumeClick = () => {
    if (!fired) return false
    fired = false
    return true
  }

  // 组件卸载时若还有未触发的定时器，清掉，避免回调打到已卸载组件
  onBeforeUnmount(clear)

  return {
    onPointerdown,
    onPointermove,
    onPointerup,
    onPointercancel,
    onPointerleave,
    onContextmenu,
    consumeClick,
  }
}
