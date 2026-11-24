<script setup lang="ts">
/**
 * 词典释义面板（对照 Android 的 `DictLookupSheet` / `ChinDictSheet`）。
 *
 * 两种形态同一张底部卡片：
 *  - `en`：英汉词条 —— 词形 + 音标 + 考试标签 + 多行释义 + 词形变化；
 *  - `zh`：中文单字 —— 字 + 拼音 + 按条列出的释义（多音字多条）。
 *
 * 用自绘底部卡片而不是 antdv Drawer：Drawer 的 bottom 模式必须给固定高度，
 * 而词典内容的行数差异极大（一个词 1 行、一个多音字 80 行），自绘才能「内容多高就多高、超高则滚动」。
 *
 * 底部固定一枚「☆ 加入单词本 / ★ 移出单词本」按钮：收藏态由调用方按 sw 判定后传入
 * `favorited`，点击只 emit `favorite`（词条快照 + 出处的组装是调用方的职责，见 Reader.vue）。
 */
import { computed, onBeforeUnmount, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { formatExchange, type ChineseEntry, type DictEntry } from '@/services/dict'

const props = defineProps<{
  open: boolean
  mode: 'en' | 'zh'
  loading: boolean
  /** 请求失败时的文案（网络/服务端错误）；空串表示没有错误 */
  error: string
  /** 查询用的原文（英文词 / 单个汉字） */
  word: string
  /** 词典是否已部署；false 时给「词典未安装」而不是「未收录」 */
  available: boolean
  en: DictEntry[]
  zh: ChineseEntry[]
  /** 该词是否已在单词本（按 sw 判定）；决定「☆ 加入」/「★ 移出」 */
  favorited: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  /** 点击「加入/移出单词本」；由调用方组装词条快照与出处后上报 */
  (e: 'favorite'): void
}>()

const isEmpty = computed(
  () => !props.loading && !props.error && (props.mode === 'en' ? props.en.length === 0 : props.zh.length === 0),
)

/** 中文多音字：每个读音一条，拼音用「/」连起来（与 Android 端一致，别只显示第一条） */
const zhPinyin = computed(() => {
  const list = props.zh.map((e) => e.pinyin).filter((p) => !!p)
  return Array.from(new Set(list)).join(' / ')
})

/** 英文音标取第一条非空的（同一 sw 的多个词形音标基本相同） */
const enPhonetic = computed(() => props.en.find((e) => !!e.phonetic)?.phonetic ?? '')

/** 词形变化按第一条有 exchange 的词条格式化 */
const enExchange = computed(() => {
  for (const e of props.en) {
    const text = formatExchange(e.exchange, e.word)
    if (text) return text
  }
  return ''
})

/** 柯林斯星级（0 = 无） */
const collins = computed(() => props.en.find((e) => e.collins > 0)?.collins ?? 0)
const oxford = computed(() => props.en.some((e) => e.oxford > 0))

/** 顶部标签：考试大纲 + 柯林斯 + 牛津 3000，最多 6 个，避免刷屏 */
const enTags = computed(() => {
  const tags: string[] = []
  const first = props.en[0]
  if (first) tags.push(...first.tags)
  if (collins.value > 0) tags.push(`柯林斯 ${'★'.repeat(collins.value)}`)
  if (oxford.value) tags.push('牛津 3000')
  return tags.slice(0, 6)
})

/** 面板打开时锁住页面滚动，关闭时恢复（移动端底部卡片的标准做法） */
const toggleBodyScroll = (lock: boolean) => {
  document.body.style.overflow = lock ? 'hidden' : ''
}

watch(
  () => props.open,
  (open) => toggleBodyScroll(open),
)

onBeforeUnmount(() => toggleBodyScroll(false))
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="dict-mask" @click.self="emit('close')">
      <div class="dict-sheet" role="dialog" aria-modal="true">
        <div class="dict-head">
          <div class="dict-head-main">
            <div class="dict-title">
              {{ word }}
              <span v-if="mode === 'en' && enPhonetic" class="dict-phonetic">/{{ enPhonetic }}/</span>
              <span v-else-if="mode === 'zh' && zhPinyin" class="dict-phonetic">{{ zhPinyin }}</span>
            </div>
          </div>
          <button type="button" class="dict-close" aria-label="关闭" @click="emit('close')">
            <CloseOutlined />
          </button>
        </div>

        <div class="dict-body">
          <div v-if="loading" class="dict-tip">正在查询…</div>

          <div v-else-if="error" class="dict-tip dict-tip-error">{{ error }}</div>

          <template v-else-if="mode === 'en'">
            <div v-if="enTags.length" class="dict-tags">
              <span v-for="t in enTags" :key="t" class="dict-tag">{{ t }}</span>
            </div>

            <div v-for="(entry, i) in en" :key="entry.word + '-' + i" class="dict-entry">
              <!-- 一个 sw 可能撞多个词形（take-off / Take Off / TAKEOFF）：逐个列出，别只显示第一条 -->
              <div v-if="en.length > 1" class="dict-entry-word">{{ entry.word }}</div>
              <div v-for="(line, j) in entry.translations" :key="j" class="dict-line">{{ line }}</div>
              <div v-if="entry.translations.length === 0" class="dict-line dict-line-empty">
                该词条暂无中文释义
              </div>
            </div>

            <div v-if="enExchange" class="dict-extra">词形变化：{{ enExchange }}</div>
          </template>

          <template v-else>
            <div v-for="(entry, i) in zh" :key="i" class="dict-entry">
              <div v-if="entry.pinyin" class="dict-entry-word">{{ entry.pinyin }}</div>
              <div v-for="(def, j) in entry.definitions" :key="j" class="dict-line">{{ def }}</div>
            </div>
          </template>

          <div v-if="isEmpty" class="dict-tip">
            {{ available ? '词典未收录这个词条' : '服务端未安装词典，请联系管理员' }}
          </div>

          <!-- 单词本入口：底部独立一条，移动端点击目标 ≥44px。
               loading 时禁用，避免收藏到「还没查完」的空词条快照；
               @click.stop 防止冒泡到 mask（mask 是 @click.self，双保险）。 -->
          <div class="dict-fav-bar">
            <button
              type="button"
              class="dict-fav"
              :class="{ 'dict-fav-on': favorited }"
              :aria-label="favorited ? '移出单词本' : '加入单词本'"
              :disabled="loading"
              @click.stop="emit('favorite')"
            >
              <span class="dict-fav-star">{{ favorited ? '★' : '☆' }}</span>
              <span>{{ favorited ? '移出单词本' : '加入单词本' }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.dict-mask {
  position: fixed;
  inset: 0;
  z-index: 2100;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  animation: dict-fade 0.16s ease-out;
}

.dict-sheet {
  width: 100%;
  max-width: 720px;
  max-height: 66vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 14px 14px 0 0;
  box-shadow: 0 -6px 24px rgba(0, 0, 0, 0.18);
  animation: dict-rise 0.2s ease-out;
  padding-bottom: env(safe-area-inset-bottom, 0);
}

.dict-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px 10px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.dict-head-main {
  flex: 1;
  min-width: 0;
}

.dict-title {
  font-size: 19px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
  overflow-wrap: anywhere;
}

.dict-phonetic {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 400;
  color: rgba(0, 0, 0, 0.45);
}

.dict-close {
  flex: none;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgba(0, 0, 0, 0.5);
  cursor: pointer;
  font-size: 15px;
}

.dict-close:hover {
  background: rgba(0, 0, 0, 0.06);
}

/* 单词本按钮：整行大目标，触屏/桌面都 ≥44px，不会与右上角关闭按钮挤在一起 */
.dict-fav-bar {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.dict-fav {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  min-height: 44px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
  background: #fff;
  color: rgba(0, 0, 0, 0.75);
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.dict-fav:hover {
  background: rgba(0, 0, 0, 0.04);
}

.dict-fav:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.dict-fav-on {
  border-color: #faad14;
  background: #fffbe6;
  color: #d48806;
}

.dict-fav-star {
  font-size: 17px;
  line-height: 1;
}

.dict-body {
  padding: 12px 16px 20px;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.dict-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.dict-tag {
  padding: 1px 7px;
  border-radius: 4px;
  background: #e6f4ff;
  color: #1677ff;
  font-size: 12px;
  line-height: 18px;
}

.dict-entry + .dict-entry {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed rgba(0, 0, 0, 0.08);
}

.dict-entry-word {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.65);
}

.dict-line {
  font-size: 15px;
  line-height: 1.7;
  color: rgba(0, 0, 0, 0.82);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.dict-line + .dict-line {
  margin-top: 2px;
}

.dict-line-empty,
.dict-tip {
  font-size: 14px;
  color: rgba(0, 0, 0, 0.45);
}

.dict-tip {
  padding: 18px 0;
  text-align: center;
}

.dict-tip-error {
  color: #cf1322;
}

.dict-extra {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 13px;
  color: rgba(0, 0, 0, 0.55);
  overflow-wrap: anywhere;
}

/* 夜间模式：深底亮字（.dark 挂在 <html> 上，Teleport 到 body 仍能命中） */
.dark .dict-sheet {
  background: #1f1f1f;
}

.dark .dict-head {
  border-bottom-color: rgba(255, 255, 255, 0.1);
}

.dark .dict-title {
  color: rgba(255, 255, 255, 0.9);
}

.dark .dict-phonetic {
  color: rgba(255, 255, 255, 0.45);
}

.dark .dict-close {
  color: rgba(255, 255, 255, 0.55);
}

.dark .dict-close:hover {
  background: rgba(255, 255, 255, 0.1);
}

.dark .dict-fav-bar {
  border-top-color: rgba(255, 255, 255, 0.1);
}

.dark .dict-fav {
  background: #2a2a2a;
  border-color: rgba(255, 255, 255, 0.2);
  color: rgba(255, 255, 255, 0.85);
}

.dark .dict-fav:hover {
  background: #333;
}

.dark .dict-fav-on {
  background: #3a2f14;
  border-color: #faad14;
  color: #ffd666;
}

.dark .dict-tag {
  background: #112a45;
  color: #69b1ff;
}

.dark .dict-entry + .dict-entry {
  border-top-color: rgba(255, 255, 255, 0.12);
}

.dark .dict-entry-word {
  color: rgba(255, 255, 255, 0.65);
}

.dark .dict-line {
  color: rgba(255, 255, 255, 0.85);
}

.dark .dict-line-empty,
.dark .dict-tip {
  color: rgba(255, 255, 255, 0.45);
}

.dark .dict-tip-error {
  color: #ff7875;
}

.dark .dict-extra {
  border-top-color: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.55);
}

@keyframes dict-fade {
  from {
    opacity: 0;
  }
}

@keyframes dict-rise {
  from {
    transform: translateY(16px);
  }
}
</style>
