# 暂存单 JVM 全栈镜像的构建上下文（不改动 deploy/stack 的暂存目录）
#
#   deploy/allinone/build/allinone/appjars/*.jar        6 个服务的 fat jar
#   deploy/allinone/build/allinone/config/*.json        per-service 配置覆盖
#   deploy/allinone/build/allinone/all-in-one-launcher.jar
#   deploy/allinone/build/frontend/{dist,canvas,default.conf}
#
# 用法：
#   powershell -File scripts\build-apps.ps1                      # 用现有 target/ 与 dist/ 暂存（快）
#   powershell -File scripts\build-apps.ps1 -RebuildJava -RebuildFrontend   # 先 mvn package + npm build
param(
    [switch]$RebuildJava,
    [switch]$RebuildFrontend
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path
$BuildDir = Join-Path $StackDir 'build'
$AppJarDir = Join-Path $BuildDir 'allinone\appjars'
$ConfigSrc = Join-Path $StackDir 'config'
$ConfigDst = Join-Path $BuildDir 'allinone\config'
$FrontendDst = Join-Path $BuildDir 'frontend'
$LauncherJar = Join-Path $BuildDir 'allinone\all-in-one-launcher.jar'
$ClassesDir = Join-Path $BuildDir 'launcher-classes'

# 服务名 -> Maven 模块目录（服务名同时是 services/<name>/ 与 config/<name>.json 的名字）
$services = [ordered]@{
    'llm-gateway'   = 'AILLMGateway'
    'backend'       = 'AIStoryboardBackend'
    'asset-service' = 'asset-service'
    'canvas'        = 'XiaoYueCanvas'
    'moon-agent'    = 'MoonAgent'
    'api-gateway'   = 'ApiGateway'
}

function Invoke-Step([string]$what, [scriptblock]$body) {
    Write-Host "[build] $what"
    & $body
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
        throw "$what 失败（exit=$LASTEXITCODE）"
    }
}

# ── 1) Java 产物 ────────────────────────────────────────────────────────
if ($RebuildJava) {
    Push-Location $RepoRoot
    try {
        Invoke-Step 'mvn package（10 个模块，跳过测试）' { & mvn -B '-Dmaven.test.skip=true' package }
    } finally { Pop-Location }
}

# 注意：这里不能整目录删掉再重建 —— 保留同名 jar 才有机会命中下面「未变复用」的分支，
# 从而避免 docker 的 COPY appjars 层因为 mtime 变化而重新解包 700MB。
New-Item -ItemType Directory -Force -Path $AppJarDir | Out-Null
Get-ChildItem $AppJarDir -Filter '*.jar' -File |
    Where-Object { $services.Keys -notcontains $_.BaseName } |
    Remove-Item -Force

foreach ($name in $services.Keys) {
    $module = $services[$name]
    $target = Join-Path $RepoRoot "$module\target"
    if (-not (Test-Path $target)) { throw "找不到 $module 的 target 目录，请先 -RebuildJava 或手工 mvn package" }
    $jar = Get-ChildItem -Path $target -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-sources.jar' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "找不到 $module 的构建产物（$target\*.jar）" }
    $dst = Join-Path $AppJarDir "$name.jar"
    # 内容没变就不重写：避免 mtime 变化让 docker 的 COPY appjars 层失效（那会重新解包 700MB）
    $unchanged = $false
    $prev = Get-Item $dst -ErrorAction SilentlyContinue
    if ($prev -and $prev.Length -eq $jar.Length) { $unchanged = $true }
    if ($unchanged) {
        Write-Host ("[stage] {0,-14} = 未变，复用已暂存的 {1:N1} MB" -f $name, ($jar.Length / 1MB))
    } else {
        Copy-Item $jar.FullName $dst -Force
        Write-Host ("[stage] {0,-14} <- {1} ({2:N1} MB)" -f $name, $jar.Name, ($jar.Length / 1MB))
    }
}

# ── 2) per-service 配置 ─────────────────────────────────────────────────
if (Test-Path $ConfigDst) { Remove-Item $ConfigDst -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ConfigDst | Out-Null
Copy-Item (Join-Path $ConfigSrc '*.json') $ConfigDst -Force
foreach ($name in $services.Keys) {
    if (-not (Test-Path (Join-Path $ConfigDst "$name.json"))) {
        Write-Host "[stage][提示] config\$name.json 不存在，该服务不会带 spring.application.json 覆盖"
    }
}

# ── 3) 编译启动器（两个类，无第三方依赖）────────────────────────────────
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw 'JAVA_HOME 未设置（本机应为 E:\Development\jdk25）' }
$javac = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'
foreach ($tool in @($javac, $jarTool)) {
    if (-not (Test-Path $tool)) { throw "找不到 $tool（检查 JAVA_HOME=$javaHome）" }
}
if (Test-Path $ClassesDir) { Remove-Item $ClassesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
Invoke-Step 'javac 编译 AllInOneLauncher + Unzip' {
    & $javac -encoding UTF-8 -d $ClassesDir `
        (Join-Path $StackDir 'launcher\AllInOneLauncher.java') `
        (Join-Path $StackDir 'launcher\Unzip.java')
}
if (Test-Path $LauncherJar) { Remove-Item $LauncherJar -Force }
Invoke-Step '打包 all-in-one-launcher.jar' {
    & $jarTool --create --file $LauncherJar --main-class moon.allinone.AllInOneLauncher -C $ClassesDir .
}
Write-Host ("[stage] {0,-14} <- {1:N1} KB" -f 'launcher', ((Get-Item $LauncherJar).Length / 1KB))

# ── 4) 两个前端 dist + nginx 配置 ───────────────────────────────────────
if ($RebuildFrontend) {
    foreach ($app in @('AIStoryboardClient', 'XiaoYueCanvasAgent')) {
        Push-Location (Join-Path $RepoRoot $app)
        try {
            if (-not (Test-Path (Join-Path $RepoRoot "$app\node_modules"))) {
                Invoke-Step "$app npm ci" { & npm ci }
            }
            Invoke-Step "$app npm run build" { & npm run build }
        } finally { Pop-Location }
    }
}

if (Test-Path $FrontendDst) { Remove-Item $FrontendDst -Recurse -Force }
New-Item -ItemType Directory -Force -Path $FrontendDst | Out-Null
foreach ($pair in @(@('AIStoryboardClient', 'dist'), @('XiaoYueCanvasAgent', 'canvas'))) {
    $app = $pair[0]
    $sub = $pair[1]
    $src = Join-Path $RepoRoot "$app\dist"
    if (-not (Test-Path (Join-Path $src 'index.html'))) { throw "$app\dist 不完整，请先 -RebuildFrontend" }
    $dst = Join-Path $FrontendDst $sub
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    Copy-Item (Join-Path $src '*') $dst -Recurse -Force
    Write-Host "[stage] frontend/$sub <- $app/dist"
}
Copy-Item (Join-Path $StackDir 'nginx\default.conf') (Join-Path $FrontendDst 'default.conf') -Force

Write-Host ''
Write-Host '[build] 暂存完成：'
Write-Host "  allinone 上下文: $BuildDir\allinone"
Write-Host "  frontend 上下文: $FrontendDst"
Write-Host '  下一步: powershell -File scripts\build-images.ps1'
