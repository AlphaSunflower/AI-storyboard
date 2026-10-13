# 停止统一栈（保留数据卷与 MinIO 宿主数据目录）
param([switch]$RemoveVolumes)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envFile  = Join-Path $StackDir '.env'

Push-Location $StackDir
try {
    if ($RemoveVolumes) {
        Write-Host '[down][警告] 同时删除本项目数据卷（不含 MinIO 宿主目录）'
        docker compose --env-file $envFile -f $compose down -v
    } else {
        docker compose --env-file $envFile -f $compose down
    }
} finally { Pop-Location }
