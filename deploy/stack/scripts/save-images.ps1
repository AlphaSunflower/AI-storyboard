# 导出离线镜像包（应用镜像 + 基础镜像 + 沙箱镜像），供 U 盘 / scp 迁移

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envFile  = Join-Path $StackDir '.env'
$outDir   = Join-Path $StackDir 'offline'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$tag = ((Get-Content $envFile | Where-Object { $_ -match '^IMAGE_TAG=' }) -split '=', 2)[1].Trim()

$appImages = @(
    "aistoryboard/api-gateway:$tag",
    "aistoryboard/backend:$tag",
    "aistoryboard/llm-gateway:$tag",
    "aistoryboard/moon-agent:$tag",
    "aistoryboard/asset-service:$tag",
    "aistoryboard/canvas:$tag",
    "aistoryboard/frontend:$tag",
    "aistoryboard/java-base:$tag",
    "aistoryboard/java-base-ffmpeg:$tag",
    'moon-sandbox-runner:local',
    'moon-sandbox-egress-proxy:local'
)

# 运行期直接使用的基础设施镜像
$baseImages = @(
    'nginx:alpine',
    'postgres:18-alpine',
    'redis:7-alpine',
    'quay.io/minio/minio'
)

function Save-Group([string]$name, [string[]]$images) {
    $present = @()
    foreach ($i in $images) {
        docker image inspect $i *> $null
        if ($LASTEXITCODE -eq 0) { $present += $i } else { Write-Host "[save][警告] 本地缺少镜像 $i" }
    }
    if ($present.Count -eq 0) { return }
    $tar = Join-Path $outDir "$name.tar"
    Write-Host "[save] $tar"
    docker save -o $tar @present
    if ($LASTEXITCODE -ne 0) { throw "docker save $name 失败" }
}

Save-Group "images-app-$tag"    $appImages
Save-Group "images-base-$tag"   $baseImages

$manifest = Join-Path $outDir 'manifest.txt'
@("IMAGE_TAG=$tag", '', 'APP IMAGES:') + $appImages + @('', 'BASE IMAGES:') + $baseImages |
    Set-Content -Path $manifest -Encoding utf8

$hashes = Get-ChildItem $outDir -Filter '*.tar' | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    "$h  $($_.Name)"
}
$hashes | Set-Content -Path (Join-Path $outDir 'sha256.txt') -Encoding utf8

Write-Host "[save] 完成，离线包目录: $outDir"
Get-ChildItem $outDir | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
