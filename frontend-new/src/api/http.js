import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { useVisitorStore } from '@/store/visitor'
import { resolveToken } from '@/api/token'
import router from '@/router'

// 统一走 /api 前缀,Vite 代理剥前缀转发到后端 :8081。
const http = axios.create({ baseURL: '/api', timeout: 30000 })

// 请求拦截:带上 authorization 头(后端读原始 token,无 Bearer 前缀)。
// token 优先访客会话(挂件标签),否则后台登录态。
http.interceptors.request.use((config) => {
  const token = resolveToken()
  if (token) {
    config.headers.authorization = token
  }
  return config
})

// 响应拦截:后端统一 Result {success, errorMsg, data, total}。
//   success=true  → 解包返回 { data, total }
//   success=false → 弹错误并 reject
//   HTTP 401/429/503 → 走 handleHttpError
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'success' in body) {
      if (body.success) {
        return { data: body.data, total: body.total }
      }
      ElMessage.error(body.errorMsg || '请求失败')
      return Promise.reject(new Error(body.errorMsg || '请求失败'))
    }
    // 非 Result 结构(极少见)直接透传
    return { data: body }
  },
  (error) => {
    handleHttpError(error)
    return Promise.reject(error)
  }
)

function handleHttpError(error) {
  const status = error.response?.status
  if (status === 401) {
    // 访客挂件:清访客会话并提示重进,不跳后台登录页
    const visitor = useVisitorStore()
    if (visitor.token || router.currentRoute.value.name === 'widget') {
      visitor.clear()
      ElMessage.error('会话已失效,请刷新页面重新进入')
    } else {
      const auth = useAuthStore()
      auth.clear()
      ElMessage.error('登录已过期,请重新登录')
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login' })
      }
    }
  } else if (status === 429) {
    ElMessage.warning('请求过于频繁,已被限流(HTTP 429)')
  } else if (status === 503) {
    ElMessage.warning('系统过载,请稍后再试(HTTP 503)')
  } else {
    ElMessage.error(error.message || '网络异常')
  }
}

export default http
