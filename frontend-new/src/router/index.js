import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('@/views/Register.vue'), meta: { public: true } },
  // 匿名访客挂件:独立全屏页,免登录(进入时自领游客会话)。?tenant=acme 指定租户
  { path: '/widget', name: 'widget', component: () => import('@/views/Widget.vue'), meta: { public: true, standalone: true } },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/chat',
    children: [
      { path: 'chat', name: 'chat', component: () => import('@/views/Chat.vue'), meta: { title: '问答调试台' } },
      { path: 'kb', name: 'kb', component: () => import('@/views/KnowledgeBase.vue'), meta: { title: '知识库管理' } },
      { path: 'agent', name: 'agent', component: () => import('@/views/AgentDesk.vue'), meta: { title: '坐席工作台' } },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '统计看板', adminOnly: true }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫 + 角色守卫(看板仅 ADMIN)
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && !auth.isAdmin) {
    return { name: 'chat' }
  }
  // 独立页(访客挂件)始终放行,不受后台登录态影响 —— 支持后台 + 访客同浏览器双开
  if (to.meta.public && auth.isLoggedIn && !to.meta.standalone) {
    return { name: 'chat' }
  }
  return true
})

export default router
