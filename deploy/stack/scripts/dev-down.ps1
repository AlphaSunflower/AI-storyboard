# 退出开发模式：停掉依赖容器；加 -FullStack 则直接恢复整套部署
param([switch]$FullStack)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envFile  = Join-Path $StackDir '.env'

Push-Location $StackDir
try {
    if ($FullStack) {
        Write-Host '[dev-down] 恢复整套部署（up.ps1）'
        & (Join-Path $PSScriptRoot 'up.ps1') -SkipPrepare
    } else {
        $depServices = @('postgres', 'redis', 'minio', 'sandbox-runner', 'sandbox-egress-proxy')
        Write-Host "[dev-down] 停止依赖容器: $($depServices -join ', ')"
        docker compose --env-file $envFile -f $compose stop @depServices 2>&1 | Out-Null
        Write-Host '[dev-down] 已停（数据卷与 MinIO 宿主数据保留）；要恢复整套部署用 -FullStack'
    }
} finally { Pop-Location }
