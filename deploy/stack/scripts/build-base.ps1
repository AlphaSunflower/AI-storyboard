# 构建共享 Java 基座镜像（普通版 + 含 ffmpeg 版），供所有应用镜像复用
param(
    [string]$AptMirror = 'mirrors.aliyun.com',
    [switch]$UseHostNetwork
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile  = Join-Path $StackDir '.env'
$tag      = ((Get-Content $envFile | Where-Object { $_ -match '^IMAGE_TAG=' }) -split '=', 2)[1].Trim()
$dockerfile = Join-Path $StackDir 'base\Dockerfile'

$netArgs = @()
if ($UseHostNetwork) { $netArgs = @('--network=host') }

function Build-Base([string]$image, [bool]$withFfmpeg) {
    Write-Host "[base] 构建 $image (ffmpeg=$withFfmpeg)"
    $withFfmpegArg = if ($withFfmpeg) { 'true' } else { 'false' }
    docker build @netArgs --progress=plain -t $image -f $dockerfile (Join-Path $StackDir 'base') `
        --build-arg "APT_MIRROR=$AptMirror" --build-arg "WITH_FFMPEG=$withFfmpegArg"
    if ($LASTEXITCODE -ne 0) { throw "基座镜像 $image 构建失败" }
}

Build-Base "aistoryboard/java-base:$tag" $false
Build-Base "aistoryboard/java-base-ffmpeg:$tag" $true

Write-Host '[base] 完成'
