# ============================================================
# M5/M7 熔断状态机断言(混沌工程)
# 故障注入 ON → 连发 /chat 打满失败窗口 → 断言熔断 CLOSED→OPEN(后续毫秒级短路降级);
# 故障 OFF + 冷却 → 一次探测 HALF_OPEN 成功 → 断言自动 CLOSED。
# 全程从 GET /dashboard/overview 读 governance.circuitBreaker.state(看板与 /governance/status 同源)。
# 可选 -KbId 让探测请求走真实知识库(更接近真实恢复路径)。
# ============================================================
param([long]$KbId = 0)
. "$PSScriptRoot\_common.ps1"

function Get-CbState($token) {
    (Invoke-Json -Method Get -Path "/dashboard/overview" -Token $token).data.governance.circuitBreaker.state
}
function Set-Fault($token, $on) {
    Invoke-Json -Method Post -Path ("/governance/fault?on={0}" -f $on.ToString().ToLower()) -Token $token | Out-Null
}
function Send-Chat($token, $msg) {
    $body = @{ message = $msg }
    if ($KbId -gt 0) { $body["kbId"] = $KbId }
    try { return (Invoke-Json -Method Post -Path "/chat" -Token $token -Body $body).data.degraded }
    catch { return "ERR" }
}

Write-Host "`n=== 熔断状态机断言 ===" -ForegroundColor Cyan
$admin = Login "acme_admin"
Write-Host ("初始熔断状态:{0}" -f (Get-CbState $admin))

# 1. 注入故障,连发 6 个不同问把失败窗口打满(最小样本 5、失败率阈值 0.5)
Set-Fault $admin $true
Write-Host "故障注入 ON,连发 6 个请求驱动跳闸..." -ForegroundColor Yellow
for ($i = 1; $i -le 6; $i++) {
    $d = Send-Chat $admin "熔断压测问题$i"
    Write-Host ("  #{0} degraded={1} state={2}" -f $i, $d, (Get-CbState $admin))
}
$openState = Get-CbState $admin
if ($openState -eq "OPEN") {
    Write-Host "[PASS] 失败累积触发 CLOSED→OPEN 跳闸。" -ForegroundColor Green
} else {
    Write-Host ("[FAIL] 期望 OPEN,实际 {0}" -f $openState) -ForegroundColor Red
}

# 2. 关故障 + 等冷却(CB_OPEN_COOLDOWN_MILLIS=15s,留余量 17s),再发一个探测请求
Set-Fault $admin $false
Write-Host "`n故障注入 OFF,等待冷却 17s 后发探测请求..." -ForegroundColor Yellow
Start-Sleep -Seconds 17
$probeDegraded = Send-Chat $admin "熔断恢复探测请求"
Start-Sleep -Milliseconds 500
$finalState = Get-CbState $admin
Write-Host ("探测请求 degraded={0},熔断状态={1}" -f $probeDegraded, $finalState)
if ($finalState -eq "CLOSED") {
    Write-Host "[PASS] HALF_OPEN 探测成功 → 自动恢复 CLOSED。" -ForegroundColor Green
} else {
    Write-Host ("[FAIL] 期望 CLOSED,实际 {0}" -f $finalState) -ForegroundColor Red
}
