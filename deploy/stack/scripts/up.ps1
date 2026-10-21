# 一键启动统一栈（本机/目标机通用）：启动全部服务 → 等待健康
#
#   powershell -File scripts\up.ps1                    # 用 .env，镜像已存在
#   powershell -File scripts\up.ps1 -Build             # 先构建应用镜像
#   powershell -File scripts\up.ps1 -EnvFile .env.verify
#
# 注意：不再默认执行 prepare.ps1。旧版它会停掉 deploy/docker/{minio,redis,sandbox}
# 等独立 compose 项目；在本机开发环境里那会把正在用的 MinIO/Redis/沙箱一起停掉。
# 需要清理旧项目时显式加 -PrepareLegacy。
param(
    [string]$EnvFile = '.env',
    [switch]$Build,
    [switch]$PrepareLegacy,
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envPath  = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $StackDir $EnvFile }
if (-not (Test-Path $envPath)) { throw "找不到 env 文件: $envPath" }

$stackEnv = @{}
Get-Content $envPath | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $stackEnv[$kv[0]] = $kv[1].Trim()
}
# PUBLIC_ORIGIN 留空或写 auto 时，自动取本机默认出口网卡的内网 IP。
# 不能把某一台机器的 IP 写进交付包：别的机器网段不同，写死会让画布写操作 403。
$publicOrigin = $stackEnv['PUBLIC_ORIGIN']
if (-not $publicOrigin -or $publicOrigin -eq 'auto') {
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
    if ($ip) { $publicOrigin = "http://$ip" } else { $publicOrigin = 'http://localhost' }
    $stackEnv['PUBLIC_ORIGIN'] = $publicOrigin
    Write-Host "[up] PUBLIC_ORIGIN 未显式指定，自动使用 $publicOrigin（公网/域名请写入 .env）"
}

# 宿主机 shell 里遗留的 REDIS_PORT / POSTGRES_PORT / MINIO_* 等变量优先级高于 --env-file，
# 会把端口/密钥悄悄改成别的值（曾导致 127.0.0.1:6379 端口冲突）。显式用 env 文件覆盖进程环境。
foreach ($k in $stackEnv.Keys) { Set-Item -Path "Env:$k" -Value $stackEnv[$k] }
$prefix = $stackEnv['STACK_PREFIX']; if (-not $prefix) { $prefix = 'aistoryboard' }

if ($PrepareLegacy) {
    & (Join-Path $PSScriptRoot 'prepare.ps1') -EnvFile $envPath
}

# Windows 检出的 db/*.sh 是 CRLF 时，postgres 的 initdb.d 会报 "/bin/bash^M: bad interpreter"
# 并静默跳过建库/还原，表现为 postgres 一直 unhealthy。起栈前统一成 LF（幂等）。
foreach ($f in @(Get-ChildItem (Join-Path $StackDir 'db') -File -Filter '*.sh') +
                @(Get-ChildItem (Join-Path $StackDir 'db\sql') -File -Filter '*.sql') +
                @(Get-ChildItem (Join-Path $StackDir 'scripts') -File -Filter '*.sh')) {
    if (-not (Test-Path $f.FullName)) { continue }
    $text = [System.IO.File]::ReadAllText($f.FullName)
    $lf = $text -replace "`r`n", "`n" -replace "`r", "`n"
    if ($lf -ne $text) {
        [System.IO.File]::WriteAllText($f.FullName, $lf, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "[up] 已去掉 CRLF: $($f.Name)"
    }
}

# 沙箱工作区用 Docker 命名卷（bind mount 在 Windows 上物化 12k 个技能文件会慢到卡住 Agent）。
# 首次起栈时把 ./data/sandbox-workspaces 作为种子播进卷；之后运行时数据只写卷，不覆盖已有内容。
$sandboxVol  = "$prefix-sandbox-workspaces"
$sandboxSeed = Join-Path $StackDir 'data\sandbox-workspaces'
$seedScript  = Join-Path $PSScriptRoot 'seed-sandbox.sh'
if ((Test-Path $sandboxSeed) -and (Test-Path $seedScript)) {
    docker volume create $sandboxVol *> $null
    Write-Host "[up] 校验沙箱卷 $sandboxVol（空则从 data/sandbox-workspaces 播种）"
    docker run --rm --entrypoint sh `
        -v "${sandboxVol}:/w" `
        -v "${sandboxSeed}:/seed:ro" `
        -v "${seedScript}:/seed.sh:ro" `
        alpine /seed.sh
}

Push-Location $StackDir
try {
    $upArgs = @('compose', '--env-file', $envPath, '-f', $compose, 'up', '-d')
    if ($Build) { $upArgs += '--build' }
    Write-Host "[up] docker $($upArgs -join ' ')"
    & docker @upArgs
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败' }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        $rows = docker compose --env-file $envPath -f $compose ps --format '{{.Service}}|{{.State}}|{{.Health}}'
        $bad = @()
        foreach ($r in $rows) {
            $parts = $r -split '\|'
            $state = $parts[1]; $health = $parts[2]
            if ($state -ne 'running') { $bad += "$($parts[0]):$state" }
            elseif ($health -and $health -ne 'healthy') { $bad += "$($parts[0]):$health" }
        }
        if ($bad.Count -eq 0 -and $rows.Count -gt 0) {
            Write-Host "[up] 全部 $($rows.Count) 个服务 running/healthy"
            $ready = $true
            break
        }
        Write-Host ("[up] 等待中… 未就绪: " + ($bad -join ', '))
        Start-Sleep -Seconds 10
    }
    if (-not $ready) { Write-Host '[up][警告] 等待超时，服务可能仍在启动；用 docker compose logs 查看' }

    docker compose --env-file $envPath -f $compose ps
} finally { Pop-Location }

$port = $stackEnv['FRONTEND_PORT']; if (-not $port) { $port = '80' }
$originOut = $stackEnv['PUBLIC_ORIGIN']
if (-not $originOut -or $originOut -eq 'auto') {
    $hostPart = if ($port -eq '80') { '' } else { ":$port" }
    $originOut = "http://localhost$hostPart"
}
Write-Host ''
Write-Host "[up] 访问入口: $($originOut.TrimEnd('/'))/"
Write-Host '[up] 初始账号: admin@qq.com / 123456'
