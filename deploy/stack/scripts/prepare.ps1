# 旧部署接管（可选）：停掉 deploy/docker/{nacos,vosk,redis,minio,sandbox} 独立 compose 项目，
# 清理与统一栈同名的残留容器/网络。只在确认旧项目不再需要时执行：
#
#   powershell -File scripts\prepare.ps1                 # 使用 .env
#   powershell -File scripts\prepare.ps1 -EnvFile .env.verify
#
# up.ps1 默认不再调用本脚本：本机开发环境里 MinIO/Redis/沙箱可能正在使用，
# 误停会把开发栈一起打断。
param(
    [string]$EnvFile = '.env',
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Continue'
$PSNativeCommandUseErrorActionPreference = $false
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envPath  = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $StackDir $EnvFile }

$stackEnv = @{}
Get-Content $envPath | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $stackEnv[$kv[0]] = $kv[1].Trim()
}
$prefix = $stackEnv['STACK_PREFIX']; if (-not $prefix) { $prefix = 'aistoryboard' }
$project = $stackEnv['COMPOSE_PROJECT_NAME']; if (-not $project) { $project = 'aistoryboard' }

# 旧 compose 需要的最小变量
$env:MINIO_ROOT_PASSWORD = $stackEnv['MINIO_ROOT_PASSWORD']
$env:SANDBOX_INTERNAL_SECRET = $stackEnv['SANDBOX_INTERNAL_SECRET']
$env:SANDBOX_ALLOWED_DOMAINS = $stackEnv['SANDBOX_ALLOWED_DOMAINS']

Write-Host "[prepare] 仓库根目录: $RepoRoot"
Write-Host "[prepare] 目标前缀: $prefix / 项目: $project"

# 1) 停掉旧的独立 compose 项目（用各自的 .env，避免覆盖 COMPOSE_PROJECT_NAME）
foreach ($svc in @('nacos', 'vosk', 'redis', 'minio', 'sandbox')) {
    $dir = Join-Path $RepoRoot "deploy\docker\$svc"
    $compose = Join-Path $dir 'docker-compose.yml'
    if (-not (Test-Path $compose)) { continue }
    $ownEnv = Join-Path $dir '.env'
    $composeArgs = @('compose', '--project-directory', $dir)
    if (Test-Path $ownEnv) { $composeArgs += @('--env-file', $ownEnv) }
    $composeArgs += @('-f', $compose, 'down')
    Write-Host "[prepare] docker $($composeArgs -join ' ')"
    docker @composeArgs 2>&1 | ForEach-Object { "    $_" }
}

# 2) 兜底：清掉与统一栈同名、但属于其它项目的残留容器
$ours = @(
    "$prefix-postgres", "$prefix-redis", "$prefix-minio",
    "$prefix-llm-gateway", "$prefix-backend", "$prefix-asset-service", "$prefix-canvas",
    "$prefix-moon-agent", "$prefix-api-gateway", "$prefix-frontend",
    "$prefix-sandbox-runner", "$prefix-sandbox-egress-proxy"
)
$existing = docker ps -a --format '{{.Names}}'
foreach ($name in $ours) {
    if ($existing -notcontains $name) { continue }
    $labelsJson = docker inspect $name --format '{{json .Config.Labels}}' 2>$null | Out-String
    $owner = $null
    if ($labelsJson -and $labelsJson.Trim() -ne 'null') {
        try { $owner = ($labelsJson | ConvertFrom-Json).'com.docker.compose.project' } catch { $owner = $null }
    }
    if ($owner -eq $project) {
        Write-Host "[prepare] 容器 $name 已由本项目管理，保留"
        continue
    }
    Write-Host "[prepare] 移除旧项目残留容器 $name（原属: $owner）"
    docker rm -f $name | Out-Null
}

# 3) 清理空闲的同名网络
foreach ($net in @($prefix, "$prefix-sandbox-internal", "$prefix-sandbox-egress", "$prefix-sandbox-edge")) {
    $nets = docker network ls --format '{{.Name}}'
    if ($nets -notcontains $net) { continue }
    $inUse = docker network inspect $net --format '{{len .Containers}}' 2>$null
    if ($inUse -eq '0') {
        docker network rm $net | Out-Null
        Write-Host "[prepare] 移除空闲网络 $net"
    } else {
        Write-Host "[prepare][警告] 网络 $net 仍被容器占用，稍后可能冲突"
    }
}

Write-Host '[prepare] 完成'
