<script setup lang="ts">
/**
 * 长按选词后弹出的浮动操作菜单（对照 Android 的 ActionMode 浮动工具栏）。
 *
 * 只在「菜单项与选区语义相关」时出现，具体显隐由调用方决定（见 Reader 的 menuItems）：
 * 复制 / 收藏 / 翻译 / 查字典 / 从此处读 —— 与 Android 端 `BookContentActivity`
 * 里 `MENU_*` 五项一一对应。
 *
 * 交互细节（都是踩过的坑）：
 *  - `@pointerdown.prevent`：不拦的话，点按钮会先让浏览器把选区清掉，
 *    某些浏览器（Safari）还会顺便把我们的菜单一起收掉，表现为「点不动」；
 *  - 定位用 `position: fixed` + 实测宽高：正文容器自己会滚动，
 *    用 absolute 会跟着滚走；而选区在视口坐标里，fixed 才对得上；
 *  - 上方放不下就翻到选区下方（手机上选区靠近屏幕顶部时很常见）。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch, type Component } from 'vue'
import {
  CopyOutlined,
  StarOutlined,
  TranslationOutlined,
  ReadOutlined,
  SoundOutlined,
} from '@ant-design/icons-vue'
import type { TextActionKey, TextSelectionRect } from '@/composables/useTextSelection'

const props = defineProps<{
  open: boolean
  /** 选区包围盒（视口坐标）；null 时不显示 */
  anchor: TextSelectionRect | null
  items: Array<{ key: TextActionKey; label: string }>
}>()

const emit = defineEmits<{ (e: 'action', key: TextActionKey): void }>()

/** 图标按 key 固定映射：菜单项文案可随选区变化，图标不该由调用方重复声明 */
const ICONS: Record<TextActionKey, Component> = {
  copy: CopyOutlined,
  favorite: StarOutlined,
  translate: TranslationOutlined,
  dict: ReadOutlined,
  readFromHere: SoundOutlined,
}

const menuRef = ref<HTMLElement | null>(null)
const position = ref({ top: 0, left: 0 })
/** true = 菜单翻到了选区下方（箭头在上边） */
const below = ref(false)

const visible = computed(() => props.open && !!props.anchor && props.items.length > 0)

/** 实测宽高后定位：先试选区上方，放不下就翻到下方，再夹进视口 */
const place = () => {
  const el = menuRef.value
  const a = props.anchor
  if (!el || !a) return
  const gap = 10
  const margin = 8
  const w = el.offsetWidth
  const h = el.offsetHeight

  let top = a.top - gap - h
  let flipped = false
  if (top < margin) {
    top = a.bottom + gap
    flipped = true
  }
  // 下方也放不下时（极矮视口）贴着底边，至少保证可点
  if (top + h > window.innerHeight - margin) {
    top = Math.max(margin, window.innerHeight - margin - h)
  }
  const center = (a.left + a.right) / 2
  const left = Math.min(Math.max(margin, center - w / 2), window.innerWidth - margin - w)
  position.value = { top, left }
  below.value = flipped
}

watch(
  () => [props.open, props.anchor, props.items.map((i) => i.key).join(',')] as const,
  async () => {
    if (!props.open) return
    // 先渲染再量：offsetWidth 必须等 DOM 挂上
    await nextTick()
    place()
  },
  { immediate: true, deep: true },
)

const onResize = () => {
  if (props.open) place()
}
window.addEventListener('resize', onResize)
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const onAction = (key: TextActionKey) => emit('action', key)
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="menuRef"
      data-text-action-menu
      class="text-action-menu"
      :class="{ below }"
      :style="{ top: position.top + 'px', left: position.left + 'px' }"
      @pointerdown.prevent
      @contextmenu.prevent
    >
      <button
        v-for="item in items"
        :key="item.key"
        type="button"
        class="tam-item"
        @click.stop="onAction(item.key)"
      >
        <component :is="ICONS[item.key]" class="tam-icon" />
        <span class="tam-label">{{ item.label }}</span>
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
/* 深色胶囊 + 白字：与 Android 的浮动工具栏观感一致，在日/夜两种主题下都能看清 */
.text-action-menu {
  position: fixed;
  z-index: 2000;
  display: flex;
  align-items: stretch;
  padding: 2px;
  border-radius: 10px;
  background: #2f3136;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.32);
  max-width: calc(100vw - 16px);
  overflow: hidden;
}

/* 指向选区的小三角 */
.text-action-menu::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -5px;
  width: 10px;
  height: 10px;
  margin-left: -5px;
  background: #2f3136;
  transform: rotate(45deg);
  border-radius: 1px;
}

.text-action-menu.below::after {
  bottom: auto;
  top: -5px;
}

.tam-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 9px 11px;
  border: 0;
  background: transparent;
  color: rgba(255, 255, 255, 0.92);
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.15s;
}

.tam-item + .tam-item {
  box-shadow: inset 1px 0 0 rgba(255, 255, 255, 0.12);
}

.tam-item:hover,
.tam-item:focus-visible {
  background: rgba(255, 255, 255, 0.12);
  outline: none;
}

.tam-icon {
  font-size: 15px;
}

/* 触屏：加大点击目标（Material 建议 48dp） */
@media (pointer: coarse) {
  .tam-item {
    padding: 12px 12px;
    font-size: 14px;
  }
}
</style>
