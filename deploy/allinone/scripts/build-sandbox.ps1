# 构建可选的 Agent 沙箱镜像（默认不启用的 sandbox profile 才用得到）
#   moon-sandbox-runner:<tag>       执行 Agent 的写文件 / Python / 产物生成
#   moon-sandbox-egress-proxy:<tag> 沙箱唯一出网通道（域名白名单）
#
# 在 2 核 2G 上默认不启动；需要 Agent 产物能力时再构建 + 打开 profile。
param(
    [string]$Tag = 'lite'
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path

$sandboxJar = Join-Path $RepoRoot 'MoonSandbox\target\moon-sandbox.jar'
if (-not (Test-Path $sandboxJar)) {
    throw "缺少 $sandboxJar，先跑 mvn -pl MoonSandbox -am '-Dmaven.test.skip=true' package（或 scripts\build-apps.ps1 -RebuildJava）"
}

Write-Host "[build] moon-sandbox-runner:$Tag"
& docker build -t "moon-sandbox-runner:$Tag" `
    -f (Join-Path $RepoRoot 'deploy\docker\sandbox\Dockerfile') `
    --build-arg MOON_SANDBOX_UID=10001 --build-arg MOON_SANDBOX_GID=10001 `
    $RepoRoot
if ($LASTEXITCODE -ne 0) { throw 'docker build moon-sandbox-runner 失败' }

Write-Host "[build] moon-sandbox-egress-proxy:$Tag"
& docker build -t "moon-sandbox-egress-proxy:$Tag" `
    -f (Join-Path $RepoRoot 'deploy\docker\sandbox\proxy\Dockerfile') `
    (Join-Path $RepoRoot 'deploy\docker\sandbox\proxy')
if ($LASTEXITCODE -ne 0) { throw 'docker build moon-sandbox-egress-proxy 失败' }

Write-Host '[build-sandbox] 完成'
