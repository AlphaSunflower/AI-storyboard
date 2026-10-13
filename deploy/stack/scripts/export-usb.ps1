# 把迁移到另一台机器所需要的文件汇总到一个目录（可直接拷进 U 盘）
# 目录结构保持：<目标>\deploy\stack\... 与 <目标>\deploy\docker\minio\data\...
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$WithSources
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path
$MinioData = Join-Path $RepoRoot 'deploy\docker\minio\data'

$targetFull = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Target)
if ($targetFull -like "$RepoRoot*") { throw "导出目标不能落在仓库内: $targetFull" }

Write-Host "[export] 目标: $targetFull"
New-Item -ItemType Directory -Force -Path $targetFull | Out-Null

# 1) deploy/stack（排除 build 暂存）；offline/、db/*.dump、.env、admin-scrypt.txt 都带上
$stackDst = Join-Path $targetFull 'deploy\stack'
if (Test-Path $stackDst) { Remove-Item $stackDst -Recurse -Force }
robocopy $StackDir $stackDst /E /XD build /NFL /NDL /NJH /NJS /NP | Out-Null
Write-Host "[export] deploy/stack -> $stackDst （已排除 build/）"

# 2) MinIO 对象数据（必须带，否则目标机是空桶）
$minioDst = Join-Path $targetFull 'deploy\docker\minio\data'
if (-not (Test-Path $MinioData)) { throw "找不到 MinIO 数据目录: $MinioData" }
New-Item -ItemType Directory -Force -Path (Split-Path $minioDst) | Out-Null
if (Test-Path $minioDst) { Remove-Item $minioDst -Recurse -Force }
robocopy $MinioData $minioDst /E /NFL /NDL /NJH /NJS /NP | Out-Null
Write-Host "[export] MinIO 数据 -> $minioDst"

# 3) 可选：源码（想在目标机重新构建镜像时才需要）
if ($WithSources) {
    $srcDst = Join-Path $targetFull 'src'
    if (Test-Path $srcDst) { Remove-Item $srcDst -Recurse -Force }
    robocopy $RepoRoot $srcDst /E /XD node_modules target .git build offline dist .idea .superpowers `
        /XF "*.log" /NFL /NDL /NJH /NJS /NP | Out-Null
    Write-Host "[export] 源码 -> $srcDst"
    $sourceNote = @(
        '# 源码迁移说明',
        '',
        '目标机若要重新构建镜像（而不是直接 docker load 离线包），需要：',
        '1) JDK 25 + Maven 3.9+、Node 22+',
        '2) 按 deploy/stack/README.md 的首次部署步骤：pull-base -> build-apps -> build-base -UseHostNetwork -> build-images -> up',
        '3) db/*.dump 已在 deploy/stack/db 下，可跳过 dump-db 步骤（除非目标机要用自己的原生 PG 数据）',
        '4) 被 .gitignore 忽略的 application-local.yml / application-prod.yml 已包含在本包内'
    )
    [System.IO.File]::WriteAllText((Join-Path $targetFull 'SOURCE-BUILD.md'), ($sourceNote -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
}

# 4) 目标机操作说明
$migrateNote = @(
    '# 目标机迁移步骤',
    '',
    '1) 把本目录整体拷到目标机，保持 deploy/stack 与 deploy/docker/minio/data 的相对位置不变',
    '2) 目标机装好 Docker（含 Compose v2）',
    '3) 进入 deploy/stack 执行：bash scripts/load-and-up.sh',
    '   （它负责 docker load 离线镜像并起栈；目标机不需要联网、不需要 Maven/Node）',
    '4) 自检：Windows 目标机可跑 powershell -File scripts/verify.ps1；Linux 目标机用 curl 手测登录接口',
    '5) 访问：http://<目标机IP>/    账号 admin@qq.com / 123456',
    '',
    '说明',
    '- 数据来自本机 ai_storyboard + llm_gateway 整库转储（db/*.dump，首启自动还原）与 MinIO 全量对象（deploy/docker/minio/data）。',
    '- 端口：前端 80；DB/Redis/MinIO/Sandbox 仅绑 127.0.0.1；模型网关发布在 127.0.0.1:8083（/v1/**、/admin-ui/、/admin/**）。',
    '- Nacos 不在本栈内：服务发现默认关闭（NACOS_DISCOVERY_ENABLED=false），ApiGateway 按 .env 的四个 *_URI 直连各服务。',
    '- 需要局域网访问时改 deploy/stack/.env 或 docker-compose.yml 里的端口绑定。',
    '- 画布执行命令的“信箱”在 canvas 服务内（/api/canvas/bridge/**），浏览器同源轮询领取，无独立进程或端口。'
)
[System.IO.File]::WriteAllText((Join-Path $targetFull 'MIGRATE-README.md'), ($migrateNote -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "[export] 已写入 MIGRATE-README.md"

Write-Host ''
Write-Host '[export] 体积统计:'
$rows = @()
foreach ($dir in @($stackDst, $minioDst, (Join-Path $targetFull 'src'))) {
    if (Test-Path $dir) {
        $mb = (Get-ChildItem $dir -Recurse -Force -File | Measure-Object Length -Sum).Sum / 1MB
        $rows += [pscustomobject]@{ Path = $dir.Replace($targetFull, '.'); MB = [Math]::Round($mb, 1) }
    }
}
$totalMb = (Get-ChildItem $targetFull -Recurse -Force -File | Measure-Object Length -Sum).Sum / 1MB
$rows += [pscustomobject]@{ Path = '(合计)'; MB = [Math]::Round($totalMb, 1) }
$rows | Format-Table -AutoSize
