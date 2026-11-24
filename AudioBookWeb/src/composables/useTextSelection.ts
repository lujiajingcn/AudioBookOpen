/**
 * 阅读页长按选词 / 选区管理（对照 Android `LineAdapter` + `FallbackSelectionHandles`）。
 *
 * <h2>为什么要自己实现</h2>
 * 桌面浏览器要「拖选」，触屏浏览器要「长按选词」，两者产生的选区来源不同
 * （系统选区 / 我们自己造的 Range），而菜单要锚定在选区上方随选区变化 ——
 * 这套逻辑与 `Reader.vue` 的播放、目录、行映射无关，抽出来才能单独说清楚。
 *
 * <h2>与 Android 端一一对应的三个行为</h2>
 * <ol>
 *   <li><b>系统选区优先</b>：移动端 Chrome/Safari 长按会自己选中一个词，此时直接用它，
 *       不要用我们自己取词的结果覆盖（Android 也是「系统菜单活着就不抢」）。</li>
 *   <li><b>系统没选中时兜底取词</b>：中文取**落点单字**（配合「查字典」），
 *       英文取**落点整词**（单字母对「翻译」没有意义）。取词规则见 {@link selectWordAt}。</li>
 *   <li><b>点空白/滚动即收</b>：Android 的 ActionMode 也是这样退出的。</li>
 * </ol>
 *
 * <h2>一个必须处理的冲突</h2>
 * 正文行有 `@click`（点哪句就从哪句起播）。长按选词后抬手，浏览器仍会补一个 `click`，
 * 于是「选词」会顺带触发播放。这里不做全局拦截（那会误伤正常点击），
 * 而是由调用方在点击回调里先判 `selection` 是否为空 —— 见 Reader 的 `onRowClick`。
 */
import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export interface TextSelectionRect {
  top: number
  bottom: number
  left: number
  right: number
}

/**
 * 浮动菜单项标识。
 *
 * 放在这里而不是组件里：`<script setup>` 不允许具名导出（Vue 编译期直接报错），
 * 而菜单组件与阅读页都要用这个类型 —— 只能放在两者都依赖的模块中。
 * 五个 key 与 Android `BookContentActivity` 的 `MENU_*` 一一对应。
 */
export type TextActionKey = 'copy' | 'favorite' | 'translate' | 'dict' | 'readFromHere'

export interface TextSelectionInfo {
  /** 原始选区文本（保留换行，供「复制」原样写入剪贴板） */
  text: string
  /** 折叠空白后的单行文本（供菜单提示、查词回显） */
  displayText: string
  /** 选区在视口中的包围盒（菜单据此定位；fixed 定位，与页面滚动无关） */
  rect: TextSelectionRect
  /** 选区起点所在段落下标；-1 表示定位不到（落在段号角标、句间空白等） */
  paragraphIndex: number
}

export interface UseTextSelectionOptions {
  /** 正文容器（只在这个范围内响应长按与选区） */
  container: Ref<HTMLElement | null>
  /** 把 DOM 节点解析成段落下标（由调用方按 `.row-part[data-p]` 实现） */
  resolveParagraph: (node: Node) => number
  /** 长按判定时长；与 Android 的 500ms 对齐（过短会与滚动起手冲突） */
  longPressMs?: number
}

export function useTextSelection(options: UseTextSelectionOptions) {
  const longPressMs = options.longPressMs ?? 500

  /** 当前选区；null = 无选区（菜单隐藏） */
  const selection = ref<TextSelectionInfo | null>(null)

  let pressTimer: number | null = null
  let changeTimer: number | null = null
  let pointerStart: { x: number; y: number } | null = null
  let longPressFired = false
  /** 自己程序化设置选区时置 true，避免 selectionchange 把同一份选区再发布一遍 */
  let suppressChange = false

  const clearTimer = () => {
    if (pressTimer != null) {
      window.clearTimeout(pressTimer)
      pressTimer = null
    }
  }

  /** 收起菜单并清掉原生选区（滚动、点空白、切书都走它） */
  const clear = () => {
    clearTimer()
    selection.value = null
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      try {
        sel.removeAllRanges()
      } catch {
        // 某些浏览器在选区被外部清掉后 removeAllRanges 会抛，忽略即可
      }
    }
  }

  // ---------------- 选区读取 ----------------

  const rectOfRange = (range: Range): TextSelectionRect | null => {
    const rect = range.getBoundingClientRect()
    if (rect.width > 0 || rect.height > 0) {
      return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right }
    }
    // 多行选区在部分浏览器上 getBoundingClientRect 会返回全 0，退回第一条矩形
    const first = range.getClientRects()[0]
    if (!first) return null
    return { top: first.top, bottom: first.bottom, left: first.left, right: first.right }
  }

  /** 读取当前原生选区；不在正文容器内、或折叠选区一律返回 null */
  const readSelection = (): TextSelectionInfo | null => {
    const root = options.container.value
    const sel = window.getSelection()
    if (!root || !sel || sel.rangeCount === 0 || sel.isCollapsed) return null
    const range = sel.getRangeAt(0)
    if (!root.contains(range.commonAncestorContainer)) return null
    const raw = sel.toString()
    const text = raw.replace(/^[\s\u00a0]+|[\s\u00a0]+$/g, '')
    if (!text) return null
    const rect = rectOfRange(range)
    if (!rect) return null
    return {
      text,
      displayText: text.replace(/\s+/g, ' '),
      rect,
      paragraphIndex: options.resolveParagraph(range.startContainer),
    }
  }

  const publish = (info: TextSelectionInfo) => {
    // 同一份选区（文字 + 位置都没变）不重复写 ref，避免 mousemove 级别的重渲染
    const cur = selection.value
    if (
      cur &&
      cur.text === info.text &&
      cur.rect.top === info.rect.top &&
      cur.rect.left === info.rect.left
    ) {
      return
    }
    selection.value = info
  }

  // ---------------- 落点取词 ----------------

  /** 取落点处的文本节点与偏移；DOM 没有 caretRangeFromPoint 时返回 null */
  const caretAt = (x: number, y: number): { node: Text; offset: number } | null => {
    const doc = document as Document & {
      caretRangeFromPoint?: (x: number, y: number) => Range | null
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    }
    let range: Range | null = null
    if (typeof doc.caretRangeFromPoint === 'function') {
      range = doc.caretRangeFromPoint(x, y)
    } else if (typeof doc.caretPositionFromPoint === 'function') {
      const pos = doc.caretPositionFromPoint(x, y)
      if (pos) {
        range = document.createRange()
        range.setStart(pos.offsetNode, pos.offset)
        range.collapse(true)
      }
    }
    if (!range || range.startContainer.nodeType !== Node.TEXT_NODE) return null
    return { node: range.startContainer as Text, offset: range.startOffset }
  }

  /** 英文字母/数字（含常见带音符的拉丁字母；CJK 由 {@link isCjkCodePoint} 单独处理） */
  const isWordChar = (ch: string) => /[\p{L}\p{N}]/u.test(ch) && !isCjkCodePoint(ch)

  /** 词内连接符：连字符 / 撇号，且**两侧都是字母数字**才算词的一部分 */
  const isInnerJoiner = (text: string, i: number) => {
    const c = text[i]
    if (c !== '-' && c !== "'" && c !== '\u2019' && c !== '\u2018') return false
    const prev = text[i - 1] ?? ''
    const next = text[i + 1] ?? ''
    return isWordChar(prev) && isWordChar(next)
  }

  /**
   * 在落点处选中一个词并发布。
   *
   * 规则与 Android `DictionaryManager.wordAt` 一致：
   *  - 落点是汉字 ⇒ 只选**这一个字**（「查字典」只对单字有意义）；
   *  - 落点是字母数字 ⇒ 向两侧扩到整词，词内连字符/撇号并入
   *    （`take-off` / `don't` 在 ECDICT 里是真实词条，切开就查不到了）；
   *  - 落点是标点/空白/段号角标 ⇒ **不选**，返回 null。
   *
   * 最后一条是刻意的：找「最近的词」会让用户点句号弹出上一个词，
   * 定位错觉比「没反应」更糟（Android 端同样的取舍）。
   */
  const selectWordAt = (x: number, y: number): TextSelectionInfo | null => {
    const root = options.container.value
    const caret = caretAt(x, y)
    if (!root || !caret) return null
    const { node, offset } = caret
    const parent = node.parentElement
    if (!parent || !root.contains(parent)) return null
    // 段号角标、状态标签不是正文，不参与取词
    if (parent.closest('.para-no, .para-tag')) return null
    if (!parent.closest('.row-part, .row, .para')) return null

    const text = node.data
    if (!text) return null
    const index = Math.min(Math.max(offset, 0), text.length - 1)
    const ch = text[index]
    if (!ch) return null

    let start = index
    let end = index + 1
    if (isCjkCodePoint(ch)) {
      // 汉字：单字，不扩展
    } else if (isWordChar(ch)) {
      while (start > 0 && (isWordChar(text[start - 1]) || isInnerJoiner(text, start - 1))) start--
      while (end < text.length && (isWordChar(text[end]) || isInnerJoiner(text, end))) end++
    } else {
      return null
    }

    const range = document.createRange()
    range.setStart(node, start)
    range.setEnd(node, end)
    const sel = window.getSelection()
    if (!sel) return null
    suppressChange = true
    try {
      sel.removeAllRanges()
      sel.addRange(range)
    } finally {
      // selectionchange 是异步派发的，放开标记要等它跑完
      window.setTimeout(() => {
        suppressChange = false
      }, 0)
    }
    const rect = rectOfRange(range)
    if (!rect) return null
    const word = text.slice(start, end)
    return {
      text: word,
      displayText: word,
      rect,
      paragraphIndex: options.resolveParagraph(node),
    }
  }

  // ---------------- 事件 ----------------

  const onPointerDown = (e: PointerEvent) => {
    // 鼠标只认左键：右键走 contextmenu 分支
    if (e.pointerType === 'mouse' && e.button !== 0) return
    pointerStart = { x: e.clientX, y: e.clientY }
    longPressFired = false
    clearTimer()
    pressTimer = window.setTimeout(() => {
      pressTimer = null
      // ① 系统已经选好了（移动端长按）：以系统选区为准
      const native = readSelection()
      if (native) {
        publish(native)
        longPressFired = true
        return
      }
      // ② 系统没起选区：自己取词（中文单字 / 英文整词）
      if (!pointerStart) return
      const info = selectWordAt(pointerStart.x, pointerStart.y)
      if (info) {
        publish(info)
        longPressFired = true
        // 轻震一下作为「长按已生效」的反馈（不支持的浏览器没有 vitbrate，忽略）
        navigator.vibrate?.(12)
      }
    }, longPressMs)
  }

  const onPointerMove = (e: PointerEvent) => {
    if (!pointerStart || pressTimer == null) return
    const dx = e.clientX - pointerStart.x
    const dy = e.clientY - pointerStart.y
    // 手指/鼠标移开就是在滚动或拖选，取消长按判定
    if (dx * dx + dy * dy > 8 * 8) clearTimer()
  }

  const onPointerUp = () => {
    const wasLongPress = longPressFired
    clearTimer()
    if (wasLongPress) {
      longPressFired = false
      // 长按取词后浏览器可能又派发一次 pointerup/click，把选区一并保住即可
      return
    }
    // 拖选结束：立刻读一次（同步，供 click 判断），下一帧再读一次
    // （部分浏览器 pointerup 之后才把选区定下来）
    const info = readSelection()
    if (info) publish(info)
    window.setTimeout(() => {
      const later = readSelection()
      if (later) publish(later)
    }, 0)
  }

  const onContextMenu = (e: MouseEvent) => {
    const root = options.container.value
    if (!root) return
    const target = e.target as HTMLElement | null
    if (!target || !root.contains(target)) return
    // 右键/长按弹出的原生菜单一律拦掉，换成我们自己的浮动菜单
    // （原生菜单里的「复制」我们也有，其余项与阅读无关）
    e.preventDefault()
    const native = readSelection()
    if (native) {
      publish(native)
      return
    }
    const info = selectWordAt(e.clientX, e.clientY)
    if (info) publish(info)
  }

  const onSelectionChange = () => {
    if (suppressChange) return
    if (changeTimer != null) window.clearTimeout(changeTimer)
    // 拖选过程中 selectionchange 会连续触发：防抖到「停手」后再弹菜单，
    // 否则菜单会跟着鼠标一路抖动
    changeTimer = window.setTimeout(() => {
      changeTimer = null
      const info = readSelection()
      if (info) publish(info)
      else if (selection.value) selection.value = null
    }, 200)
  }

  /** 点在正文与菜单之外：收起（但不阻止这次点击，用户可能就是点行去播放） */
  const onDocumentPointerDown = (e: PointerEvent) => {
    if (!selection.value) return
    const target = e.target as HTMLElement | null
    const root = options.container.value
    if (!target) return
    if (root?.contains(target)) return
    if (target.closest('[data-text-action-menu]')) return
    clear()
  }

  /** 正文滚动（容器自身或页面）：菜单锚点会错位，直接收起，与 Android 一致 */
  const onScroll = () => {
    if (selection.value || pressTimer != null) clear()
  }

  let boundRoot: HTMLElement | null = null

  const bind = (root: HTMLElement | null) => {
    if (boundRoot === root) return
    unbind()
    if (!root) return
    boundRoot = root
    root.addEventListener('pointerdown', onPointerDown)
    root.addEventListener('pointermove', onPointerMove)
    root.addEventListener('pointerup', onPointerUp)
    root.addEventListener('pointercancel', onPointerUp)
    root.addEventListener('contextmenu', onContextMenu)
    root.addEventListener('scroll', onScroll, { passive: true })
    document.addEventListener('selectionchange', onSelectionChange)
    document.addEventListener('pointerdown', onDocumentPointerDown, true)
    window.addEventListener('scroll', onScroll, { passive: true, capture: true })
  }

  const unbind = () => {
    if (!boundRoot) return
    boundRoot.removeEventListener('pointerdown', onPointerDown)
    boundRoot.removeEventListener('pointermove', onPointerMove)
    boundRoot.removeEventListener('pointerup', onPointerUp)
    boundRoot.removeEventListener('pointercancel', onPointerUp)
    boundRoot.removeEventListener('contextmenu', onContextMenu)
    boundRoot.removeEventListener('scroll', onScroll)
    document.removeEventListener('selectionchange', onSelectionChange)
    document.removeEventListener('pointerdown', onDocumentPointerDown, true)
    window.removeEventListener('scroll', onScroll, { capture: true })
    boundRoot = null
  }

  // 正文容器是 v-if 渲染的（有 rows 才出现），加载完成后才存在 ⇒ 必须监听 ref 变化
  watch(options.container, (el) => bind(el), { immediate: true })

  onBeforeUnmount(() => {
    clearTimer()
    if (changeTimer != null) window.clearTimeout(changeTimer)
    unbind()
  })

  return { selection, clear, selectWordAt }
}

/** 汉字码位判定（基本区 + 扩展 A；与 dict.ts 的 isCjkChar 同口径） */
function isCjkCodePoint(ch: string): boolean {
  const code = ch.codePointAt(0) ?? 0
  return (code >= 0x4e00 && code <= 0x9fff) || (code >= 0x3400 && code <= 0x4dbf)
}
