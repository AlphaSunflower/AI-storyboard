# 统一栈启动前的接管准备：停掉旧 compose 项目 → 清理同名残留容器 → 补齐外部卷 → 清理同名网络
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Continue'
$PSNativeCommandUseErrorActionPreference = $false
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EnvFile  = Join-Path $StackDir '.env'

$stackEnv = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Za-z_]+=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $stackEnv[$kv[0]] = $kv[1].Trim()
}

# 旧 compose 需要的最小变量（保持容器名/端口与原项目一致）
$env:MINIO_ROOT_PASSWORD = $stackEnv['MINIO_ROOT_PASSWORD']
$env:SANDBOX_INTERNAL_SECRET = $stackEnv['SANDBOX_INTERNAL_SECRET']
$env:SANDBOX_ALLOWED_DOMAINS = $stackEnv['SANDBOX_ALLOWED_DOMAINS']

Write-Host "[prepare] 仓库根目录: $RepoRoot"

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

# 2) 兜底：把本栈要接管的容器名清掉（旧项目容器名与端口保持不变）
$projectName = $stackEnv['COMPOSE_PROJECT_NAME']
if (-not $projectName) { $projectName = 'aistoryboard' }
# 本栈已不再声明的历史服务：即使带着本项目标签（旧版 compose 建的）也要清掉，
# 否则会作为「已由本项目管理」被永久保留。
$retiredServices = @('nacos-standalone', 'aistoryboard-canvas-bridge')
foreach ($name in @('nacos-standalone', 'aistoryboard-canvas-bridge', 'redis-ai', 'minio-object', 'moon-sandbox-runner', 'moon-sandbox-egress-proxy')) {
    $exists = docker ps -a --format '{{.Names}}' | Where-Object { $_ -eq $name }
    if (-not $exists) { continue }
    if ($retiredServices -contains $name) {
        Write-Host "[prepare] 移除已下线服务容器 $name（已不在统一栈内）"
        docker rm -f $name | Out-Null
        continue
    }
    # 注意：--format 里带空格的 Go 模板 + 内层双引号在脚本里会被 PowerShell 改写，
    # 所以统一用 {{json ...}} 输出后在 PowerShell 侧解析。
    $labelsJson = docker inspect $name --format '{{json .Config.Labels}}' 2>$null | Out-String
    $owner = $null
    if ($labelsJson -and $labelsJson.Trim() -ne 'null') {
        try { $owner = ($labelsJson | ConvertFrom-Json).'com.docker.compose.project' } catch { $owner = $null }
    }
    if ($owner -eq $projectName) {
        Write-Host "[prepare] 容器 $name 已由本项目管理，保留"
        continue
    }
    Write-Host "[prepare] 移除旧项目残留容器 $name（原属: $owner）"
    docker rm -f $name | Out-Null
}

# 3) 清理同名网络（仅当没有容器占用）
foreach ($net in @('aistoryboard', 'moon-sandbox-internal', 'moon-sandbox-egress', 'moon-sandbox-edge')) {
    $exists = docker network ls --format '{{.Name}}' | Where-Object { $_ -eq $net }
    if (-not $exists) { continue }
    $netLabelsJson = docker network inspect $net --format '{{json .Labels}}' 2>$null | Out-String
    $netOwner = $null
    if ($netLabelsJson -and $netLabelsJson.Trim() -ne 'null') {
        try { $netOwner = ($netLabelsJson | ConvertFrom-Json).'com.docker.compose.project' } catch { $netOwner = $null }
    }
    if ($netOwner -eq $projectName) {
        Write-Host "[prepare] 网络 $net 已由本项目管理，保留"
        continue
    }
    $inUse = docker network inspect $net --format '{{len .Containers}}' 2>$null
    if ($inUse -eq '0') {
        docker network rm $net | Out-Null
        Write-Host "[prepare] 移除空闲网络 $net"
    } else {
        Write-Host "[prepare][警告] 网络 $net 仍被容器占用，稍后可能冲突"
    }
}

Write-Host "[prepare] 完成"
