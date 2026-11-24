<script setup lang="ts">
/**
 * 我的书架。
 *
 * 数据源是 /api/shelf（服务端为唯一真源，跨端/跨设备共享），条目由服务端
 * 把 shelf_books 与 store_books 拼接后下发，因此这里既有书架关系也有书籍元信息。
 *
 * 与书城页的关系：书城负责「发现并加入书架」，本页负责「管理书架并进入阅读」。
 * 两个页面共用 storeApi.getCoverUrl + blob 取封面的方式（<img> 不带 JWT 头）。
 *
 * 三种条目状态都要能正常渲染，不能过滤：
 *  - 正常：可阅读 / 可边读边播
 *  - missing=true：书已从书城删除 → 显示失效卡片（书名回退到入架快照，仍可辨认），
 *    仅允许移除
 *  - offshelf=true：已下架（磁盘文件缺失）→ 可看不可读，给出提示
 */
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
  Button,
  Tag,
  Modal,
  message,
  Spin,
  Empty,
  Typography,
  Space,
  Input,
  Dropdown,
  Menu,
} from 'ant-design-vue'
import {
  ReadOutlined,
  DeleteOutlined,
  AudioOutlined,
  BookOutlined,
  CheckCircleOutlined,
  SearchOutlined,
  ReloadOutlined,
  InfoCircleOutlined,
  DownloadOutlined,
} from '@ant-design/icons-vue'
import { shelfApi, storeApi, assertDownloadable, extractBlobErrorMessage, type ShelfItem } from '@/services/api'
import { formatFileSize, formatDateTimeShort } from '@audiobook/shared'
import apiClient from '@/services/apiClient'

const { Title, Text, Paragraph } = Typography
const router = useRouter()

const loading = ref(false)
const items = ref<ShelfItem[]>([])
const keyword = ref('')

/** 封面 blob URL 缓存：storeBookId -> objectURL */
const coverUrls = ref<Map<number, string>>(new Map())
/** 组件卸载标记：封面请求是异步的，卸载后到达的响应不应再写入/泄漏 objectURL */
let disposed = false

const detailVisible = ref(false)
const detailItem = ref<ShelfItem | null>(null)

// ---------- 数据加载 ----------

/** v5/W11：请求序号。连点刷新时后到的旧响应会把新结果覆盖掉（列表闪回旧数据）。 */
let shelfReqSeq = 0

const loadShelf = async () => {
  const seq = ++shelfReqSeq
  loading.value = true
  try {
    const res = await shelfApi.list()
    if (seq !== shelfReqSeq) return   // 已有更新请求发出：丢弃本响应（含 finally 的 loading 复位）
    if (res.data.success) {
      items.value = res.data.items || []
      loadCovers(items.value)
    } else {
      message.error('加载书架失败')
    }
  } catch (e) {
    if (seq === shelfReqSeq) message.error('加载书架失败')
  } finally {
    if (seq === shelfReqSeq) loading.value = false
  }
}

/**
 * 封面需要 JWT，<img> 不带 axios 头，故用 blob 加载后转 objectURL。
 * 与书城页同一套做法；已缓存的 id 不重复请求。
 */
/** 封面并发上限：书架可达上百本，无节制并发会瞬时占满浏览器同域连接数，拖慢同页其它请求 */
const COVER_CONCURRENCY = 6

const loadCovers = (list: ShelfItem[]) => {
  // 失效条目没有封面可加载，跳过以免发无意义的 404 请求
  const queue = list.filter((item) => !item.missing && !coverUrls.value.has(item.storeBookId))
  let cursor = 0
  const worker = async () => {
    while (cursor < queue.length) {
      const item = queue[cursor++]
      try {
        const res = await apiClient.get(storeApi.getCoverUrl(item.storeBookId), {
          responseType: 'blob',
        })
        if (disposed) return
        const url = URL.createObjectURL(res.data)
        // 组件卸载后到达的封面 URL 立即释放，避免泄漏
        if (disposed) {
          URL.revokeObjectURL(url)
          return
        }
        // 批量更新，避免每次 set 后整体复制 Map
        const next = new Map(coverUrls.value)
        next.set(item.storeBookId, url)
        coverUrls.value = next
      } catch {
        // 封面加载失败静默忽略，前端显示占位背景
      }
    }
  }
  for (let i = 0; i < Math.min(COVER_CONCURRENCY, queue.length); i++) void worker()
  // 释放已不在书架中的封面 objectURL。
  // revoke 后必须同步从 Map 删除——否则 has(id) 命中已失效 URL 直接复用，封面裂图
  const released = new Map(coverUrls.value)
  const alive = new Set(list.filter((i) => !i.missing).map((i) => i.storeBookId))
  for (const id of [...released.keys()]) {
    if (!alive.has(id)) {
      const url = released.get(id)
      if (url) URL.revokeObjectURL(url)
      released.delete(id)
    }
  }
  coverUrls.value = released
}

onUnmounted(() => {
  disposed = true
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

const coverOf = (item: ShelfItem) => coverUrls.value.get(item.storeBookId) || ''

const openReader = (item: ShelfItem) => {
  if (item.missing || item.offshelf) return
  router.push({ path: `/reader/${item.storeBookId}`, query: { name: item.title || '' } })
}

const goSynthesize = (item: ShelfItem) => {
  detailVisible.value = false
  router.push({
    path: '/tts',
    query: { storeBookId: String(item.storeBookId), name: item.title || '' },
  })
}

const goStore = () => router.push('/store')

/**
 * 移出书架。
 * 后端按 storeBookId 幂等删除，本地乐观移除让 UI 立即响应；
 * 失败则回滚（把条目放回原位）并提示，避免「看起来删掉了其实还在」。
 */
const removeFromShelf = async (item: ShelfItem, opts?: { confirm?: boolean }) => {
  const doRemove = async () => {
    const index = items.value.findIndex((i) => i.storeBookId === item.storeBookId)
    if (index < 0) return
    const removed = items.value[index]
    items.value = items.value.filter((i) => i.storeBookId !== item.storeBookId)

    try {
      await shelfApi.remove(item.storeBookId)
      message.success(`《${removed.title || '该书'}》已移出书架`)
    } catch (e) {
      // 回滚到删除前的位置，避免用户以为删掉了、刷新后又出现
      const restored = [...items.value]
      restored.splice(index, 0, removed)
      items.value = restored
      message.error('移出书架失败，请稍后重试')
    }
  }

  if (opts?.confirm) {
    Modal.confirm({
      title: '移出书架',
      content: `确定把《${item.title || '该书'}》移出书架吗？书城中的书不会被删除。`,
      okText: '移出',
      okType: 'danger',
      cancelText: '取消',
      onOk: doRemove,
    })
    return
  }
  await doRemove()
}

/** 书架卡片右键菜单（与书城卡片一致的操作习惯） */
const contextMenuItems = (item: ShelfItem) => {
  const unavailable = item.missing || item.offshelf
  return [
    {
      key: 'read',
      icon: () => h(ReadOutlined),
      label: item.hasAudio ? '打开阅读（边读边播）' : '打开阅读',
      disabled: unavailable,
      onClick: () => openReader(item),
    },
    {
      key: 'detail',
      icon: () => h(InfoCircleOutlined),
      label: '查看详情',
      disabled: item.missing,
      onClick: () => openDetail(item),
    },
    { type: 'divider' as const },
    {
      key: 'synthesize',
      icon: () => h(AudioOutlined),
      label: '用这本书合成有声书',
      disabled: unavailable,
      onClick: () => goSynthesize(item),
    },
    {
      key: 'remove',
      icon: () => h(DeleteOutlined),
      label: '移出书架',
      danger: true,
      onClick: () => removeFromShelf(item, { confirm: true }),
    },
  ]
}

const openDetail = (item: ShelfItem) => {
  detailItem.value = item
  detailVisible.value = true
}

const readFromDetail = () => {
  if (!detailItem.value) return
  const item = detailItem.value
  detailVisible.value = false
  openReader(item)
}

const downloading = ref(false)
const downloadBook = async () => {
  if (!detailItem.value) return
  downloading.value = true
  try {
    const res = await storeApi.downloadBook(detailItem.value.storeBookId)
    // 先校验响应是电子书而非错误 JSON（后端可能以 200 + JSON 错误体返回）
    const blob = await assertDownloadable(res)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = detailItem.value.title || 'book'
    a.click()
    // 延迟释放：部分浏览器下同步 revoke 会中断大文件下载
    window.setTimeout(() => URL.revokeObjectURL(url), 10000)
    message.success(`《${detailItem.value.title}》开始下载`)
  } catch (e) {
    message.error(await extractBlobErrorMessage(e, '下载失败'))
  } finally {
    downloading.value = false
  }
}

/** 前端关键词过滤（书架规模有限，服务端暂无分页/搜索需求） */
const filteredItems = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return items.value
  return items.value.filter((i) => {
    const title = (i.title || '').toLowerCase()
    const author = (i.author || '').toLowerCase()
    return title.includes(kw) || author.includes(kw)
  })
})

const audioCount = computed(() => items.value.filter((i) => i.hasAudio).length)

onMounted(loadShelf)
</script>

<template>
  <Card>
    <div class="shelf-header">
      <Title :level="4"><BookOutlined /> 我的书架</Title>
      <Space>
        <Input
          v-model:value="keyword"
          placeholder="搜索书架中的书名或作者"
          style="width: 240px"
          allow-clear
        >
          <template #prefix><SearchOutlined /></template>
        </Input>
        <Button :icon="h(ReloadOutlined)" @click="loadShelf">刷新</Button>
      </Space>
    </div>

    <Text type="secondary" class="shelf-summary">
      共 {{ items.length }} 本，其中 {{ audioCount }} 本已合成音频（可边读边播）
    </Text>

    <Spin :spinning="loading">
      <div v-if="filteredItems.length > 0" class="book-grid">
        <Dropdown
          v-for="item in filteredItems"
          :key="item.shelfBookId"
          :trigger="['contextmenu']"
        >
          <div
            class="book-card"
            :class="{ dimmed: item.missing || item.offshelf }"
            title="左键阅读 · 右键更多"
            @click="openReader(item)"
          >
            <div class="cover" :style="{ background: safeCoverColor(item.coverColor) }">
              <img v-if="coverOf(item)" :src="coverOf(item)" alt="封面" loading="lazy" />
              <span v-else class="cover-placeholder">{{ item.title || '已失效' }}</span>
              <Tag v-if="item.hasAudio" class="corner-badge" color="blue">
                <CheckCircleOutlined /> 有声书
              </Tag>
              <Tag v-else-if="item.offshelf" class="corner-badge" color="orange">已下架</Tag>
              <Tag v-else-if="item.missing" class="corner-badge" color="red">已失效</Tag>
            </div>
            <div class="book-title">{{ item.title || '（书籍已删除）' }}</div>
            <div class="book-author">{{ item.author || '佚名' }}</div>
          </div>
          <!-- 右键菜单必须挂 #overlay：antd-vue 的 Dropdown 不消费 :menu -->
          <template #overlay>
            <Menu :items="contextMenuItems(item)" />
          </template>
        </Dropdown>
      </div>
      <Empty
        v-else-if="!loading && items.length === 0"
        description="书架还是空的，去书城挑几本吧"
      >
        <Button type="primary" @click="goStore">去书城逛逛</Button>
      </Empty>
      <Empty v-else-if="!loading" description="没有匹配的书籍" />
    </Spin>

    <!-- 书籍详情弹窗 -->
    <Modal
      v-model:open="detailVisible"
      :title="detailItem ? `《${detailItem.title || '书籍已删除'}》` : '书籍详情'"
      :footer="null"
      width="480px"
    >
      <template v-if="detailItem">
        <div class="detail-body">
          <div class="detail-cover" :style="{ background: safeCoverColor(detailItem.coverColor) }">
            <img v-if="coverOf(detailItem)" :src="coverOf(detailItem)" alt="封面" />
            <span v-else class="cover-placeholder">{{ detailItem.title || '已失效' }}</span>
          </div>
          <div class="detail-meta">
            <p><Text type="secondary">作者：</Text>{{ detailItem.author || '佚名' }}</p>
            <p><Text type="secondary">分类：</Text>{{ detailItem.category || '未分类' }}</p>
            <p>
              <Text type="secondary">格式：</Text>
              {{ detailItem.fileExtension?.toUpperCase() || '未知' }}
              <Text v-if="detailItem.fileSize" type="secondary">
                · {{ formatFileSize(detailItem.fileSize) }}
              </Text>
            </p>
            <p>
              <Text type="secondary">加入时间：</Text>
              {{ detailItem.addedAt ? formatDateTimeShort(detailItem.addedAt) : '—' }}
            </p>
          </div>
        </div>

        <Paragraph v-if="detailItem.missing" type="danger">
          这本书已从书城删除，仅保留书架记录。可移出书架以清理这条记录。
        </Paragraph>
        <Paragraph v-else-if="detailItem.offshelf" type="warning">
          这本书已下架，暂时无法阅读或下载。
        </Paragraph>

        <Space direction="vertical" style="width: 100%">
          <Button
            type="primary"
            block
            :icon="h(ReadOutlined)"
            :disabled="detailItem.missing || detailItem.offshelf"
            @click="readFromDetail"
          >
            {{ detailItem.hasAudio ? '打开阅读（边读边播）' : '打开阅读' }}
          </Button>
          <Button
            block
            :icon="h(AudioOutlined)"
            :disabled="detailItem.missing || detailItem.offshelf"
            @click="goSynthesize(detailItem)"
          >
            用这本书合成有声书
          </Button>
          <Button
            block
            :loading="downloading"
            :disabled="detailItem.missing || detailItem.offshelf"
            @click="downloadBook"
          >
            <DownloadOutlined /> 下载电子书
          </Button>
          <Button danger block @click="removeFromShelf(detailItem, { confirm: true })">
            <DeleteOutlined /> 移出书架
          </Button>
        </Space>
      </template>
    </Modal>
  </Card>
</template>

<style scoped>
.shelf-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
  gap: 8px;
}

.shelf-summary {
  display: block;
  margin-bottom: 16px;
  font-size: 13px;
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

/* 失效/已下架：整体降透明，并去掉 hover 上浮的「可点」暗示 */
.book-card.dimmed {
  opacity: 0.55;
}
.book-card.dimmed:hover {
  transform: none;
  cursor: not-allowed;
}

.cover {
  position: relative;
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

.corner-badge {
  position: absolute;
  top: 6px;
  right: 6px;
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

.detail-meta {
  flex: 1;
  font-size: 13px;
}

.detail-meta p {
  margin-bottom: 6px;
}

/* 夜间模式：作者名深灰字在 #141414 上对比度不足，调亮 */
.dark .book-author {
  color: rgba(255, 255, 255, 0.65);
}
</style>
