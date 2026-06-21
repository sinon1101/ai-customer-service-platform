# 前端 SPA(AI 智能客服平台)

对接后端 M0~M7 全部 API 的多租户管理前端。**Vue 3 + Vite + Element Plus + Pinia + Vue Router**。
(`frontend/` 是 legacy 点评 nginx SPA,与本平台无关;本目录 `frontend-new/` 才是新平台前端。)

## 运行

```bash
cd frontend-new
npm install
npm run dev      # http://localhost:5173,Vite 代理 /api、/ws → 后端 :8081
npm run build    # 产物在 dist/
```

> 后端未配 CORS:dev 阶段靠 Vite 代理(`vite.config.js`)把 `/api/*` 剥前缀转发到 `:8081`,
> `/ws/*` 以 `ws:true` 升级转发。生产可把 `dist/` 交给 nginx 并同样反代后端。

前置:后端跑在 8081(用 PowerShell 起以继承 `API-KEY`),中间件(MySQL/Redis/RocketMQ)已启动。

## 演示账号(密码均 `123456`)

| 账号 | 角色 | 用途 |
|---|---|---|
| `acme_admin` | ADMIN | 看板 + 全功能(Acme 租户) |
| `acme_agent1` / `acme_agent2` | AGENT | 坐席抢单演示 |
| `globex_admin` | ADMIN | 跨租户隔离演示(Globex 租户) |

## 页面与对接

| 路由 | 页面 | 对接 API |
|---|---|---|
| `/login` `/register` | 登录 / 租户入驻 | `/auth/login` `/auth/register` `/auth/me` |
| `/chat` | 访客智能问答 | `POST /chat/stream`(SSE 流式)+ `/ticket/transfer`(转人工) |
| `/kb` | 知识库管理 | `/kb` CRUD + `/kb/{id}/documents` 上传/轮询进度 |
| `/agent` | 坐席工作台 | `/ticket/pending` 轮询 + `/ticket/{id}/claim` 抢单 + `/ws/chat` 实时会话 |
| `/dashboard` | 统计看板(ADMIN) | `/dashboard/overview` + `/governance/fault` 故障注入 |

## 关键技术点

- **SSE 流式**(`src/api/stream.js`):`/chat/stream` 是 POST,`EventSource` 只支持 GET,
  故用 `fetch` + `ReadableStream` 手动解析 `meta`/`message`/`done`/`error` 事件,
  展示 `cached`(命中语义缓存)/`degraded`(降级兜底)/`sources`(引用溯源)标记。
- **WebSocket 实时会话**(`src/composables/useTicketSocket.js` + `components/RealtimeSession.vue`):
  `/ws/chat?ticketId=&token=`,token 走 query(浏览器 WS 不便带 header)。访客侧(Chat 抽屉)
  与坐席侧(工作台)共用同一会话组件:先拉历史(`/ticket/{id}`)再建 WS 收实时增量。
- **统一鉴权**(`src/api/http.js`):axios 请求拦截器带原始 token 到 `authorization` 头;
  响应拦截器解包 `Result{success,data,errorMsg}`,401 登出、429/503 友好提示。
- **角色守卫**(`src/router/index.js`):看板仅 ADMIN 可见(侧边栏 + 路由双重控制)。
