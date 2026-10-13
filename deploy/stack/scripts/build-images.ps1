# 构建应用镜像（基础镜像请先跑 pull-base.ps1）
param([string]$Tag)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose = Join-Path $StackDir 'docker-compose.yml'
$envFile = Join-Path $StackDir '.env'

if ($Tag) { $env:IMAGE_TAG = $Tag }

Push-Location $StackDir
try {
    docker compose --env-file $envFile -f $compose build
    if ($LASTEXITCODE -ne 0) { throw 'docker compose build 失败' }
} finally { Pop-Location }

Write-Host '[build-images] 完成'
