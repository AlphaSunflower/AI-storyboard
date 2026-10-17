# 端到端验收：入口、注册关闭、两个账号、6 个服务端口、模型网关（含一次真实模型调用）、内存占用
#
# 用法（在能访问入口的机器上跑；Linux 目标机请用 scripts/smoke-test.sh）：
#   powershell -File scripts\verify.ps1
#   powershell -File scripts\verify.ps1 -EnvFile .env.test -BaseUrl http://127.0.0.1:18080
#   powershell -File scripts\verify.ps1 -SkipModelCall
param(
    [string]$BaseUrl,
    [string]$EnvFile = '.env',
    [switch]$SkipModelCall
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_env.ps1')

$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envMap = Get-StackEnv -StackDir $StackDir -EnvFile $EnvFile
$prefix = if ($envMap['STACK_PREFIX']) { $envMap['STACK_PREFIX'] } else { 'ai-lite' }
if (-not $BaseUrl) { $BaseUrl = "http://127.0.0.1:$($envMap['HTTP_PORT'])" }
$BaseUrl = $BaseUrl.TrimEnd('/')
$gatewayPort = if ($envMap['GATEWAY_PORT']) { $envMap['GATEWAY_PORT'] } else { '8083' }
$adminEmail = 'admin@qq.com'
$secondEmail = if ($envMap['SECOND_ACCOUNT_EMAIL']) { $envMap['SECOND_ACCOUNT_EMAIL'] } else { 'test@qq.com' }
$script:tempBody = Join-Path ([System.IO.Path]::GetTempPath()) "verify-body-$PID.json"

$script:pass = 0
$script:fail = 0

function Check([string]$name, [scriptblock]$body) {
    try {
        $detail = & $body
        Write-Host ("  [OK]   {0,-42} {1}" -f $name, $detail)
        $script:pass++
    } catch {
        Write-Host ("  [FAIL] {0,-42} {1}" -f $name, $_.Exception.Message)
        $script:fail++
    }
}

# 用 curl.exe 发请求并把状态码与响应体分开返回。
# 关键点：请求体写进临时文件后用 -d @file 传，绝不把 JSON 放在命令行上——
# PowerShell 5.1 传给原生 exe 的参数会吃掉内层双引号，导致服务端收到非法 JSON。
function Invoke-Curl([string]$method, [string]$url, [string]$token, [string]$json, [string]$origin) {
    $curlArgs = @('-s', '-w', "`n__STATUS__%{http_code}", '-X', $method, $url, '--max-time', '150')
    if ($token) { $curlArgs += @('-H', "Authorization: Bearer $token") }
    if ($origin) { $curlArgs += @('-H', "Origin: $origin") }
    if ($json) {
        [System.IO.File]::WriteAllText($script:tempBody, $json, (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @('-H', 'Content-Type: application/json', '-d', "@$script:tempBody")
    }
    $raw = (& curl.exe @curlArgs 2>&1 | Out-String)
    $marker = '__STATUS__'
    $idx = $raw.LastIndexOf($marker)
    if ($idx -lt 0) { return [pscustomobject]@{ Status = '???'; Body = $raw.Trim() } }
    return [pscustomobject]@{
        Status = $raw.Substring($idx + $marker.Length).Trim()
        Body   = $raw.Substring(0, $idx).Trim()
    }
}

function Convert-FromJson([string]$text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return $text | ConvertFrom-Json } catch { return $null }
}

Write-Host "验收目标: $BaseUrl（env=$EnvFile，容器前缀=$prefix）"
Write-Host ''
Write-Host '[1] 入口与静态站'
Check 'GET /healthz = 200' {
    $r = Invoke-Curl 'GET' "$BaseUrl/healthz"
    if ($r.Status -ne '200') { throw "HTTP $($r.Status)" }
    'HTTP 200'
}
Check 'GET / 返回主站 HTML' {
    $r = Invoke-Curl 'GET' "$BaseUrl/"
    if ($r.Status -ne '200' -or $r.Body -notmatch 'id="root"') { throw "HTTP $($r.Status)" }
    "HTTP 200, $($r.Body.Length) bytes"
}

Write-Host ''
Write-Host '[2] 注册已关闭（后端注册接口代码未改，入口 nginx 拦截）'
Check 'POST /api/auth/register = 403 + 40301' {
    $r = Invoke-Curl 'POST' "$BaseUrl/api/auth/register" $null '{"email":"nobody@qq.com","password":"123456","displayName":"nobody"}'
    if ($r.Status -ne '403') { throw "HTTP $($r.Status)（期望 403）$($r.Body)" }
    if ($r.Body -notmatch '40301') { throw "响应体里没有 40301: $($r.Body)" }
    'HTTP 403 + code 40301'
}

Write-Host ''
Write-Host '[3] 两个账号与业务接口'
Check "$adminEmail 登录" {
    $r = Invoke-Curl 'POST' "$BaseUrl/api/auth/login" $null (@{ email = $adminEmail; password = '123456' } | ConvertTo-Json -Compress)
    $body = Convert-FromJson $r.Body
    if (-not $body.data.accessToken) { throw "HTTP $($r.Status) $($r.Body)" }
    $script:adminToken = $body.data.accessToken
    "displayName=$($body.data.displayName)"
}
Check "$secondEmail 登录" {
    $r = Invoke-Curl 'POST' "$BaseUrl/api/auth/login" $null (@{ email = $secondEmail; password = '123456' } | ConvertTo-Json -Compress)
    $body = Convert-FromJson $r.Body
    if (-not $body.data.accessToken) { throw "HTTP $($r.Status) $($r.Body)" }
    $script:secondToken = $body.data.accessToken
    "displayName=$($body.data.displayName)"
}
Check 'GET /api/projects（admin token）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $r = Invoke-Curl 'GET' "$BaseUrl/api/projects" $script:adminToken
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    $body = Convert-FromJson $r.Body
    $items = if ($body.data.records) { $body.data.records } else { $body.data }
    "项目数=$(if ($items) { @($items).Count } else { 0 })"
}
Check '库里恰好 2 个账号' {
    $out = & docker exec "$prefix-postgres" psql -U postgres -d ai_storyboard -tAc 'select count(*) from users' 2>&1
    $count = ($out | Select-Object -First 1).Trim()
    if ($count -ne '2') { throw "实际 $count 个（期望 2）：$out" }
    "$count 个"
}
Check 'GET /api/agent/**（moon-agent 同 JVM）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $r = Invoke-Curl 'GET' "$BaseUrl/api/agent/conversations?projectId=00000000-0000-0000-0000-000000000000" $script:adminToken
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    'HTTP 200'
}
Check 'GET /api/library/**（asset-service）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $r = Invoke-Curl 'GET' "$BaseUrl/api/library/assets?page=1&size=1" $script:adminToken
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    'HTTP 200'
}
Check 'GET /api/canvas/health（canvas）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $r = Invoke-Curl 'GET' "$BaseUrl/api/canvas/health" $script:adminToken
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    'HTTP 200'
}
# 画布写操作要带 Origin 头测：浏览器对 POST/PATCH/PUT 会带 Origin，canvas 据此做 CORS 校验；
# PUBLIC_ORIGIN 配错时会静默 403（响应体 "Invalid CORS request"，日志无痕迹）。
Check '画布写操作 PATCH（带 Origin）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $project = (& docker exec "$prefix-postgres" psql -U postgres -d ai_storyboard -tAc `
        "select project_id from public.morrow_canvases where project_id is not null limit 1" 2>&1 |
        Select-Object -First 1).Trim()
    if (-not $project) { throw '库里没有可测项目' }
    $list = Convert-FromJson (Invoke-Curl 'GET' "$BaseUrl/api/canvas/projects/$project/canvases" $script:adminToken).Body
    if (-not $list -or -not $list[0].id) { throw '该项目没有画布' }
    $origin = if ($envMap['PUBLIC_ORIGIN']) { $envMap['PUBLIC_ORIGIN'] } else { 'http://localhost' }
    $w = Invoke-Curl 'PATCH' "$BaseUrl/api/canvas/canvases/$($list[0].id)" $script:adminToken `
        (@{ title = $list[0].title } | ConvertTo-Json -Compress) $origin
    if ($w.Status -ne '200') { throw "HTTP $($w.Status)（Origin: $origin）$($w.Body) → 改 .env 的 PUBLIC_ORIGIN" }
    "HTTP 200（Origin: $origin）"
}

Write-Host ''
Write-Host '[4] 6 个服务端口（同一个容器内）'
$openPorts = @()
foreach ($port in @(8080, 8082, 8083, 8084, 8085, 9090)) {
    $out = & docker exec "$prefix-allinone" bash -c "exec 3<>/dev/tcp/127.0.0.1/$port && echo open" 2>&1
    if (($out -join '') -match 'open') { $openPorts += $port }
}
Check '8080/8082/8083/8084/8085/9090 全部在监听' {
    $missing = @(8080, 8082, 8083, 8084, 8085, 9090) | Where-Object { $openPorts -notcontains $_ }
    if ($missing.Count -gt 0) { throw "未监听: $($missing -join ', ')" }
    "$($openPorts.Count) 个端口全部 open"
}

Write-Host ''
Write-Host '[5] 模型网关（LLM Gateway，发布在宿主回环）'
Check "GET 127.0.0.1:$gatewayPort/v1/models" {
    $r = Invoke-Curl 'GET' "http://127.0.0.1:$gatewayPort/v1/models" $envMap['LLM_GATEWAY_API_KEY_MAIN']
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    $body = Convert-FromJson $r.Body
    "HTTP 200, 模型数=$(@($body.data).Count)"
}
if (-not $SkipModelCall) {
    Check '真实模型调用（网关 → 上游厂商）' {
        # 容器里没有 PowerShell 的引号问题：请求体经 base64 传进去再用 curl -d @file
        $payload = '{"model":"mimo-v2.6-pro","messages":[{"role":"user","content":"用一句话确认链路正常"}],"max_tokens":64}'
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
        $script = "echo $b64 | base64 -d > /tmp/req.json; " +
            "curl -s -o /tmp/resp.json -w '%{http_code}' --max-time 150 " +
            "-X POST http://127.0.0.1:8083/v1/chat/completions " +
            "-H 'Authorization: Bearer $($envMap['LLM_GATEWAY_API_KEY_AGENT'])' " +
            "-H 'Content-Type: application/json' -d @/tmp/req.json"
        $code = (& docker exec "$prefix-allinone" sh -c $script 2>&1 | Select-Object -Last 1).Trim()
        $body = (& docker exec "$prefix-allinone" head -c 220 /tmp/resp.json 2>&1) -join ''
        if ($code -ne '200') { throw "HTTP $code — $body" }
        $body
    }
}

Write-Host ''
Write-Host '[6] 资源占用（2 核 2G 预算）'
& docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' |
    Where-Object { $_ -like "$prefix-*" }
$mem = & docker exec "$prefix-allinone" grep -E '^Vm' /proc/1/status 2>&1
Write-Host ("  allinone JVM: " + (($mem | Where-Object { $_ -match 'VmRSS|VmHWM|VmSwap' }) -join ' | '))

Remove-Item $script:tempBody -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host "结果: $script:pass 项通过, $script:fail 项失败"
if ($script:fail -gt 0) { exit 1 }
