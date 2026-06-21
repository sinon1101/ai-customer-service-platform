import { defineStore } from 'pinia'

const TOKEN_KEY = 'aics_token'
const USER_KEY = 'aics_user'

// 登录态:token(原始 UUID,放 authorization 头)+ LoginUser(id/tenantId/username/nickName/role)。
// 持久化到 localStorage,刷新页面不掉线;后端 token TTL 24h 且每次请求续期。
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN',
    // ADMIN 在 demo 里可兼任坐席
    isAgent: (s) => s.user?.role === 'AGENT' || s.user?.role === 'ADMIN',
    displayName: (s) => s.user?.nickName || s.user?.username || ''
  },
  actions: {
    setToken(token) {
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
    },
    setUser(user) {
      this.user = user
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
