# 打包 MinIO 全量对象数据（约 1.7GB）：历史图片 / 视频 / 文档 / 上传文件
#
#   powershell -ExecutionPolicy Bypass -File scripts\export-data.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\export-data.ps1 -SourceDir E:\...\minio\data
#
# 产物 offline/minio-data.tar 内部结构是 data/minio/...，目标机上：
#   - load-and-up.sh 在 data/minio 为空时自动解包；
#   - 也可以手工：tar -xf offline/minio-data.tar -C .
param(
    [string]$SourceDir,
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path
if (-not $SourceDir) { $SourceDir = Join-Path $RepoRoot 'deploy\docker\minio\data' }
if (-not $OutFile) { $OutFile = Join-Path $StackDir 'offline\minio-data.tar' }
if (-not (Test-Path $SourceDir)) { throw "找不到 MinIO 数据目录: $SourceDir" }

$files = Get-ChildItem $SourceDir -Recurse -File -Force
if ($files.Count -lt 1500) {
    throw "MinIO 数据目录只有 $($files.Count) 个文件（期望 >=1500），拒绝导出空数据"
}
$bytes = ($files | Measure-Object Length -Sum).Sum
$mb = [math]::Round($bytes / 1MB, 1)
Write-Host "[export] $SourceDir ($($files.Count) 个文件, $mb MB) -> $OutFile"

$tar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $tar)) { throw "找不到 tar.exe：$tar（Windows 10 1803+ 自带）" }
New-Item -ItemType Directory -Force -Path (Split-Path $OutFile) | Out-Null
if (Test-Path $OutFile) { Remove-Item $OutFile -Force }

# 统一打包成 data/minio/... 结构（无论 SourceDir 的最后一级叫什么），
# 目标机 -C . 解包即得 data/minio；load-and-up.sh 也按这个结构自动解包。
$stage = Join-Path ([System.IO.Path]::GetTempPath()) "moon-minio-pack-$PID"
$stageData = Join-Path $stage 'data'
$stageMinio = Join-Path $stageData 'minio'
New-Item -ItemType Directory -Force -Path $stageData | Out-Null
Write-Host "[export] 暂存到 $stageMinio"
Copy-Item $SourceDir $stageMinio -Recurse -Force
try {
    & $tar -cf $OutFile -C $stage data
    if ($LASTEXITCODE -ne 0) { throw 'tar 打包失败' }
} finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}

$hash = (Get-FileHash $OutFile -Algorithm SHA256).Hash
Set-Content -Path ($OutFile + '.sha256') -Value "$hash  $(Split-Path $OutFile -Leaf)" -Encoding ASCII
$outMb = [math]::Round((Get-Item $OutFile).Length / 1MB, 1)
Write-Host "[export] 完成：$OutFile（$outMb MB）"
Write-Host "[export] sha256=$hash"
Write-Host '[export] 目标机解包（在 deploy/allinone 目录下）：tar -xf offline/minio-data.tar -C .'
