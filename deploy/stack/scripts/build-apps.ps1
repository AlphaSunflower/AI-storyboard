# 构建所有应用产物（Java fat jar + 两套前端 dist）并暂存到 build/ 供打镜像
param(
    [switch]$SkipJava,
    [switch]$SkipFrontend,
    # 只暂存现有 dist（不跑 npm run build）：本机刚构建过前端、只想重打镜像时用
    [switch]$SkipFrontendBuild
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path
$BuildDir = Join-Path $StackDir 'build'

function Stage-Jar([string]$Module, [string]$TargetName) {
    $target = Join-Path $RepoRoot "$Module\target"
    $jar = Get-ChildItem -Path $target -Filter '*.jar' -File |
           Where-Object { $_.Name -notlike '*.original' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "找不到 $Module 的构建产物（target/*.jar）" }
    $dir = Join-Path $BuildDir $TargetName
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item $jar.FullName (Join-Path $dir 'app.jar') -Force
    Write-Host ("[stage] {0,-14} <- {1} ({2:N1} MB)" -f $TargetName, $jar.Name, ($jar.Length / 1MB))
}

if (-not $SkipJava) {
    Write-Host '[build] mvn package -Dmaven.test.skip=true'
    Push-Location $RepoRoot
    try {
        & mvn -B '-Dmaven.test.skip=true' package
        if ($LASTEXITCODE -ne 0) { throw 'maven 构建失败' }
    } finally { Pop-Location }
}

Stage-Jar 'ApiGateway'          'api-gateway'
Stage-Jar 'AIStoryboardBackend' 'backend'
Stage-Jar 'AILLMGateway'        'llm-gateway'
Stage-Jar 'MoonAgent'           'moon-agent'
Stage-Jar 'asset-service'       'asset-service'
Stage-Jar 'XiaoYueCanvas'       'canvas'

if (-not $SkipFrontend) {
    foreach ($pair in @(@('AIStoryboardClient', 'dist'), @('XiaoYueCanvasAgent', 'canvas'))) {
        $app = $pair[0]; $sub = $pair[1]
        $appDir = Join-Path $RepoRoot $app
        Push-Location $appDir
        try {
            if ($SkipFrontendBuild) {
                Write-Host "[build] $app -SkipFrontendBuild：复用现有 dist"
            } else {
                if (-not (Test-Path (Join-Path $appDir 'node_modules'))) {
                    Write-Host "[build] $app 缺少 node_modules，先 npm ci"
                    & npm ci; if ($LASTEXITCODE -ne 0) { throw "$app npm ci 失败" }
                }
                Write-Host "[build] $app npm run build"
                & npm run build; if ($LASTEXITCODE -ne 0) { throw "$app 构建失败" }
            }
        } finally { Pop-Location }
        $src = Join-Path $appDir 'dist'
        $dst = Join-Path $BuildDir "frontend\$sub"
        if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $dst | Out-Null
        Copy-Item (Join-Path $src '*') $dst -Recurse -Force
        Write-Host "[stage] frontend/$sub <- $app/dist"
    }
    Copy-Item (Join-Path $StackDir 'nginx\default.conf') (Join-Path $BuildDir 'frontend\default.conf') -Force
}

Write-Host '[build] 应用产物暂存完成'
