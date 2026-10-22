# 端到端验收（Windows 侧）：入口、注册关闭、admin 登录、全量数据行数、MinIO 对象、
# 6 个服务端口、模型网关（含一次真实模型调用）、Agent 沙箱、内存占用。
#
#   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -EnvFile .env.test -BaseUrl http://127.0.0.1:18080
#   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -SkipModelCall
param(
    [string]$BaseUrl,
    [string]$EnvFile = '.env',
    [switch]$SkipModelCall
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_env.ps1')

$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envMap = Get-StackEnv -StackDir $StackDir -EnvFile $EnvFile
$prefix = if ($envMap['STACK_PREFIX']) { $envMap['STACK_PREFIX'] } else { 'ai-cloud' }
if (-not $BaseUrl) { $BaseUrl = "http://127.0.0.1:$($envMap['HTTP_PORT'])" }
$BaseUrl = $BaseUrl.TrimEnd('/')
$gatewayPort = if ($envMap['GATEWAY_PORT']) { $envMap['GATEWAY_PORT'] } else { '8083' }
$adminEmail = 'admin@qq.com'
$dataDir = if ($envMap['MINIO_DATA_DIR']) { $envMap['MINIO_DATA_DIR'] } else { './data/minio' }
$dataDirFull = [System.IO.Path]::GetFullPath((Join-Path $StackDir $dataDir))

# 全量数据清单
$manifest = @{}
$manifestPath = Join-Path $StackDir 'db\data-manifest.env'
if (Test-Path $manifestPath) {
    Get-Content $manifestPath | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
        $kv = $_ -split '=', 2
        $manifest[$kv[0]] = $kv[1]
    }
}
function ManifestGet([string]$key, [string]$fallback) {
    if ($manifest.ContainsKey($key) -and $manifest[$key]) { return $manifest[$key] }
    return $fallback
}

$script:tempBody = Join-Path ([System.IO.Path]::GetTempPath()) "verify-body-$PID.json"
$script:pass = 0
$script:fail = 0

function Check([string]$name, [scriptblock]$body) {
    try {
        $detail = & $body
        Write-Host ("  [OK]   {0,-44} {1}" -f $name, $detail)
        $script:pass++
    } catch {
        Write-Host ("  [FAIL] {0,-44} {1}" -f $name, $_.Exception.Message)
        $script:fail++
    }
}

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

function DbCount([string]$sql) {
    $out = & docker exec "$prefix-postgres" psql -U postgres -d ai_storyboard -tAc $sql 2>&1
    return ($out | Select-Object -First 1).Trim()
}

Write-Host "验收目标: $BaseUrl（env=$EnvFile，容器前缀=$prefix，数据目录=$dataDirFull）"
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
Write-Host '[3] 管理员与全量数据'
Check "$adminEmail 登录" {
    $r = Invoke-Curl 'POST' "$BaseUrl/api/auth/login" $null (@{ email = $adminEmail; password = '123456' } | ConvertTo-Json -Compress)
    $body = Convert-FromJson $r.Body
    if (-not $body.data.accessToken) { throw "HTTP $($r.Status) $($r.Body)" }
    $script:adminToken = $body.data.accessToken
    "displayName=$($body.data.displayName)"
}
Check 'GET /api/projects（admin token）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $r = Invoke-Curl 'GET' "$BaseUrl/api/projects" $script:adminToken
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    $n = DbCount "select count(*) from projects p join users u on u.id=p.user_id where u.email='admin@qq.com'"
    if ([int]$n -le 0) { throw 'admin 名下项目数为 0' }
    "HTTP 200, admin 项目数=$n"
}
Check '全量行数与 data-manifest.env 一致' {
    $pairs = @(
        @('users', 'select count(*) from public.users', (ManifestGet 'USERS' '')),
        @('projects', 'select count(*) from public.projects', (ManifestGet 'PROJECTS' '')),
        @('conversations', 'select count(*) from public.conversations', (ManifestGet 'CONVERSATIONS' '')),
        @('agent_messages', 'select count(*) from public.agent_messages', (ManifestGet 'AGENT_MESSAGES' '')),
        @('agent_assets', 'select count(*) from public.agent_assets', (ManifestGet 'AGENT_ASSETS' ''))
    )
    $bad = @()
    foreach ($p in $pairs) {
        $actual = DbCount $p[1]
        if ($p[2] -and $actual -ne $p[2]) { $bad += "$($p[0])=$actual/$($p[2])" }
    }
    if ($bad.Count -gt 0) { throw ("与清单不一致: " + ($bad -join ', ')) }
    "users=$(ManifestGet 'USERS' '?') projects=$(ManifestGet 'PROJECTS' '?') conversations=$(ManifestGet 'CONVERSATIONS' '?') assets=$(ManifestGet 'AGENT_ASSETS' '?')"
}
Check '账号未被收敛（>=100）' {
    $n = DbCount 'select count(*) from public.users'
    if ([int]$n -lt 100) { throw "只有 $n 个账号（全量包应 >=100）" }
    "$n 个账号（未做收敛）"
}
Check 'MinIO 全量对象在位' {
    if (-not (Test-Path (Join-Path $dataDirFull 'storyboard'))) { throw "缺少 $dataDirFull\storyboard 桶" }
    $n = (Get-ChildItem $dataDirFull -Recurse -File -Force | Measure-Object).Count
    if ($n -lt 1500) { throw "只有 $n 个对象文件（期望 >=1500）" }
    "$n 个对象文件（清单 $(ManifestGet 'MINIO_FILES' '?'))"
}

Write-Host ''
Write-Host '[4] 各服务路由（6 个服务在同一个 JVM 里）'
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
Check '画布写操作 PATCH（带 Origin）' {
    if (-not $script:adminToken) { throw 'admin 未登录' }
    $project = DbCount "select c.project_id from public.morrow_canvases c join public.projects p on p.id=c.project_id join public.users u on u.id=p.user_id where u.email='admin@qq.com' and c.project_id is not null limit 1"
    if (-not $project) { throw 'admin 名下没有可测画布' }
    $list = Convert-FromJson (Invoke-Curl 'GET' "$BaseUrl/api/canvas/projects/$project/canvases" $script:adminToken).Body
    if (-not $list -or -not $list[0].id) { throw '该项目没有画布' }
    $origin = if ($envMap['PUBLIC_ORIGIN']) { $envMap['PUBLIC_ORIGIN'] } else { 'http://localhost' }
    $w = Invoke-Curl 'PATCH' "$BaseUrl/api/canvas/canvases/$($list[0].id)" $script:adminToken `
        (@{ title = $list[0].title } | ConvertTo-Json -Compress) $origin
    if ($w.Status -ne '200') { throw "HTTP $($w.Status)（Origin: $origin）$($w.Body) → 改 .env 的 PUBLIC_ORIGIN" }
    "HTTP 200（Origin: $origin）"
}

Write-Host ''
Write-Host '[5] 6 个服务端口（同一个容器内）'
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
Write-Host '[6] 模型网关（LLM Gateway，发布在宿主回环）'
Check "GET 127.0.0.1:$gatewayPort/v1/models" {
    $r = Invoke-Curl 'GET' "http://127.0.0.1:$gatewayPort/v1/models" $envMap['LLM_GATEWAY_API_KEY_MAIN']
    if ($r.Status -ne '200') { throw "HTTP $($r.Status) $($r.Body)" }
    $body = Convert-FromJson $r.Body
    "HTTP 200, 模型数=$(@($body.data).Count)"
}
if (-not $SkipModelCall) {
    Check '真实模型调用（网关 → 上游厂商）' {
        $payload = '{"model":"mimo-v2.6-pro","messages":[{"role":"user","content":"用一句话确认链路正常"}],"max_tokens":64}'
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
        $cmd = "echo $b64 | base64 -d > /tmp/req.json; " +
            "curl -s -o /tmp/resp.json -w '%{http_code}' --max-time 150 " +
            "-X POST http://127.0.0.1:8083/v1/chat/completions " +
            "-H 'Authorization: Bearer $($envMap['LLM_GATEWAY_API_KEY_AGENT'])' " +
            "-H 'Content-Type: application/json' -d @/tmp/req.json"
        $code = (& docker exec "$prefix-allinone" sh -c $cmd 2>&1 | Select-Object -Last 1).Trim()
        $body = (& docker exec "$prefix-allinone" head -c 220 /tmp/resp.json 2>&1) -join ''
        if ($code -ne '200') { throw "HTTP $code — $body" }
        $body
    }
}

Write-Host ''
Write-Host '[7] Agent 沙箱（独立容器，默认开启）'
Check '沙箱健康 + 写文件 + Python' {
    $enabled = if ($envMap['AGENT_SANDBOX_ENABLED']) { $envMap['AGENT_SANDBOX_ENABLED'] } else { 'true' }
    if ($enabled -ne 'true') { return "未启用（AGENT_SANDBOX_ENABLED=$enabled），跳过" }
    $secret = $envMap['SANDBOX_INTERNAL_SECRET']
    $h = & docker exec "$prefix-allinone" curl -s --max-time 15 http://sandbox-runner:8090/actuator/health 2>&1
    if ($h -notmatch '"status":"UP"') { throw "健康检查失败: $h" }
    $json = '{"script":"print(6*7)","timeoutSeconds":60}'
    $json | docker exec -i "$prefix-allinone" sh -c 'cat > /tmp/ex.json'
    $cmd = "curl -s --max-time 90 -X POST http://sandbox-runner:8090/internal/sandbox/smoke/execute " +
        "-H 'X-Internal-Secret: $secret' -H 'Content-Type: application/json' -d @/tmp/ex.json"
    $out = (& docker exec "$prefix-allinone" sh -c $cmd 2>&1) -join ''
    if ($out -notmatch '"exitCode":0') { throw "执行脚本失败: $out" }
    '健康 + Python 执行 OK'
}

Write-Host ''
Write-Host '[8] 资源占用（2 核 2G 预算）'
& docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' |
    Where-Object { $_ -like "$prefix-*" }
$mem = & docker exec "$prefix-allinone" grep -E '^Vm' /proc/1/status 2>&1
Write-Host ("  allinone JVM: " + (($mem | Where-Object { $_ -match 'VmRSS|VmHWM|VmSwap' }) -join ' | '))

Remove-Item $script:tempBody -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host "结果: $script:pass 项通过, $script:fail 项失败"
if ($script:fail -gt 0) { exit 1 }
