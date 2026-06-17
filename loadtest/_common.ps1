# ============================================================
# M7 压测/断言脚本 — 公共助手(PowerShell 5.1)
# 用法:在各脚本顶部 . "$PSScriptRoot\_common.ps1" 引入。
# 中文 body 强制 UTF-8 编码(避免 PowerShell 默认 GBK 把中文打乱)。
# ============================================================

$script:BaseUrl = if ($env:AICS_BASE_URL) { $env:AICS_BASE_URL } else { "http://127.0.0.1:8081" }

# 发 JSON(UTF-8),带可选 authorization 头;失败也尽量解析出响应体
function Invoke-Json {
    param([string]$Method, [string]$Path, [string]$Token, $Body)
    $uri = "$script:BaseUrl$Path"
    $headers = @{}
    if ($Token) { $headers["authorization"] = $Token }
    $params = @{ Uri = $uri; Method = $Method; Headers = $headers }
    if ($null -ne $Body) {
        $json = ($Body | ConvertTo-Json -Compress -Depth 6)
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params["ContentType"] = "application/json; charset=utf-8"
    }
    return Invoke-RestMethod @params
}

function Login {
    param([string]$Username, [string]$Password = "123456")
    $r = Invoke-Json -Method Post -Path "/auth/login" -Body @{ username = $Username; password = $Password }
    if (-not $r.success) { throw "登录失败 $Username : $($r.errorMsg)" }
    return $r.data
}

# 并发执行:对 $Items 中每个元素跑一份 $Script(自包含 scriptblock,签名 param($item,$baseUrl)),
# 用 runspace pool 尽量同时发起,返回各自结果数组。
function Invoke-Concurrent {
    param([scriptblock]$Script, [object[]]$Items)
    $pool = [runspacefactory]::CreateRunspacePool(1, [Math]::Max(1, $Items.Count))
    $pool.Open()
    $jobs = @()
    foreach ($it in $Items) {
        $ps = [powershell]::Create()
        $ps.RunspacePool = $pool
        [void]$ps.AddScript($Script).AddArgument($it).AddArgument($script:BaseUrl)
        $jobs += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
    }
    $results = @()
    foreach ($j in $jobs) {
        try { $results += $j.PS.EndInvoke($j.Handle) } catch { $results += $_.Exception.Message }
        $j.PS.Dispose()
    }
    $pool.Close(); $pool.Dispose()
    return $results
}

Write-Host "[common] BaseUrl = $script:BaseUrl" -ForegroundColor DarkGray
