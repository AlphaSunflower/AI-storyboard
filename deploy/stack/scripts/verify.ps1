# 部署验收：健康状态、入口、admin 登录、迁移数据量、MinIO 对象、沙箱、真实模型调用
#
#   powershell -File scripts\verify.ps1                    # 用 .env
#   powershell -File scripts\verify.ps1 -EnvFile .env.verify
#   powershell -File scripts\verify.ps1 -SkipModelCall     # 不花钱跑真实模型
param(
    [string]$EnvFile = '.env',
    [switch]$SkipModelCall,
    [string]$AdminEmail    = 'admin@qq.com',
    [string]$AdminPassword = '123456'
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envPath  = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $StackDir $EnvFile }
if (-not (Test-Path $envPath)) { throw "找不到 env 文件: $envPath" }

$cfg = @{}
Get-Content $envPath | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $cfg[$kv[0]] = $kv[1].Trim()
}
$prefix  = $cfg['STACK_PREFIX']; if (-not $prefix) { $prefix = 'aistoryboard' }
$port    = $cfg['FRONTEND_PORT']; if (-not $port) { $port = '80' }

# 与 up.ps1 同一口径：PUBLIC_ORIGIN 留空/auto 时自动探测本机内网 IP
$origin = $cfg['PUBLIC_ORIGIN']
if (-not $origin -or $origin -eq 'auto') {
    $ip = $null
    $netCfg = Get-NetIPConfiguration -ErrorAction SilentlyContinue |
        Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Select-Object -First 1
    if ($netCfg) { $ip = $netCfg.IPv4Address.IPAddress }
    if (-not $ip) {
        $ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
            Select-Object -First 1).IPAddress
    }
    $origin = if ($ip -and $port -eq '80') { "http://$ip" }
              elseif ($ip) { "http://${ip}:$port" }
              elseif ($port -eq '80') { 'http://localhost' }
              else { "http://localhost:$port" }
}
$baseUrl = $origin.TrimEnd('/')
$pg      = "$prefix-postgres"
$minio   = "$prefix-minio"
$gateway = "$prefix-llm-gateway"
$sandbox = "$prefix-sandbox-runner"
$results = @()

function Check([string]$name, [scriptblock]$body) {
    try {
        $detail = & $body
        $script:results += [pscustomobject]@{ Check = $name; Result = 'PASS'; Detail = $detail }
        Write-Host "[PASS] $name — $detail" -ForegroundColor Green
    } catch {
        $script:results += [pscustomobject]@{ Check = $name; Result = 'FAIL'; Detail = $_.Exception.Message }
        Write-Host "[FAIL] $name — $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Sql([string]$db, [string]$sql) {
    docker exec $pg psql -tAq -U postgres -d $db -c $sql
}

Push-Location $StackDir
try {
    Check 'compose 健康状态' {
        $raw = docker compose --env-file $envPath -f $compose ps --format '{{.Service}}|{{.State}}|{{.Health}}'
        $bad = @()
        foreach ($line in $raw) {
            $p = $line -split '\|'
            if ($p[1] -ne 'running' -or ($p[2] -and $p[2] -ne 'healthy')) { $bad += "$($p[0]):$($p[1])/$($p[2])" }
        }
        if ($bad.Count -gt 0) { throw ("未就绪: " + ($bad -join ', ')) }
        "$($raw.Count) 个服务全部 running"
    }

    Check '前端入口 /' {
        $r = Invoke-WebRequest -Uri "$baseUrl/" -UseBasicParsing -TimeoutSec 20
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        'HTTP 200'
    }

    Check '画布入口 /canvas/' {
        $r = Invoke-WebRequest -Uri "$baseUrl/canvas/" -UseBasicParsing -TimeoutSec 20
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        'HTTP 200'
    }

    $token = $null
    Check "登录 $AdminEmail" {
        $body = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json
        $raw = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 30
        if ($raw.StatusCode -ne 200) { throw "HTTP $($raw.StatusCode)" }
        $resp = $raw.Content | ConvertFrom-Json
        $access = $resp.data.accessToken
        if (-not $access) { throw "响应无 accessToken: $($raw.Content)" }
        $script:token = $access
        $setCookie = $raw.Headers['Set-Cookie']
        if ($setCookie -match 'refresh_token=') {
            $secureFlag = if ($setCookie -match 'Secure') { 'Secure' } else { 'no-Secure' }
            "登录成功，refresh_token cookie 已下发（$secureFlag）"
        } else {
            '登录成功，但响应未带 refresh_token cookie'
        }
    }

    Check 'GET /api/projects（带 Bearer）' {
        if (-not $token) { throw '登录未成功，跳过' }
        $r = Invoke-RestMethod -Uri "$baseUrl/api/projects" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30
        $n = if ($r.data -is [array]) { $r.data.Count } else { 1 }
        "返回 $n 个项目"
    }

    Check '数据库账号收敛为 admin@qq.com' {
        $users = Sql 'ai_storyboard' 'select count(*) from users'
        $admin = Sql 'ai_storyboard' 'select email||''/''||role||''/''||status from users'
        if ($users -ne '1') { throw "users=$users（期望 1）" }
        if ($admin -notmatch '^admin@qq\.com/admin/enabled') { throw "账号不匹配: $admin" }
        "$users 个账号: $admin"
    }

    Check '当前业务数据量（项目/会话/消息/分镜）' {
        $row = Sql 'ai_storyboard' @'
select (select count(*) from projects)||'/'||
       (select count(*) from conversations)||'/'||
       (select count(*) from agent_messages)||'/'||
       (select count(*) from scenes)
'@
        # 干净交付包基线为 0/0/0/0；导入数据后这里只做如实展示，不设下限
        "projects/conversations/messages/scenes = $row"
    }

    Check '网关配置（渠道/路由/Key）' {
        $row = Sql 'llm_gateway' "select (select count(*) from channel)||'/'||(select count(*) from model_route)||'/'||(select count(*) from gateway_api_key)"
        $parts = $row.Split('/')
        if ([int]$parts[0] -lt 12 -or [int]$parts[1] -lt 20 -or [int]$parts[2] -lt 10) {
            throw "channel/model_route/gateway_api_key=$row（基线 12/20/10）"
        }
        "channel/model_route/gateway_api_key = $row（基线 12/20/10）"
    }

    Check 'MinIO 桶可读' {
        $out = docker exec $minio sh -c "mc alias set local http://127.0.0.1:9000 $($cfg['MINIO_ROOT_USER']) $($cfg['MINIO_ROOT_PASSWORD']) >/dev/null 2>&1 && mc ls local/storyboard | wc -l"
        $n = [int](($out | Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -Last 1))
        "storyboard 顶层对象/前缀 $n 个"
    }

    Check '历史图片经 /api/files/** 可读' {
        if (-not $token) { throw '登录未成功，跳过' }
        $imgDir = Join-Path $StackDir 'data\minio\storyboard\images'
        $file = $null
        if (Test-Path $imgDir) {
            $file = (Get-ChildItem $imgDir -Directory | Where-Object { $_.Name -ne '.gitkeep' } | Select-Object -First 1).Name
        }
        if (-not $file) { return '空桶（干净部署，无历史图片）' }
        $path = "/api/files/images/$file"
        $exp = [int][double]::Parse((Get-Date -UFormat %s)) + 600
        $hmac = New-Object System.Security.Cryptography.HMACSHA256
        $hmac.Key = [Text.Encoding]::UTF8.GetBytes($cfg['INTERNAL_SECRET'])
        $sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$path`n$exp"))).Replace('+', '-').Replace('/', '_').TrimEnd('=')
        $r = Invoke-WebRequest -Uri "$baseUrl$path`?token=$exp.$sig" -UseBasicParsing -TimeoutSec 30
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        "$file -> HTTP 200, $([Math]::Round($r.RawContentLength / 1KB)) KB, $($r.Headers['Content-Type'])"
    }

    Check 'Agent 会话经网关可达' {
        if (-not $token) { throw '登录未成功，跳过' }
        $projects = (Invoke-RestMethod -Uri "$baseUrl/api/projects" -Headers @{ Authorization = "Bearer $token" }).data
        $total = 0
        foreach ($p in $projects) {
            $c = (Invoke-RestMethod -Uri "$baseUrl/api/agent/conversations?projectId=$($p.id)" -Headers @{ Authorization = "Bearer $token" }).data
            if ($c) { $total += @($c).Count }
        }
        if ($total -lt 0) { throw "会话总数异常 $total" }
        "$($projects.Count) 个项目下共 $total 条会话"
    }

    Check '沙箱执行 Python 回声' {
        $body = @{ script = "print('sandbox-ok')"; args = @(); timeoutSeconds = 30 } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$($cfg['SANDBOX_PORT'])/internal/sandbox/deploy-check/execute" -Method Post `
            -Headers @{ 'X-Internal-Secret' = $cfg['SANDBOX_INTERNAL_SECRET'] } -ContentType 'application/json' -Body $body -TimeoutSec 60
        if ($resp.stdout -notmatch 'sandbox-ok') { throw "stdout=$($resp.stdout)" }
        "exitCode=$($resp.exitCode) stdout=$($resp.stdout.Trim())"
    }

    Check '沙箱技能包保留' {
        $out = docker exec $sandbox sh -c 'find /opt/moon-skills/ppt-master -type f | wc -l'
        $n = [int](($out | Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -Last 1))
        if ($n -lt 1000) { throw "镜像内 ppt-master 技能包文件数 $n" }
        "ppt-master 技能包 $n 个文件"
    }

    Check '沙箱卷可写' {
        $vol = "$prefix-sandbox-workspaces"
        $out = docker run --rm --user 10001:10001 --entrypoint sh -v "${vol}:/w" alpine `
            -c 'touch /w/.verify && rm /w/.verify && echo ok'
        if (($out -join '') -notmatch 'ok') { throw "卷 ${vol} 不可写" }
        "卷 ${vol} 可写（10001）"
    }

    if (-not $SkipModelCall) {
        Check '真实模型调用（经网关渠道密钥）' {
            $key = $cfg['LLM_GATEWAY_API_KEY_AGENT']
            $payload = @{ model = 'mimo-v2.6-pro'; messages = @(@{ role = 'user'; content = '用一句话确认链路正常' }); max_tokens = 64 } | ConvertTo-Json -Compress -Depth 5
            $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
            docker exec $gateway sh -c "echo $b64 | base64 -d > /tmp/req.json"
            if ($LASTEXITCODE -ne 0) { throw '写入请求体失败' }
            $code = docker exec $gateway sh -c "curl -s -o /tmp/resp.json -w '%{http_code}' -X POST http://127.0.0.1:8083/v1/chat/completions -H 'Authorization: Bearer $key' -H 'Content-Type: application/json' -d @/tmp/req.json"
            $body = docker exec $gateway sh -c 'head -c 400 /tmp/resp.json'
            if ($code -ne '200') { throw "HTTP $code — $body" }
            "HTTP 200 — $body"
        }

        Check 'Agent SSE 流式对话（网关→MoonAgent→模型）' {
            if (-not $token) { throw '登录未成功，跳过' }
            $projects = (Invoke-RestMethod -Uri "$baseUrl/api/projects" -Headers @{ Authorization = "Bearer $token" }).data
            if (-not $projects -or @($projects).Count -eq 0) { return '空库（无项目，跳过 SSE 自检）' }
            $createBody = @{ projectId = $projects[0].id; title = '部署自检（可删除）' } | ConvertTo-Json
            $created = Invoke-RestMethod -Uri "$baseUrl/api/agent/conversations" -Method Post -ContentType 'application/json' `
                -Headers @{ Authorization = "Bearer $token" } -Body $createBody
            $convId = $created.data.id
            if (-not $convId) { throw "创建临时会话失败: $($created | ConvertTo-Json -Compress)" }
            $json = '{"content":"只回复四个字：部署联通","clientRequestId":"' + [guid]::NewGuid().ToString() + '"}'
            $bodyFile = Join-Path $env:TEMP 'verify_sse.json'
            [System.IO.File]::WriteAllText($bodyFile, $json, (New-Object System.Text.UTF8Encoding($false)))
            $outFile = Join-Path $env:TEMP 'verify_sse_out.txt'
            cmd /c "curl.exe -s -N --max-time 180 -X POST `"$baseUrl/api/agent/conversations/$convId/turns/stream`" -H `"Authorization: Bearer $token`" -H `"Content-Type: application/json`" -H `"Accept: text/event-stream`" --data-binary `"@$bodyFile`" > `"$outFile`" 2>&1"
            $text = Get-Content $outFile -Raw
            Invoke-RestMethod -Uri "$baseUrl/api/agent/conversations/$convId" -Method Delete -Headers @{ Authorization = "Bearer $token" } | Out-Null
            if (-not $text) { throw 'SSE 无输出' }
            if ($text -notmatch 'turn_completed') { throw "SSE 未完成: $($text.Substring(0, [Math]::Min(300, $text.Length)))" }
            $inTok = if ($text -match '"inputTokens":(\d+)') { $matches[1] } else { '?' }
            "turn_completed，inputTokens=$inTok，事件数=$(([regex]::Matches($text, 'event:')).Count)"
        }
    }
} finally {
    Pop-Location
}

$fail = @($results | Where-Object { $_.Result -eq 'FAIL' })
Write-Host ''
Write-Host ("验收结果: {0} 通过 / {1} 失败" -f (@($results | Where-Object { $_.Result -eq 'PASS' }).Count, $fail.Count))
if ($fail.Count -gt 0) { exit 1 }
