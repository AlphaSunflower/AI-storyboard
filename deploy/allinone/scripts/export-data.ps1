# 打包 MinIO 对象数据（可选，约 1.6GB）：历史图片/视频/上传文件的原始对象
# 不带这份数据时，应用功能完全正常，只是库里引用的历史媒体对象 404。
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

$mb = [math]::Round((Get-ChildItem $SourceDir -Recurse -File -Force | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Host "[export] $SourceDir ($mb MB) -> $OutFile"

$tar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $tar)) { throw "找不到 tar.exe：$tar（Windows 10 1803+ 自带）" }
if (Test-Path $OutFile) { Remove-Item $OutFile -Force }

# 打包成 data/minio/... 的相对结构，目标机解包：tar -xf minio-data.tar -C .
Push-Location (Split-Path $SourceDir)
try {
    & $tar -cf $OutFile (Split-Path $SourceDir -Leaf)
    if ($LASTEXITCODE -ne 0) { throw 'tar 打包失败' }
} finally { Pop-Location }

$hash = (Get-FileHash $OutFile -Algorithm SHA256).Hash
Write-Host "[export] 完成：$OutFile"
Write-Host "[export] sha256=$hash"
Write-Host '[export] 目标机解包（在 deploy/allinone 目录下）：tar -xf minio-data.tar -C .'
