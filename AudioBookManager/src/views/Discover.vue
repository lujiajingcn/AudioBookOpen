<script setup lang="ts">
import { ref, onMounted, onUnmounted, h } from 'vue'
import {
  Tabs,
  TabPane,
  List,
  Button,
  Card,
  Tag,
  Empty,
  Switch,
  Select,
  Popconfirm,
  message,
} from 'ant-design-vue'
import {
  FireOutlined,
  MessageOutlined,
  PlayCircleOutlined,
  StopOutlined,
  ReloadOutlined,
  EyeOutlined,
  DeleteOutlined,
  RollbackOutlined,
} from '@ant-design/icons-vue'
import {
  discoveryApi,
  adminContentApi,
  type AdminPost,
  type AdminReference,
} from '@/services/discovery'
import { formatFileSize, formatDateTimeShort } from '@audiobook/shared'

const activeTab = ref<string>('rank')

// ---------------- Tab 1：参考音频分享（排行 / 内容审核） ----------------
const references = ref<AdminReference[]>([])
const rankLoading = ref(false)
const refActionLoading = ref<number | null>(null)
/** 只看被管理员下架的：切走后列表内容语义完全变了，标题也要跟着变，否则会误读 */
const onlyHiddenRefs = ref(false)

const previewingId = ref<number | null>(null)
const previewUrl = ref<string | null>(null)
const audio = new Audio()
// 请求所有权标记：连续快速点击不同项时会并发拉取，仅最新一次请求的结果可生效，
// 防止旧音频抢播 / 已停止后仍尝试播放（与 Web 端 useAudioPreview 的 targetId 守卫一致）
let previewTargetId: number | null = null

const stopPreview = () => {
  audio.pause()
  audio.onended = null
  // 必须先摘掉 src 再 revoke：只 revokeObjectURL 而元素仍指向旧 blob 时，
  // 元素会持有已失效的引用，后续 onended/缓冲回调可能作用在错误的目标上。
  audio.removeAttribute('src')
  audio.load()
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = null
  previewingId.value = null
  previewTargetId = null
}

const togglePreview = async (row: AdminReference) => {
  if (previewingId.value === row.id) {
    stopPreview()
    return
  }
  const targetId = row.id
  previewTargetId = targetId
  try {
    const res = await discoveryApi.previewSharedReference(targetId)
    // 请求期间用户又点了别项/停止：丢弃本次结果，防止旧音频抢播
    if (previewTargetId !== targetId) return
    if (!(res.data instanceof Blob)) {
      message.error('试听失败')
      return
    }
    stopPreview()
    const url = URL.createObjectURL(res.data)
    previewUrl.value = url
    previewingId.value = targetId
    audio.src = url
    audio.onended = () => stopPreview()
    try {
      await audio.play()
    } catch {
      message.warning('浏览器阻止了自动播放，可手动点击停止')
    }
  } catch {
    // 仅当本次请求仍是最新目标时才提示，避免切换后误报
    if (previewTargetId === targetId) message.error('试听失败，请稍后重试')
  }
}

const loadReferences = async () => {
  try {
    rankLoading.value = true
    const res = await adminContentApi.listReferences(onlyHiddenRefs.value, 100)
    if (res.data.success) {
      references.value = res.data.references || []
    } else {
      message.error(res.data.message || '加载参考音频列表失败')
    }
  } catch {
    message.error('加载参考音频列表失败，请稍后重试')
  } finally {
    rankLoading.value = false
  }
}

const onOnlyHiddenRefsChange = () => loadReferences()

/**
 * 下架 / 恢复参考音频。
 *
 * 语义要点（与服务端 setReferenceHidden 一致）：只翻 admin_hidden，
 * **不改用户自己的 shared 开关** —— 用户取消分享再重新分享，违规内容也不会回到前台。
 */
const toggleRefHidden = async (row: AdminReference) => {
  const nextHidden = !row.adminHidden
  try {
    refActionLoading.value = row.id
    await adminContentApi.setReferenceHidden(row.id, nextHidden)
    message.success(nextHidden ? `已下架「${row.name}」` : `已恢复「${row.name}」`)
    await loadReferences()
  } catch {
    message.error(nextHidden ? '下架失败，请稍后重试' : '恢复失败，请稍后重试')
  } finally {
    refActionLoading.value = null
  }
}

// ---------------- Tab 2：讨论（帖子审核） ----------------
const posts = ref<AdminPost[]>([])
const discussLoading = ref(false)
const page = ref(0)
const hasMore = ref(false)
const total = ref(0)
const removedCount = ref(0)
const activeCount = ref(0)
/** 「加载更多」在途标记：独立于 discussLoading（后者只在首屏/重置时置位，page > 0 时恒为 false） */
const loadingMore = ref(false)
const postActionLoading = ref<number | null>(null)
/** 状态过滤：all（默认）/ active / removed。切状态后分页必须归零重载 */
const postStatusFilter = ref<'all' | 'active' | 'removed'>('all')

const loadPosts = async (reset: boolean) => {
  if (reset) {
    discussLoading.value = true
    page.value = 0
  } else {
    // 在途守卫：连点两次「加载更多」会以同一个 page 并发发出两次请求，同一页数据被 concat
    // 两次（列表重复），且 page 连续 +=1 两次导致下一次「加载更多」跳过中间一整页。
    if (loadingMore.value) return
    loadingMore.value = true
  }
  try {
    const res = await adminContentApi.listPosts(postStatusFilter.value, page.value, 20)
    if (res.data.success) {
      const list = res.data.posts || []
      posts.value = reset ? list : posts.value.concat(list)
      hasMore.value = res.data.hasNext
      total.value = res.data.total
      if (res.data.counts) {
        activeCount.value = res.data.counts.active
        removedCount.value = res.data.counts.removed
      }
      // 无条件递增：reset 时上面已把 page 归 0 并请求了第 0 页（后端 page 是 0-based），
      // 若只在「加载更多」时递增，首次点「加载更多」会再次请求 page=0，
      // 把第 1 页的内容重复追加到列表尾部。
      page.value += 1
      // 边界保护：后端偶发 hasNext=true 但实际无数据，强制收尾避免无限滚动多发空请求
      if (list.length === 0) hasMore.value = false
    } else {
      message.error(res.data.message || '加载帖子失败')
    }
  } catch {
    message.error('加载帖子失败，请稍后重试')
  } finally {
    if (reset) discussLoading.value = false
    loadingMore.value = false
  }
}

const reloadPosts = () => loadPosts(true)

const onPostStatusChange = () => loadPosts(true)

/** 逻辑下架：前台立即不可见，数据保留，可一键恢复 */
const removePost = async (row: AdminPost) => {
  try {
    postActionLoading.value = row.id
    await adminContentApi.removePost(row.id)
    message.success('已下架该帖子')
    await loadPosts(true)
  } catch {
    message.error('下架失败，请稍后重试')
  } finally {
    postActionLoading.value = null
  }
}

const restorePost = async (row: AdminPost) => {
  try {
    postActionLoading.value = row.id
    await adminContentApi.restorePost(row.id)
    message.success('已恢复该帖子')
    await loadPosts(true)
  } catch {
    message.error('恢复失败，请稍后重试')
  } finally {
    postActionLoading.value = null
  }
}

/** 彻底删除：不可恢复，连带评论与收藏，故必须 Popconfirm 二次确认 */
const deletePost = async (row: AdminPost) => {
  try {
    postActionLoading.value = row.id
    await adminContentApi.deletePost(row.id)
    message.success('已彻底删除该帖子')
    await loadPosts(true)
  } catch {
    message.error('删除失败，请稍后重试')
  } finally {
    postActionLoading.value = null
  }
}

onMounted(() => {
  loadReferences()
  loadPosts(true)
})
onUnmounted(stopPreview)
</script>

<template>
  <div class="discover">
    <Card class="review-note" :bordered="false">
      <EyeOutlined /> 内容审核：帖子支持「下架 / 恢复」（逻辑下架，数据保留可还原）与「彻底删除」
      （不可恢复，连带评论与收藏）。参考音频的「管理员下架」独立于用户自己的「取消分享」，
      用户重新分享不会让已下架内容回到前台。
    </Card>

    <Tabs v-model:activeKey="activeTab" class="discover-tabs">
      <!-- Tab 1：参考音频分享 / 排行 -->
      <TabPane key="rank">
        <template #tab><span><FireOutlined /> 参考音频分享</span></template>

        <div class="rank-toolbar">
          <span class="rank-count">
            {{ onlyHiddenRefs ? '已下架' : '全部' }} {{ references.length }} 个音频
          </span>
          <div class="toolbar-right">
            <span class="filter-label">只看已下架</span>
            <Switch
              :checked="onlyHiddenRefs"
              size="small"
              @change="onOnlyHiddenRefsChange"
            />
            <Button size="small" @click="loadReferences">
              <ReloadOutlined /> 刷新
            </Button>
          </div>
        </div>

        <List :loading="rankLoading" class="ref-list">
          <List.Item v-for="item in references" :key="item.id" class="ref-item">
            <div class="ref-rank" :class="{ top: !item.adminHidden && item.useCount >= 1 }">
              {{ item.useCount }}
            </div>
            <div class="ref-body">
              <div class="ref-title">
                <span class="ref-name">{{ item.name }}</span>
                <Tag v-if="item.adminHidden" color="red">已下架</Tag>
                <Tag v-else-if="item.shared" color="green">分享中</Tag>
                <Tag v-else color="default">用户已取消分享</Tag>
                <Tag color="default">已用 {{ item.useCount }}</Tag>
              </div>
              <div class="ref-meta">
                <span>分享者：{{ item.ownerName }}</span>
                <span>{{ formatFileSize(item.fileSize) }}</span>
                <span v-if="item.sharedAt">分享于 {{ formatDateTimeShort(item.sharedAt) }}</span>
              </div>
              <div v-if="item.description" class="ref-desc">{{ item.description }}</div>
            </div>
            <div class="ref-actions">
              <Button
                size="small"
                :icon="h(previewingId === item.id ? StopOutlined : PlayCircleOutlined)"
                @click="togglePreview(item)"
              >
                {{ previewingId === item.id ? '停止' : '试听' }}
              </Button>
              <Popconfirm
                :title="item.adminHidden ? '确认恢复该参考音频？' : '下架后前台将不再展示，用户重新分享也不会恢复。确认下架？'"
                ok-text="确认"
                cancel-text="取消"
                @confirm="toggleRefHidden(item)"
              >
                <Button
                  size="small"
                  :danger="!item.adminHidden"
                  :loading="refActionLoading === item.id"
                >
                  {{ item.adminHidden ? '恢复' : '下架' }}
                </Button>
              </Popconfirm>
            </div>
          </List.Item>
        </List>

        <Empty
          v-if="!rankLoading && references.length === 0"
          :description="onlyHiddenRefs ? '没有被下架的参考音频' : '还没有人分享参考音频'"
        />
      </TabPane>

      <!-- Tab 2：讨论 -->
      <TabPane key="discuss">
        <template #tab>
          <span>
            <MessageOutlined /> 讨论
            <Tag v-if="removedCount > 0" color="red" style="margin-left: 6px">
              已下架 {{ removedCount }}
            </Tag>
          </span>
        </template>

        <div class="rank-toolbar">
          <span class="rank-count">共 {{ total }} 条（在架 {{ activeCount }} / 已下架 {{ removedCount }}）</span>
          <div class="toolbar-right">
            <Select
              v-model:value="postStatusFilter"
              size="small"
              style="width: 120px"
              :options="[
                { value: 'all', label: '全部' },
                { value: 'active', label: '仅在架' },
                { value: 'removed', label: '仅已下架' },
              ]"
              @change="onPostStatusChange"
            />
            <Button size="small" @click="reloadPosts">
              <ReloadOutlined /> 刷新
            </Button>
          </div>
        </div>

        <List :loading="discussLoading" class="post-list">
          <List.Item v-for="item in posts" :key="item.id" class="post-item">
            <div class="post-avatar">{{ (item.username || '?').slice(0, 1).toUpperCase() }}</div>
            <div class="post-body">
              <div class="post-head">
                <span class="post-user">{{ item.username }}</span>
                <Tag v-if="item.removed" color="red">已下架</Tag>
                <span v-if="item.createdAt" class="post-time">
                  {{ formatDateTimeShort(item.createdAt) }}
                </span>
                <span v-if="item.removedAt" class="post-time">
                  下架于 {{ formatDateTimeShort(item.removedAt) }}
                </span>
              </div>
              <div class="post-content" :class="{ dimmed: item.removed }">{{ item.content }}</div>
              <div class="post-meta">
                评论 {{ item.commentCount }} · 收藏 {{ item.favoriteCount }}
              </div>
            </div>
            <div class="ref-actions">
              <Button
                v-if="!item.removed"
                size="small"
                danger
                :loading="postActionLoading === item.id"
                @click="removePost(item)"
              >
                下架
              </Button>
              <Button
                v-else
                size="small"
                :icon="h(RollbackOutlined)"
                :loading="postActionLoading === item.id"
                @click="restorePost(item)"
              >
                恢复
              </Button>
              <Popconfirm
                title="彻底删除不可恢复，该帖子的评论与收藏会一并删除。确认删除？"
                ok-text="确认删除"
                cancel-text="取消"
                @confirm="deletePost(item)"
              >
                <Button size="small" danger :icon="h(DeleteOutlined)">删除</Button>
              </Popconfirm>
            </div>
          </List.Item>
        </List>

        <div v-if="!discussLoading && posts.length === 0" class="empty-posts">
          <Empty :description="postStatusFilter === 'removed' ? '没有被下架的帖子' : '还没有帖子'" />
        </div>

        <div v-if="hasMore" class="load-more">
          <Button :loading="loadingMore" :disabled="loadingMore" @click="loadPosts(false)">加载更多</Button>
        </div>
        <div v-else-if="posts.length > 0" class="load-more">
          <span class="no-more">没有更多了（共 {{ total }} 条）</span>
        </div>
      </TabPane>
    </Tabs>
  </div>
</template>

<style scoped>
.discover {
  max-width: 880px;
  margin: 0 auto;
}

.review-note {
  margin-bottom: 16px;
  background: #fafafa;
  color: #666;
  font-size: 13px;
}

.review-note code {
  background: #f0f0f0;
  padding: 1px 5px;
  border-radius: 3px;
}

.discover-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 16px;
}

/* ----- 参考音频排行 ----- */
.rank-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-label {
  color: #888;
  font-size: 13px;
}

.rank-count {
  color: #888;
  font-size: 13px;
}

.ref-list .ref-item {
  align-items: flex-start;
  padding: 14px 8px;
}

.ref-rank {
  flex: 0 0 28px;
  height: 28px;
  line-height: 28px;
  text-align: center;
  border-radius: 50%;
  background: #f0f0f0;
  color: #666;
  font-weight: 600;
  margin-right: 12px;
}

.ref-rank.top {
  background: #fff1f0;
  color: #cf1322;
}

.ref-body {
  flex: 1 1 auto;
  min-width: 0;
}

.ref-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ref-name {
  font-weight: 600;
  font-size: 15px;
}

.ref-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #999;
  font-size: 12px;
  margin-top: 4px;
}

.ref-desc {
  margin-top: 6px;
  color: #555;
  font-size: 13px;
  word-break: break-word;
}

.ref-actions {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
  margin-left: 12px;
}

/* ----- 讨论 ----- */
.post-list .post-item {
  align-items: flex-start;
  padding: 14px 4px;
}

.post-avatar {
  flex: 0 0 36px;
  height: 36px;
  line-height: 36px;
  text-align: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-weight: 600;
  margin-right: 12px;
}

.post-body {
  flex: 1 1 auto;
  min-width: 0;
}

.post-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
}

.post-user {
  font-weight: 600;
}

.post-time {
  color: #bbb;
  font-size: 12px;
}

.post-content {
  margin-top: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.post-content.dimmed {
  color: #999;
}

.post-meta {
  margin-top: 4px;
  color: #bbb;
  font-size: 12px;
}

.empty-posts {
  padding: 40px 0;
}

.load-more {
  text-align: center;
  padding: 16px 0;
}

.no-more {
  color: #bbb;
  font-size: 13px;
}
</style>