# ============================================================
# M5/M7 限流 + 隔离 并发断言
# ① 限流:单用户 12 并发同问 → 部分 200、部分 429(用户令牌桶容量 5)。
# ② 隔离:acme 暴涨打满信号量(部分降级)的同时,globex 请求 degraded=false 不受影响。
# 可选 -KbId 指向已摄入的知识库以产生真实作答(不传也能验限流/降级)。
# ============================================================
param([long]$KbId = 0)
. "$PSScriptRoot\_common.ps1"

Write-Host "`n=== ① 限流:单用户 12 并发同问 ===" -ForegroundColor Cyan
$token = Login "acme_admin"
$payload = @{ message = "购买的商品7天内可以退款吗" }
if ($KbId -gt 0) { $payload["kbId"] = $KbId }
$bodyJson = ($payload | ConvertTo-Json -Compress)

$fire = {
    param($idx, $baseUrl)
    $json = '__BODY__'
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $r = Invoke-RestMethod -Uri "$baseUrl/chat" -Method Post -Headers @{ authorization = '__TOKEN__' } `
            -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
        return [pscustomobject]@{ code = 200; degraded = $r.data.degraded; cached = $r.data.cached }
    } catch {
        $code = -1
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode.value__ }
        return [pscustomobject]@{ code = $code; degraded = $null; cached = $null }
    }
}
$fire = [scriptblock]::Create($fire.ToString().Replace("__BODY__", $bodyJson).Replace("__TOKEN__", $token))

$res = Invoke-Concurrent -Script $fire -Items (1..12)
$ok = @($res | Where-Object { $_.code -eq 200 }).Count
$rl = @($res | Where-Object { $_.code -eq 429 }).Count
$deg = @($res | Where-Object { $_.degraded -eq $true }).Count
Write-Host ("结果:200={0}  429={1}  其中降级={2}" -f $ok, $rl, $deg) -ForegroundColor Yellow
if ($rl -gt 0 -and $ok -gt 0) {
    Write-Host "[PASS] 限流生效:部分放行、部分 429。" -ForegroundColor Green
} else {
    Write-Host "[WARN] 未观测到预期的 200/429 混合(可能令牌桶已被前次测试耗尽,稍等补充后重试)。" -ForegroundColor Red
}

Write-Host "`n=== ② 隔离:acme 打满信号量时 globex 不受影响 ===" -ForegroundColor Cyan
$globex = Login "globex_admin"
# acme 5 并发不同问把信号量(名额 3)打满 → 部分降级;同时 globex 单发一问应正常
$gPayload = @{ message = "你们的配送范围是哪里" } | ConvertTo-Json -Compress
$gBytes = [System.Text.Encoding]::UTF8.GetBytes($gPayload)
$globexJob = Start-Job -ScriptBlock {
    param($baseUrl, $token, $bytes)
    try {
        $r = Invoke-RestMethod -Uri "$baseUrl/chat" -Method Post -Headers @{ authorization = $token } `
            -ContentType "application/json; charset=utf-8" -Body $bytes
        return $r.data.degraded
    } catch { return "ERR:$($_.Exception.Message)" }
} -ArgumentList $script:BaseUrl, $globex, $gBytes

$acmeFire = {
    param($idx, $baseUrl)
    $json = "{`"message`":`"压测隔离问题$idx 请简述售后政策第$idx 条`"}"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $r = Invoke-RestMethod -Uri "$baseUrl/chat" -Method Post -Headers @{ authorization = '__TOKEN__' } `
            -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
        return $r.data.degraded
    } catch { return "429" }
}
$acmeFire = [scriptblock]::Create($acmeFire.ToString().Replace("__TOKEN__", $token))
$acmeRes = Invoke-Concurrent -Script $acmeFire -Items (1..5)
$globexDegraded = Receive-Job -Job $globexJob -Wait -AutoRemoveJob

Write-Host ("acme 5 并发 degraded 分布:{0}" -f (($acmeRes | ForEach-Object { "$_" }) -join ", ")) -ForegroundColor Yellow
Write-Host ("globex 同时段请求 degraded = {0}" -f $globexDegraded) -ForegroundColor Yellow
if ("$globexDegraded" -eq "False") {
    Write-Host "[PASS] 租户隔离:acme 过载不波及 globex。" -ForegroundColor Green
} else {
    Write-Host "[WARN] globex 也降级了,可能是限流/熔断或 globex 无可用 KB 导致,请结合日志判断。" -ForegroundColor Red
}
