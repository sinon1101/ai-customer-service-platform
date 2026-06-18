import { useAuthStore } from '@/store/auth'
import { useVisitorStore } from '@/store/visitor'

// 统一的请求 token 解析:优先用访客会话 token(sessionStorage,仅访客挂件标签里有),
// 否则回退到后台登录 token(localStorage)。http / SSE / WebSocket 三处共用,保证
// 访客挂件与后台管理在同一浏览器双开时各用各的身份。
export function resolveToken() {
  const visitor = useVisitorStore()
  if (visitor.token) return visitor.token
  const auth = useAuthStore()
  return auth.token || ''
}
