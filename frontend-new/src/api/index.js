import http from './http'

// ─────────────── 鉴权(M1)───────────────
export const authApi = {
  login: (form) => http.post('/auth/login', form),
  register: (form) => http.post('/auth/register', form),
  me: () => http.get('/auth/me'),
  logout: () => http.post('/auth/logout')
}

// ─────────────── 知识库 + 文档摄入(M1/M2)───────────────
export const kbApi = {
  list: () => http.get('/kb/list'),
  get: (id) => http.get(`/kb/${id}`),
  create: (form) => http.post('/kb', form),
  update: (id, form) => http.put(`/kb/${id}`, form),
  remove: (id) => http.delete(`/kb/${id}`),
  uploadDoc: (kbId, form) => http.post(`/kb/${kbId}/documents`, form),
  listDocs: (kbId) => http.get(`/kb/${kbId}/documents`)
}

// ─────────────── RAG 问答(M3/M4)───────────────
// 非流式 /chat 走 axios;流式 /chat/stream 走 fetch(见 chatStream)。
export const chatApi = {
  chat: (req) => http.post('/chat', req)
}

// ─────────────── 工单 / 人工坐席(M6)───────────────
export const ticketApi = {
  transfer: (form) => http.post('/ticket/transfer', form),
  pending: () => http.get('/ticket/pending'),
  claim: (id) => http.post(`/ticket/${id}/claim`),
  close: (id) => http.post(`/ticket/${id}/close`),
  mine: () => http.get('/ticket/mine'),
  detail: (id) => http.get(`/ticket/${id}`)
}

// ─────────────── 看板 / 治理(M5/M7)───────────────
export const dashboardApi = {
  overview: () => http.get('/dashboard/overview')
}
export const governanceApi = {
  status: () => http.get('/governance/status'),
  fault: (on) => http.post(`/governance/fault?on=${on}`)
}
