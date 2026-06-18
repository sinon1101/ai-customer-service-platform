import { useAuthStore } from '@/store/auth'
import { useVisitorStore } from '@/store/visitor'
import { resolveToken } from '@/api/token'

// POST /chat/stream 是 SSE,但 EventSource 只支持 GET,故用 fetch + ReadableStream 手动解析。
// 后端事件协议(见 ChatServiceImpl):
//   event: meta    data: {conversationId, sources, cached, degraded}
//   event: message data: <一个 token 文本>
//   event: done    data: [DONE]
//   event: error   data: <错误文案>(限流/校验失败)
//
// 回调:
//   onMeta(metaObj) / onToken(text) / onDone() / onError(msg)
// 返回一个带 .abort() 的句柄,便于组件卸载时中断。
export function streamChat(req, { onMeta, onToken, onDone, onError } = {}) {
  const controller = new AbortController()

  fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      authorization: resolveToken()
    },
    body: JSON.stringify(req),
    signal: controller.signal
  })
    .then(async (resp) => {
      if (!resp.ok) {
        if (resp.status === 401) {
          // 访客挂件清访客会话,后台清登录态(避免互相误清)
          const visitor = useVisitorStore()
          if (visitor.token) visitor.clear()
          else useAuthStore().clear()
        }
        onError && onError(`请求失败(HTTP ${resp.status})`)
        return
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE 以空行分隔事件块
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const raw = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          dispatch(raw, { onMeta, onToken, onDone, onError })
        }
      }
    })
    .catch((err) => {
      if (err.name === 'AbortError') return
      onError && onError(err.message || '流式连接异常')
    })

  return { abort: () => controller.abort() }
}

function dispatch(rawBlock, { onMeta, onToken, onDone, onError }) {
  let event = 'message'
  const dataLines = []
  for (const line of rawBlock.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // 去掉 "data:" 后保留首个空格之后的内容(token 可能含前导空格,只剥一个)
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  const data = dataLines.join('\n')

  if (event === 'meta') {
    try {
      onMeta && onMeta(JSON.parse(data))
    } catch {
      /* 忽略解析异常 */
    }
  } else if (event === 'message') {
    onToken && onToken(data)
  } else if (event === 'done') {
    onDone && onDone()
  } else if (event === 'error') {
    onError && onError(data)
  }
}
