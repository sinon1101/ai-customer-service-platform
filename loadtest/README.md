# 压测 / 高并发断言(M7)

本目录是 M7「压测验证」的可复现成果:用 **JMeter** 做吞吐压测驱动看板,用 **手搓 PowerShell 脚本** 做正确性断言(抢单/熔断/限流/隔离)。两类工具分工:

| 工具 | 适用场景 | 为什么 |
|---|---|---|
| **JMeter**(`chat-load.jmx`) | `/chat` 持续并发负载、吞吐/延迟/错误率报告、驱动看板指标累加 | JMeter 强在压量与报表;一条 `.jmx` 即简历级成果物 |
| **PowerShell**(`*.ps1`) | 抢单 only-one-winner、熔断状态机、限流/隔离的断言型并发 | 断言逻辑重(解析 JSON 标志、控制故障开关、数赢家),GUI 压测工具反而笨重 |

> 前提:后端跑在 `http://127.0.0.1:8081`(可用环境变量 `AICS_BASE_URL` 覆盖);Docker 中间件已起;demo 账号 `acme_admin/acme_agent1/acme_agent2/globex_admin`(密码 `123456`)在库;部分脚本需一个已摄入文档的知识库 `kbId`(见各脚本 `-KbId` 参数)。

## 看板接口

- `GET /dashboard/overview`(ADMIN):当前租户的累计画像 —— 会话量、语义缓存命中率、降级率、限流拒绝、LLM 调用、真实 token 消耗、坐席效率(工单状态分布 + 平均等待/处理时长),以及治理实时态(熔断/故障/信号量/令牌桶)。
- 累计计数来自 Redis Hash `t:{tenantId}:metrics`(`MetricsCollector` 埋点);坐席效率从 `ticket` 表 SQL 聚合。

## JMeter 吞吐压测

1. 先登录建一个知识库并上传一篇文档(让 `/chat` 能真实作答、消耗 token),记下 `kbId`。
2. 打开 `chat-load.jmx`,把 User Defined Variables 里的 `kbId` 改为该值(也可调 `threads/loops/rampup`)。
3. GUI 跑,或 CLI:
   ```powershell
   jmeter -n -t chat-load.jmx -l result.jtl -e -o report
   ```
   `report/` 是 HTML 仪表盘;同时打开 `GET /dashboard/overview` 看指标随压测累加。

> 用户令牌桶容量小(演示值 5),高并发下会出现大量 429 —— 这正是限流在压测下的可视化证据;429 也计入看板 `chat.rateLimited`。

## 手搓断言脚本

```powershell
# 抢单:N 个坐席并发抢同一工单,断言恰好一人成功
./ticket-grab.ps1

# 熔断:故障注入 → 跳闸 OPEN → 冷却 → HALF_OPEN 探测 → 自动 CLOSED
./circuit-breaker.ps1 -KbId <你的kbId>

# 限流 + 隔离:12 并发同问看 200/429;acme 过载时 globex 不受影响
./rate-limit-isolation.ps1 -KbId <你的kbId>
```

`_common.ps1` 是公共助手(UTF-8 JSON、登录、runspace 并发),被各脚本 dot-source 引入,无需单独运行。
