# ============================================================
# M6/M7 抢单 only-one-winner 并发断言
# N 个坐席并发抢同一张工单 → 断言「恰好一人成功」(Redisson 锁 + DB 条件更新双保险)。
# 依赖 demo 坐席账号 acme_agent1/2(+ ADMIN 兼坐席),见 项目进度.md。
# ============================================================
. "$PSScriptRoot\_common.ps1"

Write-Host "`n=== 抢单 only-one-winner 并发断言 ===" -ForegroundColor Cyan

# 1. 访客建一张全新 WAITING 工单
$visitor = Login "acme_admin"
$convId = "loadtest-grab-" + ([guid]::NewGuid().ToString("N").Substring(0, 8))
$t = Invoke-Json -Method Post -Path "/ticket/transfer" -Token $visitor -Body @{
    conversationId = $convId; reason = "USER_REQUEST"; lastQuestion = "压测:抢单并发"
}
$ticketId = $t.data.id
Write-Host ("建单完成 ticketId={0} status={1}" -f $ticketId, $t.data.status)

# 2. 坐席登录拿 token(ADMIN 在 demo 里可兼任坐席)
$agentTokens = @(
    (Login "acme_agent1"),
    (Login "acme_agent2"),
    (Login "acme_admin")
)

# 3. 并发抢同一张工单
$claim = {
    param($token, $baseUrl)
    try {
        $r = Invoke-RestMethod -Uri "$baseUrl/ticket/claim_PLACEHOLDER" -Method Post `
            -Headers @{ authorization = $token } -ErrorAction Stop
        return [pscustomobject]@{ success = $r.success; msg = $r.errorMsg; agent = $r.data.agentName }
    } catch {
        return [pscustomobject]@{ success = $false; msg = $_.Exception.Message; agent = $null }
    }
}
# 把 ticketId 注入 scriptblock(runspace 不共享外层变量)
$claim = [scriptblock]::Create($claim.ToString().Replace("claim_PLACEHOLDER", "$ticketId/claim"))

$results = Invoke-Concurrent -Script $claim -Items $agentTokens

# 4. 断言
$winners = @($results | Where-Object { $_.success -eq $true })
Write-Host "`n抢单结果:" -ForegroundColor Yellow
$results | ForEach-Object { Write-Host ("  success={0} agent={1} msg={2}" -f $_.success, $_.agent, $_.msg) }

if ($winners.Count -eq 1) {
    Write-Host ("`n[PASS] 恰好一人抢到(坐席={0}),其余 {1} 人被锁/CAS 挡下。" -f $winners[0].agent, ($results.Count - 1)) -ForegroundColor Green
} else {
    Write-Host ("`n[FAIL] 期望 1 个赢家,实际 {0} 个!" -f $winners.Count) -ForegroundColor Red
}

# 5. 清理:结束该工单
Invoke-Json -Method Post -Path "/ticket/$ticketId/close" -Token $visitor | Out-Null
Write-Host "已关闭测试工单 $ticketId" -ForegroundColor DarkGray
