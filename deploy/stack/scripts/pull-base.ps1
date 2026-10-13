# 预拉 docker compose 构建/运行所需的基础镜像，让后续构建与启动不再等下载

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$images = @(
    'eclipse-temurin:25-jre-noble',
    'node:22-alpine',
    'nginx:alpine',
    'postgres:18-alpine',
    'redis:7-alpine',
    'quay.io/minio/minio',
    'ubuntu/squid'
)

$ok = @(); $fail = @(); $skipped = @()
foreach ($img in $images) {
    docker image inspect $img *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[pull] 已存在，跳过 $img"
        $skipped += $img
        continue
    }
    Write-Host "[pull] $img"
    docker pull $img
    if ($LASTEXITCODE -eq 0) { $ok += $img } else { $fail += $img }
}

$listPath = Join-Path $StackDir 'offline\base-images.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $listPath) | Out-Null
# 记录构建期基础镜像清单（目标机若要重建需要这些）
($images | Where-Object { $fail -notcontains $_ }) | Set-Content -Path $listPath -Encoding utf8

Write-Host "[pull] 新拉 $($ok.Count) 个，已存在 $($skipped.Count) 个，失败 $($fail.Count) 个"
if ($fail.Count -gt 0) { Write-Host "[pull][警告] 失败: $($fail -join ', ')" }
