# 导出离线镜像包（目标机 docker load 即可，不需要联网/Maven/Node）
#
#   offline/images-core-<tag>.tar     必传：allinone + frontend-lite + postgres/redis/minio/nginx
#   offline/images-sandbox-<tag>.tar  可选：Agent 沙箱（默认不启用的 profile）
#
# 另外写出 manifest.txt / sha256.txt。目标机用 scripts/load-and-up.sh 载入并启动。
param(
    [switch]$WithSandbox,
    [string]$Tag
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envMap = Get-StackEnv -StackDir $StackDir
if (-not $Tag) { $Tag = $envMap['IMAGE_TAG'] }
if (-not $Tag) { throw '.env 里没有 IMAGE_TAG' }
$sandboxTag = if ($envMap['SANDBOX_IMAGE_TAG']) { $envMap['SANDBOX_IMAGE_TAG'] } else { 'lite' }

$outDir = Join-Path $StackDir 'offline'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$coreImages = @(
    "aistoryboard/allinone:$Tag",
    "aistoryboard/frontend-lite:$Tag",
    'postgres:18-alpine',
    'redis:7-alpine',
    'quay.io/minio/minio',
    'nginx:alpine'
)
$sandboxImages = @(
    "moon-sandbox-runner:$sandboxTag",
    "moon-sandbox-egress-proxy:$sandboxTag"
)

function Save-Group([string]$name, [string[]]$images) {
    $present = @()
    foreach ($image in $images) {
        & docker image inspect $image *> $null
        if ($LASTEXITCODE -eq 0) { $present += $image } else { Write-Host "[save][警告] 本地缺少镜像 $image" }
    }
    if ($present.Count -eq 0) { throw "$name 没有可导出的镜像" }
    $tar = Join-Path $outDir "$name.tar"
    if (Test-Path $tar) { Remove-Item $tar -Force }
    Write-Host "[save] $tar"
    & docker save -o $tar @present
    if ($LASTEXITCODE -ne 0) { throw "docker save $name 失败" }
}

Save-Group "images-core-$Tag" $coreImages
if ($WithSandbox) { Save-Group "images-sandbox-$Tag" $sandboxImages }

$manifest = Join-Path $outDir 'manifest.txt'
@("IMAGE_TAG=$Tag", "SANDBOX_IMAGE_TAG=$sandboxTag", '', 'CORE IMAGES:') + $coreImages +
    @('', 'SANDBOX IMAGES:') + $sandboxImages | Set-Content -Path $manifest -Encoding utf8

Get-ChildItem $outDir -Filter '*.tar' | ForEach-Object {
    $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    "$hash  $($_.Name)"
} | Set-Content -Path (Join-Path $outDir 'sha256.txt') -Encoding utf8

Write-Host ''
Get-ChildItem $outDir | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
Write-Host "[save-images] 完成，离线包目录: $outDir"
