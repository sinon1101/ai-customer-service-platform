import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'

// 工单实时会话(M6):/ws/chat?ticketId=&token=
//   - 握手鉴权:token 走 query(浏览器 WS 不便带 header),后端校验登录态 + 参与方 + 工单未结束。
//   - 发送:直接发纯文本 content(后端按连接身份补全 WsMessage)。
//   - 接收:后端广播完整 WsMessage JSON {type, ticketId, senderRole, senderId, senderName, content, timestamp}。
// 访客侧(Chat)与坐席侧(AgentDesk)共用本组合式函数。
export function useTicketSocket() {
  const auth = useAuthStore()
  const messages = ref([]) // 已收到的 WsMessage
  const connected = ref(false)
  let ws = null
  let currentTicketId = null

  function connect(ticketId, { onMessage } = {}) {
    close()
    currentTicketId = ticketId
    messages.value = []

    // 经 Vite 代理(ws:true);用当前页面 host,协议跟随 http/https
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${proto}://${location.host}/ws/chat?ticketId=${ticketId}&token=${encodeURIComponent(auth.token)}`
    ws = new WebSocket(url)

    ws.onopen = () => {
      connected.value = true
    }
    ws.onmessage = (evt) => {
      try {
        const msg = JSON.parse(evt.data)
        messages.value.push(msg)
        onMessage && onMessage(msg)
      } catch {
        /* 忽略非 JSON 帧 */
      }
    }
    ws.onclose = () => {
      connected.value = false
    }
    ws.onerror = () => {
      connected.value = false
      ElMessage.error('实时连接失败(请确认工单未关闭且你是参与方)')
    }
  }

  function send(text) {
    if (!text || !text.trim()) return false
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      ElMessage.warning('连接未就绪')
      return false
    }
    ws.send(text)
    return true
  }

  function close() {
    if (ws) {
      try {
        ws.close()
      } catch {
        /* ignore */
      }
      ws = null
    }
    connected.value = false
    currentTicketId = null
  }

  return { messages, connected, connect, send, close, get ticketId() { return currentTicketId } }
}
