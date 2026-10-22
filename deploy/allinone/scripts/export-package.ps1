# 把「目标机只需要的东西」汇总到一个目录，直接 scp/拷到远程服务器
#
#   powershell -File scripts\export-package.ps1 -Target E:\Desktop\ai-lite-deploy
#
# 包含：compose / .env / db 初始化 / nginx / config / scripts / offline 镜像包
# 不包含：build 暂存目录（几 GB 的中间产物，目标机用不到）
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$WithSource
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path
$targetFull = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Target)
if ($targetFull -like "$StackDir*") { throw "导出目标不能落在源目录内: $targetFull" }

Write-Host "[export] 目标: $targetFull"
if (Test-Path $targetFull) { Remove-Item $targetFull -Recurse -Force }
New-Item -ItemType Directory -Force -Path $targetFull | Out-Null

# 先统一换行符：Windows 检出的 CRLF shell 脚本在 Linux 上会让 postgres 初始化直接失败
& (Join-Path $PSScriptRoot 'normalize-lf.ps1') -Root $StackDir

# ── 1) 运行必需的文件 ───────────────────────────────────────────────────
$dirs = @('nginx', 'config', 'db', 'scripts', 'allinone', 'frontend', 'launcher')
$files = @('docker-compose.yml', '.env', '.env.example', 'README.md', '.gitignore')
foreach ($dir in $dirs) {
    $src = Join-Path $StackDir $dir
    if (Test-Path $src) {
        Copy-Item $src (Join-Path $targetFull $dir) -Recurse -Force
    }
}
foreach ($file in $files) {
    $src = Join-Path $StackDir $file
    if (Test-Path $src) { Copy-Item $src (Join-Path $targetFull $file) -Force }
}
# 离线镜像包（*.tar + manifest + sha256）
$offlineSrc = Join-Path $StackDir 'offline'
if (Test-Path $offlineSrc) {
    $offlineDst = Join-Path $targetFull 'offline'
    New-Item -ItemType Directory -Force -Path $offlineDst | Out-Null
    Copy-Item (Join-Path $offlineSrc '*') $offlineDst -Recurse -Force
} else {
    Write-Host '[export][警告] 还没有 offline 镜像包，先在源机跑 scripts\save-images.ps1'
}
# MinIO 数据落点（空目录，目标机可选地解 minio-data.tar 进来）
New-Item -ItemType Directory -Force -Path (Join-Path $targetFull 'data\minio') | Out-Null
Set-Content -Path (Join-Path $targetFull 'data\minio\.keep') -Value '' -Encoding ascii

# ── 2) 目标机一页纸说明 ─────────────────────────────────────────────────
$note = @(
    '# 目标机部署（单 JVM 全栈）',
    '',
    '## 1. 传上去',
    '在源机（本机）执行：',
    '  scp -r <本目录> root@<服务器IP>:/opt/ai-lite',
    '（也可以用 U 盘 / 宝塔面板 / 任意文件传输方式，保持目录结构不变）',
    '',
    '## 2. 前置检查（2 核 2G 必看）',
    '  docker version && docker compose version',
    '  free -m          # 看内存与 swap',
    '没有 swap 或小于 1G 时先加 4G（否则高峰期会被 OOM Killer 杀掉）：',
    '  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile',
    '  echo "/swapfile none swap sw 0 0" >> /etc/fstab',
    '',
    '## 3. 起栈',
    '  cd /opt/ai-lite',
    '  bash scripts/load-and-up.sh',
    '  # 需要 Agent 沙箱（写文件 / 跑 Python / 产物文件卡）时：',
    '  #   sed -i "s/^AGENT_SANDBOX_ENABLED=.*/AGENT_SANDBOX_ENABLED=true/" .env',
    '  #   WITH_SANDBOX=1 bash scripts/load-and-up.sh',
    '',
    '## 4. 首次启动要等 1~3 分钟（6 个 Spring 上下文在同一个 JVM 里串行启动）',
    '  docker compose --env-file .env logs -f allinone',
    '看到 6 行 "Started ... in ... seconds" 就好了。',
    '',
    '## 5. 访问',
    '  http://<服务器公网IP>/',
    '  admin@qq.com / 123456',
    '  test@qq.com  / 123456',
    '注册入口已被 nginx 拦成 403（后端注册代码未改动）。',
    '',
    '## 6. 安全组 / 防火墙',
    '只放行 80（以及你实际用的端口）。5433/6379/9000/9001/8083/8090 都只绑在 127.0.0.1，公网访问不到。',
    '',
    '## 7. 常用命令',
    '  docker compose --env-file .env ps',
    '  docker compose --env-file .env logs -f allinone',
    '  docker compose --env-file .env restart allinone',
    '  docker stats --no-stream        # 看内存占用',
    '',
    '## 8. 排障先看 README.md 的「排障」一节'
)
Set-Content -Path (Join-Path $targetFull '目标机部署.txt') -Value ($note -join "`r`n") -Encoding utf8

# ── 3) 可选：源码（目标机要从源码重建才需要）────────────────────────────
if ($WithSource) {
    $srcDst = Join-Path $targetFull 'src'
    Write-Host "[export] 拷贝源码 -> $srcDst"
    robocopy $RepoRoot $srcDst /E /XD node_modules target .git build offline dist .idea .superpowers .codegraph .firecrawl .hermes .tmp-asar-probe test-results /XF '*.log' /NFL /NDL /NJH /NJS /NP | Out-Null
}

Write-Host ''
Write-Host '[export] 体积统计:'
$rows = foreach ($item in (Get-ChildItem $targetFull -Force)) {
    $sum = if ($item.PSIsContainer) {
        (Get-ChildItem $item.FullName -Recurse -Force -File | Measure-Object Length -Sum).Sum
    } else { $item.Length }
    [pscustomobject]@{ Name = $item.Name; MB = [Math]::Round($sum / 1MB, 1) }
}
$rows | Sort-Object MB -Descending | Format-Table -AutoSize
$total = (Get-ChildItem $targetFull -Recurse -Force -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("[export] 合计 {0:N1} MB -> {1}" -f $total, $targetFull)
