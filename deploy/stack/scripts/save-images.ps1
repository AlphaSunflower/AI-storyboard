# 导出离线镜像包（7 个应用镜像 + 2 个基座 + 2 个沙箱镜像 + 4 个基础设施镜像），供 U 盘 / scp 迁移
#
#   powershell -File scripts\save-images.ps1
#   powershell -File scripts\save-images.ps1 -WithData   # 额外把 MinIO/沙箱数据打成 tar（可选）
param([switch]$WithData)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile  = Join-Path $StackDir '.env'
$outDir   = Join-Path $StackDir 'offline'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function EnvVal([string]$key, [string]$fallback) {
    $line = Get-Content $envFile | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if (-not $line) { return $fallback }
    return (($line -split '=', 2)[1]).Trim()
}

$tag        = EnvVal 'IMAGE_TAG' 'stack-20261020'
$sandboxTag = EnvVal 'SANDBOX_IMAGE_TAG' 'stack-20261020'

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
    "moon-sandbox-runner:$sandboxTag",
    "moon-sandbox-egress-proxy:$sandboxTag"
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

# 旧 tag 的离线包先清掉，避免 load-and-up.sh 把过期镜像一起 load
Get-ChildItem $outDir -Filter 'images-*.tar' | Remove-Item -Force

Save-Group "images-app-$tag"    $appImages
Save-Group "images-base-$tag"   $baseImages

# 可选：数据目录打包（目标机 load-and-up.sh 会自动识别 offline/*-data.tar）
if ($WithData) {
    $tarExe = Join-Path $env:SystemRoot 'System32\tar.exe'
    foreach ($pair in @(@('minio-data.tar', 'data\minio'), @('sandbox-workspaces.tar', 'data\sandbox-workspaces'))) {
        $name = $pair[0]; $rel = $pair[1]
        $src = Join-Path $StackDir $rel
        if (-not (Test-Path $src)) { Write-Host "[save][警告] 缺少 $src"; continue }
        $out = Join-Path $outDir $name
        if (Test-Path $out) { Remove-Item $out -Force }
        Write-Host "[save] $rel -> $out"
        Push-Location $StackDir
        try { & $tarExe -cf $out (Join-Path 'data' (Split-Path $rel -Leaf)) } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "打包 $rel 失败" }
    }
}

$manifest = Join-Path $outDir 'manifest.txt'
@("IMAGE_TAG=$tag", "SANDBOX_IMAGE_TAG=$sandboxTag", '', 'APP IMAGES:') + $appImages + @('', 'BASE IMAGES:') + $baseImages |
    Set-Content -Path $manifest -Encoding utf8

$hashes = Get-ChildItem $outDir -Filter '*.tar' | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    "$h  $($_.Name)"
}
$hashes | Set-Content -Path (Join-Path $outDir 'sha256.txt') -Encoding utf8

Write-Host "[save] 完成，离线包目录: $outDir"
Get-ChildItem $outDir | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
