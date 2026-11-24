import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { createAuthGuard } from '@audiobook/shared'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        redirect: '/store',
      },
      {
        path: 'store',
        name: 'BookStore',
        component: () => import('@/views/BookStore.vue'),
      },
      {
        // 我的书架：书城加入的书在这里集中管理与进入阅读
        path: 'shelf',
        name: 'Shelf',
        component: () => import('@/views/Shelf.vue'),
      },
      {
        path: 'tts',
        name: 'TtsSynthesis',
        component: () => import('@/views/TtsSynthesis.vue'),
      },
      {
        path: 'references',
        name: 'ReferenceLibrary',
        component: () => import('@/views/ReferenceLibrary.vue'),
      },
      {
        path: 'tasks',
        name: 'TaskList',
        component: () => import('@/views/TaskList.vue'),
      },
      {
        path: 'player/:taskId',
        name: 'Player',
        component: () => import('@/views/Player.vue'),
      },
      {
        // 书城书籍阅读页：有合成音频的书边读边播，无音频的书纯文本阅读
        path: 'reader/:bookId',
        name: 'Reader',
        component: () => import('@/views/Reader.vue'),
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue'),
      },
      {
        path: 'credits',
        name: 'Credits',
        component: () => import('@/views/Credits.vue'),
      },
      {
        path: 'discover',
        name: 'Discover',
        component: () => import('@/views/Discover.vue'),
      },
    ],
  },
  {
    // 兜底：任何未匹配的路径展示 404 页，而不是白屏
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(
  createAuthGuard({
    useAuthStore,
    loginRouteName: 'Login',
    redirectQueryKey: 'redirect',
    authenticatedRedirectNames: ['Login', 'Register'],
  }),
)

export default router
