# 构建单 JVM 全栈的两个应用镜像
#   1) aistoryboard/allinone:<tag>       6 个 Java 服务 + 启动器（同一个 JVM）
#   2) aistoryboard/frontend-lite:<tag>  nginx 静态站 + /api 反代 + 注册关闭
#
# 前置：powershell -File scripts\build-apps.ps1
param(
    [string]$Tag
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envMap = Get-StackEnv -StackDir $StackDir
if (-not $Tag) { $Tag = $envMap['IMAGE_TAG'] }
$baseImage = $envMap['BASE_IMAGE']
if (-not $Tag) { throw '.env 里没有 IMAGE_TAG' }

$allInOneCtx = Join-Path $StackDir 'build\allinone'
$frontendCtx = Join-Path $StackDir 'build\frontend'
foreach ($ctx in @($allInOneCtx, $frontendCtx)) {
    if (-not (Test-Path $ctx)) { throw "构建上下文不存在: $ctx（先跑 scripts\build-apps.ps1）" }
}

Write-Host "[build] aistoryboard/allinone:$Tag  (BASE_IMAGE=$baseImage)"
$buildArgs = @()
if ($baseImage) { $buildArgs = @('--build-arg', "BASE_IMAGE=$baseImage") }
& docker build -t "aistoryboard/allinone:$Tag" -f (Join-Path $StackDir 'allinone\Dockerfile') @buildArgs $allInOneCtx
if ($LASTEXITCODE -ne 0) { throw 'docker build allinone 失败' }

Write-Host "[build] aistoryboard/frontend-lite:$Tag"
& docker build -t "aistoryboard/frontend-lite:$Tag" -f (Join-Path $StackDir 'frontend\Dockerfile') $frontendCtx
if ($LASTEXITCODE -ne 0) { throw 'docker build frontend-lite 失败' }

Write-Host ''
docker images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' |
    Where-Object { $_ -like "aistoryboard/allinone:$Tag*" -or $_ -like "aistoryboard/frontend-lite:$Tag*" }
Write-Host '[build-images] 完成'
