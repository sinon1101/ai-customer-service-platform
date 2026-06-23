import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { resolveToken } from '@/api/token'

// 工单实时会话(M6):/ws/chat?ticketId=&token=
//   - 握手鉴权:token 走 query(浏览器 WS 不便带 header),后端校验登录态 + 参与方 + 工单未结束。
//   - 发送:直接发纯文本 content(后端按连接身份补全 WsMessage)。
//   - 接收:后端广播完整 WsMessage JSON {type, ticketId, senderRole, senderId, senderName, content, timestamp}。
// 访客侧(Chat)与坐席侧(AgentDesk)共用本组合式函数。
export function useTicketSocket() {
  const messages = ref([]) // 已收到的 WsMessage
  const connected = ref(false)
  let ws = null
  let currentTicketId = null

  function connect(ticketId, { onMessage, as } = {}) {
    close()
    currentTicketId = ticketId
    // 注意:不在此清空 messages —— 历史消息由调用方(RealtimeSession.init)在 connect 之前
    // 用工单详情回填,connect 后只追加实时新帧。若在这里清空会把已加载的历史抹掉(重开会话丢上下文)。

    // 经 Vite 代理(ws:true);用当前页面 host,协议跟随 http/https
    // as:声明本次连接的视角(VISITOR/AGENT)。Demo 里同一账号可能既是访客又是接单坐席,
    //    后端需据此判定 senderRole,否则会一律按「访客优先」把坐席消息也标成访客。
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const asParam = as ? `&as=${encodeURIComponent(as)}` : ''
    const url = `${proto}://${location.host}/ws/chat?ticketId=${ticketId}&token=${encodeURIComponent(resolveToken())}${asParam}`
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
