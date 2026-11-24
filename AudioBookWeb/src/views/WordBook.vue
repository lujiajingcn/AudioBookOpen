<script setup lang="ts">
/**
 * 单词本列表（Web 端从零新建）。
 *
 * 刻意**不包一层 <Card>**：本视图作为「个人中心 → 单词本」TabPane 内嵌（父级已有 Card），
 * 自带 Card 会形成双层边框；若将来改挂独立路由，由调用方决定是否包 Card。
 *
 * 两条交互路径：
 *  - 移动端**长按**某词 500ms ⇒ 弹「跳转到原书」浮层（无出处/无 storeBookId 时禁用并提示）；
 *  - 桌面端**短按**整项 ⇒ 直接跳转（长按是移动端主路径，鼠标长按不便）。
 *    长按后浏览器补发的 click 由 useLongPress.consumeClick() 吞掉，避免绕过浮层直接跳转。
 *
 * 跳转只走 `/reader/:bookId`，**不走 `/player/:taskId`**：
 * 书城书能在 Web 打开的唯一键是 storeBookId；`/player/:taskId` 依赖 TTS 任务归属，
 * 书城书的 task 常属管理员、无音频书 taskId 为空，走 /player 必然 403 或白屏。
 */
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { List, Button, Popconfirm, message } from 'ant-design-vue'
import { DeleteOutlined, BookOutlined } from '@ant-design/icons-vue'
import { wordBook, wordBookVersion, type WordBookItem } from '@/services/wordBook'
import { useLongPress } from '@/composables/useLongPress'

const router = useRouter()

const list = ref<WordBookItem[]>(wordBook.list())
// 云端同步完成 / 增删后 wordBookVersion 自增 ⇒ 重新读缓存刷新列表
watch(wordBookVersion, () => {
  list.value = wordBook.list()
})

onMounted(() => {
  // 拉取云端单词本：跨端同步后本页自动刷新（清缓存/换设备也能看到历史收藏）
  wordBook.ensureLoaded()
})

/** 释义：wire 是 LF(\n) 分隔字符串 ⇒ 切行去空，逐行展示 */
const translationLines = (item: WordBookItem): string[] =>
  (item.translations || '').split('\n').map((s) => s.trim()).filter(Boolean)

/** 出处副标题：书名 · 第 N 段；无出处（旧数据 6 键全 null）时返回空串，模板隐藏 */
const sourceText = (item: WordBookItem): string => {
  const parts: string[] = []
  if (item.bookName) parts.push(item.bookName)
  if (item.segmentNumber != null && item.segmentNumber > 0) parts.push(`第 ${item.segmentNumber} 段`)
  return parts.join(' · ')
}

/**
 * 「跳转到原书」禁用原因（返回空串 = 可跳转）。
 *
 * 全部 6 键可空：旧数据/服务端 null 时绝不崩、绝不白屏，只禁用 + 给一句人话。
 * 核心判据是 storeBookId —— Web 只能打开书城书；本地书（storeBookId=null）即便有
 * 书名/段号也无法在 Web 打开。两类提示分开，便于用户区分「没记录」与「不是书城书」。
 */
const jumpReason = (item: WordBookItem): string => {
  if (item.storeBookId == null || item.storeBookId <= 0) {
    const hasAnySource =
      item.bookName || item.bookKey || item.segmentNumber != null || item.sentenceText
    return hasAnySource ? '该书不在书城，无法在 Web 打开' : '该词没有记录出处'
  }
  return ''
}

const canJump = (item: WordBookItem): boolean => !jumpReason(item)

/** 打开该书并定位到对应句子：定位 query 由 Reader.vue 消费（segment/wordJump/sentence） */
const goToBook = (item: WordBookItem) => {
  const reason = jumpReason(item)
  if (reason) {
    message.info(reason)
    return
  }
  closeMenu()
  // sentenceText 是定位第一判据（段号会漂移），segment 仅兜底；wordJump=1 让阅读页
  // 跳过「继续上次阅读」确认，别让进度恢复把用户刚跳进来的定位抢走。
  router.push({
    path: `/reader/${item.storeBookId}`,
    query: {
      name: item.bookName || '',
      segment: item.segmentNumber != null ? String(item.segmentNumber) : '',
      wordJump: '1',
      // sentenceText 上限 20000 字，直接塞进 URL query 会编码成数万字符，可能超出
      // 浏览器/代理对 URL 长度的限制导致跳转失败。取前 300 字已足够在 rows 里按内容
      // 命中该行（定位第一判据），段号 query 仍作兜底；存储侧仍保留完整原文。
      sentence: (item.sentenceText || '').slice(0, 300),
    },
  })
}

// ---------- 长按浮层 ----------

interface MenuState {
  open: boolean
  item: WordBookItem | null
  top: number
  left: number
}
const menu = ref<MenuState>({ open: false, item: null, top: 0, left: 0 })

const closeMenu = () => {
  menu.value.open = false
  menu.value.item = null
}

/** 长按回调：在 pointerdown 视口坐标附近弹浮层，实测宽高后做视口夹取 */
const openMenu = (item: WordBookItem, x: number, y: number) => {
  const w = 208
  const h = 96
  const margin = 8
  const gap = 12
  let top = y - gap - h
  if (top < margin) top = y + gap // 上方放不下就翻到下方（选区靠近屏幕顶部时常见）
  top = Math.min(Math.max(margin, top), window.innerHeight - margin - h)
  const left = Math.max(margin, Math.min(x - w / 2, window.innerWidth - margin - w))
  menu.value = { open: true, item, top, left }
}

const lp = useLongPress<WordBookItem>({ onLongPress: openMenu })

/**
 * 整项点击（桌面短按直达）。
 * 长按弹浮层后浏览器仍会补一个 click —— 不吞掉就会直接跳转，绕过浮层按钮，
 * 也会和「无出处/禁用」提示冲突（用户还没看到提示就被带走了）。
 */
const onItemClick = (item: WordBookItem) => {
  if (lp.consumeClick()) return
  if (!canJump(item)) {
    message.info(jumpReason(item))
    return
  }
  goToBook(item)
}

const onDelete = async (item: WordBookItem) => {
  await wordBook.remove(item)
  list.value = wordBook.list()
  message.success('已移出单词本')
}
</script>

<template>
  <div class="word-book">
    <List
      :data-source="list"
      :locale="{ emptyText: '还没有收藏单词：阅读时长按查词，在词典面板点「☆ 加入单词本」即可记录出处' }"
    >
      <template #renderItem="{ item }">
        <List.Item class="wb-item">
          <!-- 手势绑在内部 div 而非 List.Item 组件上，避免组件 listener fallthrough 的不确定性 -->
          <div
            class="wb-row"
            @pointerdown="lp.onPointerdown($event, item)"
            @pointermove="lp.onPointermove"
            @pointerup="lp.onPointerup"
            @pointercancel="lp.onPointercancel"
            @pointerleave="lp.onPointerleave"
            @contextmenu="lp.onContextmenu"
            @click="onItemClick(item)"
          >
            <div class="wb-main">
              <div class="wb-head">
                <span class="wb-word">{{ item.word }}</span>
                <span v-if="item.phonetic" class="wb-phonetic">/{{ item.phonetic }}/</span>
                <span v-if="sourceText(item)" class="wb-source">{{ sourceText(item) }}</span>
              </div>
              <div class="wb-trans">
                <div v-for="(line, i) in translationLines(item)" :key="i" class="wb-line">{{ line }}</div>
                <span v-if="!translationLines(item).length" class="wb-empty">暂无释义</span>
              </div>
            </div>
            <!-- 删除入口：@click.stop 阻止冒泡到整项 click（否则点删除会顺带跳转） -->
            <Popconfirm
              title="移出单词本？"
              ok-text="移出"
              cancel-text="取消"
              @confirm="onDelete(item)"
            >
              <Button type="text" size="small" danger class="wb-del" @click.stop>
                <template #icon><DeleteOutlined /></template>
                删除
              </Button>
            </Popconfirm>
          </div>
        </List.Item>
      </template>
    </List>

    <!-- 长按浮层：Teleport 到 body + fixed 定位 + 视口夹取（对照 TextActionMenu.vue）。
         遮罩接管「点外部关闭」；菜单 @pointerdown.prevent 防点按钮时选区/焦点被清，
         @click.stop 防菜单内点击冒泡到遮罩把自己关掉。 -->
    <Teleport to="body">
      <div v-if="menu.open" class="wb-menu-mask" @click="closeMenu" @contextmenu.prevent>
        <div
          class="wb-menu"
          :style="{ top: menu.top + 'px', left: menu.left + 'px' }"
          @pointerdown.prevent
          @click.stop
        >
          <div class="wb-menu-word">{{ menu.item?.word }}</div>
          <Button
            type="primary"
            size="small"
            block
            :disabled="menu.item ? !canJump(menu.item) : true"
            @click="menu.item && goToBook(menu.item)"
          >
            <template #icon><BookOutlined /></template>
            跳转到原书
          </Button>
          <div v-if="menu.item && jumpReason(menu.item)" class="wb-menu-tip">
            {{ jumpReason(menu.item) }}
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* 整项：左释义 + 右删除，撑满 List.Item 宽度，点击目标足够大 */
.wb-item {
  padding: 0;
}

.wb-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 10px 4px;
  cursor: pointer;
  /* 长按选词/点按不希望被系统长按气泡打断 */
  -webkit-touch-callout: none;
}

.wb-main {
  flex: 1;
  min-width: 0;
}

.wb-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}

.wb-word {
  font-size: 16px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
}

.wb-phonetic {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.wb-source {
  font-size: 12px;
  color: #1677ff;
  background: #e6f4ff;
  border-radius: 4px;
  padding: 0 6px;
}

.wb-trans {
  margin-top: 4px;
}

.wb-line {
  font-size: 13px;
  line-height: 1.6;
  color: rgba(0, 0, 0, 0.65);
  overflow-wrap: anywhere;
}

.wb-empty {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.35);
}

.wb-del {
  flex: none;
}

/* 触屏：加大点击目标（Material 建议 ≥44px） */
@media (pointer: coarse) {
  .wb-row {
    padding: 12px 4px;
  }

  .wb-del {
    min-height: 44px;
  }
}

/* ---------- 长按浮层 ---------- */
.wb-menu-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
}

.wb-menu {
  position: fixed;
  z-index: 2001;
  width: 208px;
  padding: 10px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.18);
}

.wb-menu-word {
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
  margin-bottom: 8px;
  overflow-wrap: anywhere;
}

.wb-menu-tip {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: rgba(0, 0, 0, 0.45);
}

/* 夜间模式：浮层深底亮字（Teleport 到 body 仍能被挂在 <html> 上的 .dark 命中） */
.dark .wb-word {
  color: rgba(255, 255, 255, 0.9);
}

.dark .wb-phonetic {
  color: rgba(255, 255, 255, 0.45);
}

.dark .wb-source {
  background: #112a45;
  color: #69b1ff;
}

.dark .wb-line {
  color: rgba(255, 255, 255, 0.7);
}

.dark .wb-empty,
.dark .wb-menu-tip {
  color: rgba(255, 255, 255, 0.45);
}

.dark .wb-menu {
  background: #1f1f1f;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.6);
}

.dark .wb-menu-word {
  color: rgba(255, 255, 255, 0.9);
}
</style>
