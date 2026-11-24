<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue'
import { useRouter } from 'vue-router'

/**
 * v5/W6：封面底色过闸。
 *
 * 原实现把后端字段直接拼进 :style，非 #RRGGBB 的值会让卡片底色错乱（Vue 的 style 绑定
 * 无法执行脚本，所以不是 XSS，但属于「外部字符串未经校验进入样式」）。
 * 与 AudioBookManager/AdminBooks.vue 的 safeCoverColor 同一口径。
 */
const safeCoverColor = (color?: string | null): string =>
  /^#[0-9a-fA-F]{6}$/.test(color || '') ? (color as string) : '#78909C'

import {
  Card,
  Input,
  Button,
  Tag,
  Modal,
  message,
  Spin,
  Empty,
  Typography,
  Descriptions,
  Space,
  Pagination,
  Dropdown,
  Menu,
} from 'ant-design-vue'
import {
  SearchOutlined,
  DownloadOutlined,
  BookOutlined,
  AudioOutlined,
  CheckCircleOutlined,
  ReadOutlined,
  InfoCircleOutlined,
  PlusOutlined,
  BookFilled,
} from '@ant-design/icons-vue'
import { storeApi, shelfApi, assertDownloadable, extractBlobErrorMessage, type StoreBook, type StoreCategory, type StoreTag } from '@/services/api'
import { formatFileSize } from '@audiobook/shared'
import apiClient from '@/services/apiClient'

const { Title, Paragraph } = Typography
const router = useRouter()

// ---------- 状态 ----------

const loading = ref(false)
const browseMode = ref(false)

const hotBooks = ref<StoreBook[]>([])
const newBooks = ref<StoreBook[]>([])
const bookList = ref<StoreBook[]>([])
const categories = ref<StoreCategory[]>([])
/** 标签栏数据（标签名 + 在售书籍数），来自 /store/home 与 /store/tags */
const tags = ref<StoreTag[]>([])

const currentCategory = ref<string>('')
/**
 * 当前选中的标签（空串 = 不按标签筛选）。
 *
 * 与 currentCategory **并存**：分类是单选业务归属（决定磁盘目录），标签是多选描述，
 * 两者可以同时生效（「分类：历史 + 标签：战争」）。这与「点分类就清空一切」的
 * 互斥式筛选不同，清空标签只在点分类栏的「推荐/全部」时发生。
 */
const currentTag = ref<string>('')
const searchKeyword = ref('')

// 封面 blob URL 缓存：bookId -> objectURL
const coverUrls = ref<Map<number, string>>(new Map())
// 组件卸载标记：封面请求是异步的，卸载后到达的响应不应再写入/泄漏 objectURL
let bookCoversDisposed = false

// 详情弹窗
const detailVisible = ref(false)
const detailBook = ref<StoreBook | null>(null)
const downloading = ref(false)
const downloadingAudio = ref(false)

// ---------- 书架状态 ----------

/**
 * 已在书架中的书城书籍 id 集合。
 *
 * 用一次 /shelf/ids 拉全量，而不是逐本 contains —— 书城一页有 24 本，
 * 逐本查会打出 24 个请求。加入/移出后就地维护这个 Set，无须重新拉取。
 */
const shelfIds = ref<Set<number>>(new Set())
/** 正在提交「加入/移出」的书 id，用于禁用按钮防连点 */
const shelfPending = ref<Set<number>>(new Set())

const inShelf = (book?: StoreBook | null) => !!book && shelfIds.value.has(book.id)

const loadShelfIds = async () => {
  try {
    const res = await shelfApi.ids()
    if (res.data.success) {
      shelfIds.value = new Set(res.data.bookIds || [])
    }
  } catch (e) {
    // 书架状态拿不到不该阻塞书城浏览：静默降级为「都没在书架」
    // （卡片不显示角标、菜单显示「加入书架」，点下去仍会由服务端幂等兜底）
  }
}

/**
 * 加入书架 / 移出书架（按当前状态自动选择）。
 *
 * 乐观更新 Set 让角标与菜单文案立即变化；失败则回滚并提示，
 * 避免出现「显示已加入但服务端其实没有」的假状态。
 */
const toggleShelf = async (book: StoreBook) => {
  if (shelfPending.value.has(book.id)) return
  const wasInShelf = shelfIds.value.has(book.id)

  shelfPending.value = new Set(shelfPending.value).add(book.id)
  const next = new Set(shelfIds.value)
  if (wasInShelf) next.delete(book.id)
  else next.add(book.id)
  shelfIds.value = next

  try {
    if (wasInShelf) {
      await shelfApi.remove(book.id)
      message.success(`《${book.title}》已移出书架`)
    } else {
      const res = await shelfApi.add(book.id)
      // added=false 表示本来就在书架里（多端同时操作等），文案要区分开
      if (res.data.added === false) {
        message.info(`《${book.title}》已在书架中`)
      } else {
        message.success(`《${book.title}》已加入书架`)
      }
    }
  } catch (e) {
    // 回滚到操作前的状态
    const rollback = new Set(shelfIds.value)
    if (wasInShelf) rollback.add(book.id)
    else rollback.delete(book.id)
    shelfIds.value = rollback
    message.error(wasInShelf ? '移出书架失败，请稍后重试' : '加入书架失败，请稍后重试')
  } finally {
    const pending = new Set(shelfPending.value)
    pending.delete(book.id)
    shelfPending.value = pending
  }
}

const goShelf = () => router.push('/shelf')

// ---------- 数据加载 ----------

/**
 * 列表请求序号：快速切换分类（A→B）或在「推荐」与分类间来回点时，会连续发出多个请求，
 * 慢的旧响应后到会把 bookList / browseMode / currentCategory 写成旧选择 ——
 * 列表内容与高亮分类不一致，loadHome 迟到还会把刚选的分类列表整块清掉。
 * 只有最新一次请求可以写入状态。
 */
let listReqSeq = 0

const loadHome = async () => {
  const seq = ++listReqSeq
  loading.value = true
  try {
    const res = await storeApi.getHome()
    if (seq !== listReqSeq) return // 已有更新的请求发出：丢弃本次过期响应
    if (res.data.success) {
      hotBooks.value = res.data.hotBooks || []
      newBooks.value = res.data.newBooks || []
      categories.value = res.data.categories || []
      // 旧版服务端没有 tags 字段：兜底成空数组，标签栏整块不渲染（而不是显示一排空 Tag）
      tags.value = res.data.tags || []
      browseMode.value = false
      currentCategory.value = ''
      currentTag.value = ''
      loadCovers([...hotBooks.value, ...newBooks.value])
    }
  } catch (e) {
    if (seq !== listReqSeq) return
    message.error('加载书城失败')
  } finally {
    // 只有最新请求负责收尾 loading，避免过期请求把新请求的 loading 提前关掉
    if (seq === listReqSeq) loading.value = false
  }
}

const loadBooks = async (category?: string, keyword?: string, page = 1) => {
  const seq = ++listReqSeq
  loading.value = true
  try {
    const res = await storeApi.getBooks({
      category: category || undefined,
      // 标签取的是「当前选中的 tag」而不是入参：切换标签的入口有多个（标签栏、清空），
      // 走统一状态比给每个调用点再加一个参数更不容易漏
      tag: currentTag.value || undefined,
      keyword: keyword || undefined,
      page,
      size: PAGE_SIZE,
    })
    if (seq !== listReqSeq) return
    if (res.data.success) {
      bookList.value = res.data.books || []
      totalBooks.value = res.data.total || bookList.value.length
      currentPage.value = page
      browseMode.value = true
      loadCovers(bookList.value)
    }
  } catch (e) {
    if (seq !== listReqSeq) return
    message.error('加载书籍列表失败')
  } finally {
    if (seq === listReqSeq) loading.value = false
  }
}

/** 浏览模式分页：每页封面只加载当页的 24 本（顺带解决封面 N+1 的规模问题） */
const PAGE_SIZE = 24
const currentPage = ref(1)
const totalBooks = ref(0)

const onPageChange = (page: number) => {
  loadBooks(currentCategory.value || undefined, searchKeyword.value.trim() || undefined, page)
}

/** 封面需要 JWT，<img> 不带 axios 头，故用 blob 加载后转 objectURL */
const loadCovers = (books: StoreBook[]) => {
  for (const book of books) {
    if (coverUrls.value.has(book.id)) continue
    apiClient
      .get(storeApi.getCoverUrl(book.id), { responseType: 'blob' })
      .then((res) => {
        if (bookCoversDisposed) return
        const url = URL.createObjectURL(res.data)
        // 组件卸载后到达的封面 URL 立即释放，避免泄漏
        if (bookCoversDisposed) {
          URL.revokeObjectURL(url)
          return
        }
        // 批量更新，避免每次 set 后整体复制 Map（O(n²)）
        const next = new Map(coverUrls.value)
        next.set(book.id, url)
        coverUrls.value = next
      })
      .catch(() => {
        // 封面加载失败静默忽略，前端显示占位背景
      })
  }
  // 释放已不在列表中的书封面 objectURL，避免内存泄漏
  // revoke 后必须同步从 Map 删除——否则下次回到首页时 has(id) 命中已失效的
  // URL 直接复用，封面全部裂图
  const released = new Map(coverUrls.value)
  for (const id of [...released.keys()]) {
    if (!books.some((b) => b.id === id)) {
      const url = released.get(id)
      if (url) URL.revokeObjectURL(url)
      released.delete(id)
    }
  }
  coverUrls.value = released
}

// 组件卸载时释放所有封面 objectURL，避免内存泄漏
onUnmounted(() => {
  bookCoversDisposed = true
  coverUrls.value.forEach((url) => {
    try {
      URL.revokeObjectURL(url)
    } catch (e) {
      // 忽略已释放的 URL
    }
  })
  coverUrls.value = new Map()
})

// ---------- 交互 ----------

const onCategoryClick = (name: string) => {
  currentCategory.value = name
  searchKeyword.value = ''
  if (!name) {
    loadBooks()
  } else {
    loadBooks(name)
  }
}

/**
 * 点标签栏：切到（或取消）按标签筛选。
 *
 * 保留当前的分类与关键词：标签是**叠加**的筛选维度，不是互斥的浏览模式 ——
 * 用户在「历史」分类里点「战争」，期望看到的是「历史 ∩ 战争」，而不是全站战争。
 * 重置到第 1 页：换筛选条件后还停在第 3 页多半是空列表。
 */
const onTagClick = (name: string) => {
  currentTag.value = name
  loadBooks(currentCategory.value || undefined, searchKeyword.value.trim() || undefined, 1)
}

const onSearch = () => {
  const kw = searchKeyword.value.trim()
  if (!kw) {
    loadHome()
    return
  }
  loadBooks(currentCategory.value || undefined, kw)
}

const backToHome = () => {
  searchKeyword.value = ''
  loadHome()
}

const openDetail = (book: StoreBook) => {
  detailBook.value = book
  detailVisible.value = true
}

/**
 * 左键点书籍 → 直接打开阅读。
 *
 * 阅读页自行按 store_books.id 判断这本书有没有音频（拉详情拿 taskId），
 * 有则边读边播、无则纯文本，所以这里只传 id 与书名，不需要先探活 taskId。
 * 书名只作加载完成前的标题占位，最终以服务端返回的 title 为准。
 */
const openReader = (book: StoreBook) => {
  router.push({ path: `/reader/${book.id}`, query: { name: book.title } })
}

/** 详情弹窗里的「打开阅读」：先关弹窗再跳转，返回时不会顶着未关闭的弹窗 */
const readFromDetail = () => {
  if (!detailBook.value) return
  const book = detailBook.value
  detailVisible.value = false
  openReader(book)
}

/**
 * 右键菜单：原「详情弹窗」里的三个动作 + 打开阅读 + 查看详情。
 *
 * 每个动作只在「这本书自己的」菜单项上生效，所以用闭包捕获 book，
 * 而不是共用 detailBook —— 后者在菜单打开到点击之间存在被其他交互改写的窗口。
 */
const contextMenuItems = (book: StoreBook) => [
  {
    key: 'read',
    icon: () => h(ReadOutlined),
    label: hasAudio(book) ? '打开阅读（边读边播）' : '打开阅读',
    onClick: () => openReader(book),
  },
  {
    // 文案随书架状态切换；加入/移出是同一个动作，故共用一项而非拆两项
    key: 'shelf',
    icon: () => h(inShelf(book) ? BookFilled : PlusOutlined),
    label: inShelf(book) ? '移出书架' : '加入书架',
    onClick: () => toggleShelf(book),
  },
  {
    key: 'detail',
    icon: () => h(InfoCircleOutlined),
    label: '查看详情',
    onClick: () => openDetail(book),
  },
  { type: 'divider' as const },
  {
    key: 'synthesize',
    icon: () => h(AudioOutlined),
    label: '用这本书合成有声书',
    onClick: () => goSynthesize(book),
  },
  {
    key: 'audio',
    icon: () => h(DownloadOutlined),
    label: '下载合成音频',
    // 无音频的书直接不给这个入口，比点下去弹「暂无音频」更干净
    disabled: !hasAudio(book),
    onClick: () => {
      detailBook.value = book
      downloadAudio()
    },
  },
  {
    key: 'ebook',
    icon: () => h(DownloadOutlined),
    label: '下载电子书',
    onClick: () => {
      detailBook.value = book
      downloadBook()
    },
  },
]

/** 下载书籍文件（浏览器保存对话框） */
const downloadBook = async () => {
  if (!detailBook.value) return
  downloading.value = true
  try {
    const res = await storeApi.downloadBook(detailBook.value.id)
    // 先校验响应是电子书而非错误 JSON，否则后端以 200 + JSON 错误体返回时
    // 用户会下载到「看起来是书、实为错误 JSON」的文件（与 Player.vue / TaskList.vue 一致）
    const blob = await assertDownloadable(res)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    // fileName 在共享类型中为 string | null（后端部分书籍未落库文件名），
    // 下载属性需要 string，缺失时回退到书名或默认名（WEB 健壮性修复）。
    a.download = detailBook.value.fileName ?? detailBook.value.title ?? 'book'
    a.click()
    // 延迟释放：部分浏览器下同步 revoke 会中断大文件（上限 50MB）下载
    window.setTimeout(() => URL.revokeObjectURL(url), 10000)
    message.success(`《${detailBook.value.title}》开始下载`)
  } catch (e) {
    // 错误多为非 2xx 的 Blob 响应，解析出真实原因，避免显示英文泛化文案
    message.error(await extractBlobErrorMessage(e, '下载失败'))
  } finally {
    downloading.value = false
  }
}

/**
 * 下载书城书籍的合成音频（zip）。
 * 仅当 store_books.task_id 非空时后端才会返回 200 + zip 流；
 * 任务未完成 / 书不存在则交由 extractBlobErrorMessage 给出明确提示。
 */
const downloadAudio = async () => {
  if (!detailBook.value) return
  if (!detailBook.value.taskId) {
    message.warning('这本书暂无合成音频')
    return
  }
  downloadingAudio.value = true
  try {
    const res = await storeApi.downloadAudio(detailBook.value.id)
    const blob = await assertDownloadable(res)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    // 合成音频是 zip 包；用「书名 + audio」命名，避免和电子书文件名冲突
    a.download = `audio_${detailBook.value.title ?? detailBook.value.id}.zip`
    a.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 10000)
    message.success(`《${detailBook.value.title}》音频开始下载`)
  } catch (e) {
    message.error(await extractBlobErrorMessage(e, '下载音频失败'))
  } finally {
    downloadingAudio.value = false
  }
}

/** 任务是否已合成：详情 / 列表 Tag 共用 */
const hasAudio = (book?: StoreBook | null) => !!book?.taskId

const coverOf = (book: StoreBook) => coverUrls.value.get(book.id) || ''

/** 跳转到 TTS 合成页并预填该书 */
const goSynthesize = (book: StoreBook) => {
  detailVisible.value = false
  router.push({
    path: '/tts',
    query: { storeBookId: String(book.id), name: book.title },
  })
}

const listTitle = computed(() => {
  const kw = searchKeyword.value.trim()
  const parts: string[] = []
  if (kw) parts.push(`搜索：${kw}`)
  if (currentCategory.value) parts.push(`分类：${currentCategory.value}`)
  if (currentTag.value) parts.push(`标签：${currentTag.value}`)
  return parts.length > 0 ? parts.join(' · ') : '全部书籍'
})

onMounted(() => {
  loadHome()
  // 书架状态与书城首页并行拉取，互不阻塞
  loadShelfIds()
})
</script>

<template>
  <Card>
    <div class="store-header">
      <Title :level="4"><BookOutlined /> 书城</Title>
      <Space>
        <Input
          v-model:value="searchKeyword"
          placeholder="搜索书名或作者"
          style="width: 280px"
          allow-clear
          @press-enter="onSearch"
        >
          <template #prefix><SearchOutlined /></template>
        </Input>
        <Button :icon="h(BookFilled)" @click="goShelf">我的书架</Button>
      </Space>
    </div>

    <!-- 分类栏 -->
    <div class="category-bar">
      <Tag
        class="category-tag"
        :color="!browseMode ? 'blue' : 'default'"
        @click="backToHome"
      >
        推荐
      </Tag>
      <Tag
        class="category-tag"
        :color="browseMode && !currentCategory && !searchKeyword ? 'blue' : 'default'"
        @click="onCategoryClick('')"
      >
        全部
      </Tag>
      <Tag
        v-for="cat in categories"
        :key="cat.name"
        class="category-tag"
        :color="browseMode && currentCategory === cat.name ? 'blue' : 'default'"
        @click="onCategoryClick(cat.name)"
      >
        {{ cat.name }} ({{ cat.count }})
      </Tag>
    </div>

    <!-- 标签栏：分类是单选归属，标签是多选描述，两者可叠加筛选 -->
    <div v-if="tags.length > 0" class="tag-bar">
      <span class="tag-bar-label">标签</span>
      <Tag
        v-for="t in tags"
        :key="t.name"
        class="tag-chip"
        :color="currentTag === t.name ? 'blue' : 'default'"
        @click="onTagClick(currentTag === t.name ? '' : t.name)"
      >
        {{ t.name }} ({{ t.count }})
      </Tag>
    </div>

    <Spin :spinning="loading">
      <!-- 首页模式：热门榜 + 新书榜 -->
      <template v-if="!browseMode">
        <div v-if="hotBooks.length > 0" class="section">
          <Title :level="5">🔥 热门榜单</Title>
          <div class="book-grid">
            <Dropdown
              v-for="book in hotBooks"
              :key="book.id"
              :trigger="['contextmenu']"
            >
              <div class="book-card" title="左键阅读 · 右键更多" @click="openReader(book)">
                <div class="cover" :style="{ background: safeCoverColor(book.coverColor) }">
                  <img v-if="coverOf(book)" :src="coverOf(book)" alt="封面" />
                  <span v-else class="cover-placeholder">{{ book.title }}</span>
                  <!-- 已加入书架：左上角角标，与右上角的「有声书」徽章错开不打架 -->
                  <Tag v-if="inShelf(book)" class="shelf-badge" color="green">
                    <BookFilled /> 在书架
                  </Tag>
                  <Tag v-if="hasAudio(book)" class="audio-badge" color="blue">
                    <CheckCircleOutlined /> 有声书
                  </Tag>
                </div>
                <div class="book-title">{{ book.title }}</div>
                <div class="book-author">{{ book.author || '佚名' }}</div>
                <!-- 标签：卡片位置有限，最多显示 2 个，其余折成 +N（详情弹窗里看全量） -->
                <div v-if="book.tags && book.tags.length" class="book-tags">
                  <Tag v-for="tag in book.tags.slice(0, 2)" :key="tag" class="book-tag">{{ tag }}</Tag>
                  <span v-if="book.tags.length > 2" class="book-tag-extra">+{{ book.tags.length - 2 }}</span>
                </div>
              </div>
              <!-- 右键菜单必须挂 #overlay：antd-vue 的 Dropdown 不消费 :menu -->
              <template #overlay>
                <Menu :items="contextMenuItems(book)" />
              </template>
            </Dropdown>
          </div>
        </div>

        <div v-if="newBooks.length > 0" class="section">
          <Title :level="5">✨ 新书上架</Title>
          <div class="book-grid">
            <Dropdown
              v-for="book in newBooks"
              :key="book.id"
              :trigger="['contextmenu']"
            >
              <div class="book-card" title="左键阅读 · 右键更多" @click="openReader(book)">
                <div class="cover" :style="{ background: safeCoverColor(book.coverColor) }">
                  <img v-if="coverOf(book)" :src="coverOf(book)" alt="封面" />
                  <span v-else class="cover-placeholder">{{ book.title }}</span>
                  <Tag v-if="inShelf(book)" class="shelf-badge" color="green">
                    <BookFilled /> 在书架
                  </Tag>
                  <Tag v-if="hasAudio(book)" class="audio-badge" color="blue">
                    <CheckCircleOutlined /> 有声书
                  </Tag>
                </div>
                <div class="book-title">{{ book.title }}</div>
                <div class="book-author">{{ book.author || '佚名' }}</div>
                <!-- 标签：卡片位置有限，最多显示 2 个，其余折成 +N（详情弹窗里看全量） -->
                <div v-if="book.tags && book.tags.length" class="book-tags">
                  <Tag v-for="tag in book.tags.slice(0, 2)" :key="tag" class="book-tag">{{ tag }}</Tag>
                  <span v-if="book.tags.length > 2" class="book-tag-extra">+{{ book.tags.length - 2 }}</span>
                </div>
              </div>
              <!-- 右键菜单必须挂 #overlay：antd-vue 的 Dropdown 不消费 :menu -->
              <template #overlay>
                <Menu :items="contextMenuItems(book)" />
              </template>
            </Dropdown>
          </div>
        </div>

        <Empty v-if="hotBooks.length === 0 && newBooks.length === 0 && !loading" description="书城暂无书籍" />
      </template>

      <!-- 浏览模式：完整列表 -->
      <template v-else>
        <div class="list-header">
          <Title :level="5">{{ listTitle }}</Title>
          <Button type="link" @click="backToHome">返回书城首页</Button>
        </div>
        <div v-if="bookList.length > 0" class="book-grid">
          <Dropdown
            v-for="book in bookList"
            :key="book.id"
            :trigger="['contextmenu']"
          >
            <div class="book-card" title="左键阅读 · 右键更多" @click="openReader(book)">
              <div class="cover" :style="{ background: safeCoverColor(book.coverColor) }">
                <img v-if="coverOf(book)" :src="coverOf(book)" alt="封面" loading="lazy" />
                <span v-else class="cover-placeholder">{{ book.title }}</span>
                <Tag v-if="inShelf(book)" class="shelf-badge" color="green">
                  <BookFilled /> 在书架
                </Tag>
                <Tag v-if="hasAudio(book)" class="audio-badge" color="blue">
                  <CheckCircleOutlined /> 有声书
                </Tag>
              </div>
              <div class="book-title">{{ book.title }}</div>
              <div class="book-author">{{ book.author || '佚名' }}</div>
              <!-- 标签：卡片位置有限，最多显示 2 个，其余折成 +N（详情弹窗里看全量） -->
              <div v-if="book.tags && book.tags.length" class="book-tags">
                <Tag v-for="tag in book.tags.slice(0, 2)" :key="tag" class="book-tag">{{ tag }}</Tag>
                <span v-if="book.tags.length > 2" class="book-tag-extra">+{{ book.tags.length - 2 }}</span>
              </div>
            </div>
            <!-- 右键菜单必须挂 #overlay：antd-vue 的 Dropdown 不消费 :menu -->
            <template #overlay>
              <Menu :items="contextMenuItems(book)" />
            </template>
          </Dropdown>
        </div>
        <Empty v-else-if="!loading" description="没有找到相关书籍" />
        <div v-if="totalBooks > PAGE_SIZE" class="pagination-row">
          <Pagination
            :current="currentPage"
            :total="totalBooks"
            :page-size="PAGE_SIZE"
            :show-size-changer="false"
            @change="onPageChange"
          />
        </div>
      </template>
    </Spin>

    <!-- 书籍详情弹窗 -->
    <Modal
      v-model:open="detailVisible"
      :title="detailBook ? `《${detailBook.title}》` : '书籍详情'"
      :footer="null"
      width="480px"
    >
      <template v-if="detailBook">
        <div class="detail-body">
          <div class="detail-cover" :style="{ background: safeCoverColor(detailBook.coverColor) }">
            <img v-if="coverOf(detailBook)" :src="coverOf(detailBook)" alt="封面" />
            <span v-else class="cover-placeholder">{{ detailBook.title }}</span>
          </div>
          <Descriptions :column="1" size="small" style="flex: 1">
            <Descriptions.Item label="作者">{{ detailBook.author || '佚名' }}</Descriptions.Item>
            <Descriptions.Item label="分类">{{ detailBook.category || '未分类' }}</Descriptions.Item>
            <Descriptions.Item label="标签">
              <template v-if="detailBook.tags && detailBook.tags.length">
                <Tag v-for="tag in detailBook.tags" :key="tag" class="book-tag">{{ tag }}</Tag>
              </template>
              <span v-else class="detail-empty">无</span>
            </Descriptions.Item>
            <Descriptions.Item label="格式">{{ detailBook.fileExtension?.toUpperCase() || '未知' }}</Descriptions.Item>
            <Descriptions.Item label="大小">{{ formatFileSize(detailBook.fileSize) }}</Descriptions.Item>
            <Descriptions.Item label="下载次数">{{ detailBook.downloadCount || 0 }}</Descriptions.Item>
          </Descriptions>
        </div>
        <Paragraph class="detail-desc" :ellipsis="{ rows: 3, expandable: true }">
          {{ detailBook.description || '暂无简介' }}
        </Paragraph>
        <Space direction="vertical" style="width: 100%">
          <Button type="primary" block :icon="h(ReadOutlined)" @click="readFromDetail">
            {{ hasAudio(detailBook) ? '打开阅读（边读边播）' : '打开阅读' }}
          </Button>
          <Button
            block
            :icon="h(inShelf(detailBook) ? BookFilled : PlusOutlined)"
            :loading="shelfPending.has(detailBook.id)"
            @click="toggleShelf(detailBook)"
          >
            {{ inShelf(detailBook) ? '移出书架' : '加入书架' }}
          </Button>
          <Button block :icon="h(AudioOutlined)" @click="goSynthesize(detailBook)">
            用这本书合成有声书
          </Button>
          <Button
            v-if="hasAudio(detailBook)"
            block
            color="blue"
            variant="solid"
            :loading="downloadingAudio"
            @click="downloadAudio"
          >
            <DownloadOutlined /> 下载合成音频
          </Button>
          <Button block :loading="downloading" @click="downloadBook">
            <DownloadOutlined /> 下载电子书
          </Button>
        </Space>
      </template>
    </Modal>
  </Card>
</template>

<style scoped>
.store-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.category-bar {
  margin-bottom: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.category-tag {
  cursor: pointer;
  padding: 2px 10px;
  font-size: 13px;
}

/* 标签栏：单独一行、字号更小，与上面的分类栏区分开（两排同款 Tag 会看成一件事） */
.tag-bar {
  margin: -8px 0 16px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.tag-bar-label {
  font-size: 12px;
  color: #999;
  margin-right: 2px;
}

.tag-chip {
  cursor: pointer;
  padding: 2px 8px;
  font-size: 12px;
}

/* 书籍卡片上的标签：比筛选栏更淡，是描述信息不是操作入口 */
.book-tags {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
}

.book-tag {
  margin: 0;
  font-size: 11px;
  line-height: 16px;
  padding: 0 5px;
  border-radius: 8px;
  border: none;
  color: #666;
  background: rgba(0, 0, 0, 0.04);
}

.book-tag-extra {
  font-size: 11px;
  color: #999;
}

.detail-empty {
  color: #999;
}

.section {
  margin-bottom: 24px;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.pagination-row {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.book-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 16px;
}

.book-card {
  cursor: pointer;
  transition: transform 0.15s ease;
}
.book-card:hover {
  transform: translateY(-3px);
}

.cover {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  color: rgba(255, 255, 255, 0.95);
  font-size: 15px;
  font-weight: bold;
  text-align: center;
  padding: 8px;
}

/* 「有声书」徽章：浮在封面右上角，叠加在 cover 上的 position:absolute 子元素 */
.cover {
  position: relative;
}

.audio-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  margin: 0;
  font-size: 11px;
  line-height: 18px;
  padding: 0 6px;
  border-radius: 10px;
}

/* 「在书架」角标：放左上角，与右上角的「有声书」徽章错开，避免两枚标签叠在一起 */
.shelf-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  margin: 0;
  font-size: 11px;
  line-height: 18px;
  padding: 0 6px;
  border-radius: 10px;
}

.book-title {
  margin-top: 8px;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.book-author {
  font-size: 12px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.detail-body {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}

.detail-cover {
  width: 120px;
  min-width: 120px;
  aspect-ratio: 3 / 4;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail-desc {
  margin-top: 8px;
  color: #555;
}

/* 夜间模式：作者名与详情正文的深灰字在 #141414 上对比度不足，调亮 */
.dark .book-author {
  color: rgba(255, 255, 255, 0.65);
}

.dark .detail-desc {
  color: rgba(255, 255, 255, 0.85);
}

/* 夜间模式：卡片标签的深灰字与浅色底在 #141414 上几乎看不见，反色处理 */
.dark .book-tag {
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.1);
}

.dark .tag-bar-label,
.dark .book-tag-extra,
.dark .detail-empty {
  color: rgba(255, 255, 255, 0.45);
}
</style>
