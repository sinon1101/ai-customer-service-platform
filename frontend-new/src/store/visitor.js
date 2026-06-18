import { defineStore } from 'pinia'

const V_TOKEN_KEY = 'aics_visitor_token'
const V_NICK_KEY = 'aics_visitor_nick'
const V_TENANT_KEY = 'aics_visitor_tenant'

// 匿名访客会话态。与后台登录态(localStorage)刻意分开:
//   - 存 sessionStorage —— 按浏览器标签页隔离,因此「后台管理」与「访客挂件」可在同一浏览器双开互不覆盖。
//   - token 由 POST /visitor/session 领取(role=VISITOR),与登录 token 同构,复用同一鉴权链。
export const useVisitorStore = defineStore('visitor', {
  state: () => ({
    token: sessionStorage.getItem(V_TOKEN_KEY) || '',
    nickName: sessionStorage.getItem(V_NICK_KEY) || '',
    tenantName: sessionStorage.getItem(V_TENANT_KEY) || ''
  }),
  getters: {
    hasSession: (s) => !!s.token
  },
  actions: {
    setSession({ token, nickName, tenantName }) {
      this.token = token || ''
      this.nickName = nickName || ''
      this.tenantName = tenantName || ''
      sessionStorage.setItem(V_TOKEN_KEY, this.token)
      sessionStorage.setItem(V_NICK_KEY, this.nickName)
      sessionStorage.setItem(V_TENANT_KEY, this.tenantName)
    },
    clear() {
      this.token = ''
      this.nickName = ''
      this.tenantName = ''
      sessionStorage.removeItem(V_TOKEN_KEY)
      sessionStorage.removeItem(V_NICK_KEY)
      sessionStorage.removeItem(V_TENANT_KEY)
    }
  }
})
