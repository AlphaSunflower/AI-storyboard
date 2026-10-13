# 部署验收：健康状态、登录、页面、数据量、MinIO、沙箱、真实模型调用
param(
    [switch]$SkipModelCall,
    [string]$AdminEmail    = 'admin@qq.com',
    [string]$AdminPassword = '123456'
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envFile  = Join-Path $StackDir '.env'
$results  = @()

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
    docker exec aistoryboard-postgres psql -tAq -U postgres -d $db -c $sql
}

Push-Location $StackDir
try {
    Check 'compose 健康状态' {
        $raw = docker compose --env-file $envFile -f $compose ps --format '{{.Service}}|{{.State}}|{{.Health}}'
        $bad = @()
        foreach ($line in $raw) {
            $p = $line -split '\|'
            if ($p[1] -ne 'running' -or ($p[2] -and $p[2] -ne 'healthy')) { $bad += "$($p[0]):$($p[1])/$($p[2])" }
        }
        if ($bad.Count -gt 0) { throw ("未就绪: " + ($bad -join ', ')) }
        "$($raw.Count) 个服务全部 running"
    }

    Check '前端入口 http://localhost/' {
        $r = Invoke-WebRequest -Uri 'http://localhost/' -UseBasicParsing -TimeoutSec 20
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        'HTTP 200'
    }

    Check '画布入口 http://localhost/canvas/' {
        $r = Invoke-WebRequest -Uri 'http://localhost/canvas/' -UseBasicParsing -TimeoutSec 20
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        'HTTP 200'
    }

    $token = $null
    Check "登录 $AdminEmail" {
        $body = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json
        $raw = Invoke-WebRequest -Uri 'http://localhost/api/auth/login' -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 30
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
        $r = Invoke-RestMethod -Uri 'http://localhost/api/projects' -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30
        $n = if ($r.data -is [array]) { $r.data.Count } else { 1 }
        if ($n -le 0) { throw '项目列表为空' }
        "返回 $n 个项目"
    }

    Check '数据库账号收敛' {
        $users = Sql 'ai_storyboard' 'select count(*) from users'
        $admin = Sql 'ai_storyboard' "select email||'/'||role||'/'||status from users"
        if ($users -ne '1') { throw "users=$users（期望 1）" }
        "$users 个账号: $admin"
    }

    Check '业务数据量（会话/消息/分镜）' {
        $c = Sql 'ai_storyboard' 'select count(*) from conversations'
        $m = Sql 'ai_storyboard' 'select count(*) from agent_messages'
        $s = Sql 'ai_storyboard' 'select count(*) from scenes'
        if ([int]$c -lt 250) { throw "conversations=$c（期望 250）" }
        "conversations=$c agent_messages=$m scenes=$s"
    }

    Check '网关配置（渠道/路由/Key）' {
        # 迁移基线是 12/20/10；model_route 会随画布选模型继续增长，因此只校验不低于基线
        $row = Sql 'llm_gateway' "select (select count(*) from channel)||'/'||(select count(*) from model_route)||'/'||(select count(*) from gateway_api_key)"
        $parts = $row.Split('/')
        if ([int]$parts[0] -lt 12 -or [int]$parts[1] -lt 20 -or [int]$parts[2] -lt 10) {
            throw "channel/model_route/gateway_api_key=$row（基线 12/20/10）"
        }
        "channel/model_route/gateway_api_key = $row（基线 12/20/10）"
    }

    Check 'MinIO 桶与对象可读' {
        $out = docker exec minio-object sh -c "mc alias set local http://127.0.0.1:9000 minioadmin minioadmin123 >/dev/null 2>&1 && mc ls local/storyboard | head -n 5"
        if (-not $out) { throw 'mc ls 无输出' }
        ($out -join '; ')
    }

    Check '历史图片经 /api/files/** 可读' {
        if (-not $token) { throw '登录未成功，跳过' }
        $imgDir = Join-Path $StackDir '..\docker\minio\data\storyboard\images'
        $file = (Get-ChildItem $imgDir -Directory | Where-Object { $_.Name -ne '.gitkeep' } | Select-Object -First 1).Name
        if (-not $file) { throw "MinIO images 目录为空: $imgDir" }
        $path = "/api/files/images/$file"
        $exp = [int][double]::Parse((Get-Date -UFormat %s)) + 600
        $hmac = New-Object System.Security.Cryptography.HMACSHA256
        $hmac.Key = [Text.Encoding]::UTF8.GetBytes(((Get-Content $envFile | Where-Object { $_ -match '^INTERNAL_SECRET=' }) -split '=', 2)[1].Trim())
        $sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$path`n$exp"))).Replace('+', '-').Replace('/', '_').TrimEnd('=')
        $r = Invoke-WebRequest -Uri "http://localhost$path`?token=$exp.$sig" -UseBasicParsing -TimeoutSec 30
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        "$file -> HTTP 200, $([Math]::Round($r.RawContentLength / 1KB)) KB, $($r.Headers['Content-Type'])"
    }

    Check 'Agent 会话经网关可达' {
        if (-not $token) { throw '登录未成功，跳过' }
        $projects = (Invoke-RestMethod -Uri 'http://localhost/api/projects' -Headers @{ Authorization = "Bearer $token" }).data
        $total = 0
        foreach ($p in $projects) {
            $c = (Invoke-RestMethod -Uri "http://localhost/api/agent/conversations?projectId=$($p.id)" -Headers @{ Authorization = "Bearer $token" }).data
            if ($c) { $total += @($c).Count }
        }
        if ($total -lt 250) { throw "会话总数 $total（期望 250）" }
        "$($projects.Count) 个项目下共 $total 条会话"
    }

    Check '沙箱执行 Python 回声' {
        $body = @{ script = "print('sandbox-ok')"; args = @(); timeoutSeconds = 30 } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/internal/sandbox/deploy-check/execute' -Method Post `
            -Headers @{ 'X-Internal-Secret' = 'local-moon-sandbox-secret' } -ContentType 'application/json' -Body $body -TimeoutSec 60
        if ($resp.stdout -notmatch 'sandbox-ok') { throw "stdout=$($resp.stdout)" }
        "exitCode=$($resp.exitCode) stdout=$($resp.stdout.Trim())"
    }

    if (-not $SkipModelCall) {
        Check '真实模型调用（经网关渠道密钥）' {
            $key = ((Get-Content $envFile | Where-Object { $_ -match '^LLM_GATEWAY_API_KEY_AGENT=' }) -split '=', 2)[1].Trim()
            $payload = @{ model = 'mimo-v2.6-pro'; messages = @(@{ role = 'user'; content = '用一句话确认链路正常' }); max_tokens = 64 } | ConvertTo-Json -Compress -Depth 5
            # 用 base64 传输，避免 PowerShell 管道给 JSON 加上 BOM
            $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
            docker exec aistoryboard-llm-gateway sh -c "echo $b64 | base64 -d > /tmp/req.json"
            if ($LASTEXITCODE -ne 0) { throw '写入请求体失败' }
            $code = docker exec aistoryboard-llm-gateway sh -c "curl -s -o /tmp/resp.json -w '%{http_code}' -X POST http://127.0.0.1:8083/v1/chat/completions -H 'Authorization: Bearer $key' -H 'Content-Type: application/json' -d @/tmp/req.json"
            $body = docker exec aistoryboard-llm-gateway sh -c 'head -c 400 /tmp/resp.json'
            if ($code -ne '200') { throw "HTTP $code — $body" }
            "HTTP 200 — $body"
        }

        Check 'Agent SSE 流式对话（网关→MoonAgent→模型）' {
            if (-not $token) { throw '登录未成功，跳过' }
            $projects = (Invoke-RestMethod -Uri 'http://localhost/api/projects' -Headers @{ Authorization = "Bearer $token" }).data
            if (-not $projects) { throw '没有可用项目' }
            # 用临时会话做流式自检，结束后删除，避免污染既有数据
            $createBody = @{ projectId = $projects[0].id; title = '部署自检（可删除）' } | ConvertTo-Json
            $created = Invoke-RestMethod -Uri 'http://localhost/api/agent/conversations' -Method Post -ContentType 'application/json' `
                -Headers @{ Authorization = "Bearer $token" } -Body $createBody
            $convId = $created.data.id
            if (-not $convId) { throw "创建临时会话失败: $($created | ConvertTo-Json -Compress)" }
            $json = '{"content":"只回复四个字：部署联通","clientRequestId":"' + [guid]::NewGuid().ToString() + '"}'
            $bodyFile = Join-Path $env:TEMP 'verify_sse.json'
            [System.IO.File]::WriteAllText($bodyFile, $json, (New-Object System.Text.UTF8Encoding($false)))
            $outFile = Join-Path $env:TEMP 'verify_sse_out.txt'
            cmd /c "curl.exe -s -N --max-time 120 -X POST `"http://localhost/api/agent/conversations/$convId/turns/stream`" -H `"Authorization: Bearer $token`" -H `"Content-Type: application/json`" -H `"Accept: text/event-stream`" --data-binary `"@$bodyFile`" > `"$outFile`" 2>&1"
            $text = Get-Content $outFile -Raw
            Invoke-RestMethod -Uri "http://localhost/api/agent/conversations/$convId" -Method Delete -Headers @{ Authorization = "Bearer $token" } | Out-Null
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
