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
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/users',
    children: [
      {
        path: 'users',
        name: 'AdminUsers',
        component: () => import('@/views/AdminUsers.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'books',
        name: 'AdminBooks',
        component: () => import('@/views/AdminBooks.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'reference',
        name: 'AdminReference',
        component: () => import('@/views/AdminReference.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'tts-nodes',
        name: 'AdminTtsNodes',
        component: () => import('@/views/AdminTtsNodes.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'credits',
        name: 'AdminCredits',
        component: () => import('@/views/AdminCredits.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'app-version',
        name: 'AdminAppVersion',
        component: () => import('@/views/AdminAppVersion.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'discover',
        name: 'Discover',
        component: () => import('@/views/Discover.vue'),
        meta: { requiresAdmin: true },
      },
    ],
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
    adminMode: true,
    redirectQueryKey: 'redirect',
    authenticatedRedirectNames: ['Login'],
  }),
)

export default router
