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

# 数据完整性硬校验：这是「所有数据都存在」的交付包，缺任何一份都不允许导出
$required = @(
    (Join-Path $StackDir 'db\ai_storyboard.dump'),
    (Join-Path $StackDir 'db\llm_gateway.dump'),
    (Join-Path $StackDir 'db\data-manifest.env'),
    (Join-Path $StackDir 'offline\minio-data.tar')
)
foreach ($f in $required) {
    if (-not (Test-Path $f)) { throw "缺少全量数据文件: $f（先跑 scripts\export-db.ps1 + scripts\export-data.ps1）" }
}
$dumpMb = [math]::Round((Get-Item (Join-Path $StackDir 'db\ai_storyboard.dump')).Length / 1MB, 1)
$minioMb = [math]::Round((Get-Item (Join-Path $StackDir 'offline\minio-data.tar')).Length / 1MB, 1)
if ($dumpMb -lt 5) { throw "ai_storyboard.dump 只有 $dumpMb MB，疑似空库" }
if ($minioMb -lt 1000) { throw "minio-data.tar 只有 $minioMb MB，疑似对象没带全" }
Write-Host "[export] 全量数据: ai_storyboard.dump=$dumpMb MB, minio-data.tar=$minioMb MB"

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
# 离线镜像包（*.tar + manifest + sha256；含 minio-data.tar）
$offlineSrc = Join-Path $StackDir 'offline'
if (Test-Path $offlineSrc) {
    $offlineDst = Join-Path $targetFull 'offline'
    New-Item -ItemType Directory -Force -Path $offlineDst | Out-Null
    Copy-Item (Join-Path $offlineSrc '*') $offlineDst -Recurse -Force
} else {
    throw '[export] 没有 offline 离线包，先跑 scripts\save-images.ps1 和 scripts\export-data.ps1'
}
# MinIO 数据落点：目标机由 load-and-up.sh 从 offline/minio-data.tar 自动解到 data/minio。
# 这里不要放 .keep——那会让 load-and-up 误判“目录非空”，从而跳过解包。
New-Item -ItemType Directory -Force -Path (Join-Path $targetFull 'data') | Out-Null

# ── 2) 目标机一页纸说明 ─────────────────────────────────────────────────
$note = @(
    '# 目标机部署（单 JVM + 全量数据）',
    '',
    '本包 = 6 个 Spring Boot 服务跑在 1 个 JVM 里 + Postgres/Redis/MinIO + nginx 入口，',
    '并带上了本地全量数据：157 个账号、168 个项目、376 个会话、677 条 Agent 资产、',
    '2087 个 MinIO 对象文件（1.72GB）。admin@qq.com 由 771038325@qq.com 原地改名，其余账号一行不删。',
    '',
    '## 0. 先备份并停掉旧栈（122.51.12.200 上现有 /opt/ai-lite）',
    '旧栈的卷和数据目录都不要删；新包用独立前缀 ai-cloud，起新栈不会覆盖旧数据。',
    '  mkdir -p /opt/backup',
    '  cd /opt/ai-lite',
    '  docker exec ai-lite-postgres pg_dump -U postgres -Fc ai_storyboard > /opt/backup/ai_storyboard-old.dump',
    '  docker exec ai-lite-postgres pg_dump -U postgres -Fc llm_gateway     > /opt/backup/llm_gateway-old.dump',
    '  docker compose --env-file .env down          # 先停容器（保留卷），释放 80 端口',
    '  tar -czf /opt/backup/ai-lite-minio-$(date +%F).tar.gz data/minio 2>/dev/null || true',
    '',
    '## 1. 传上去',
    '方式 A（推荐，单文件）：在源机把 ai-cloud-deploy-20261021.tar 传上去，再在服务器解包：',
    '  scp ai-cloud-deploy-20261021.tar root@122.51.12.200:/opt/',
    '  ssh root@122.51.12.200',
    '  cd /opt && tar -xf ai-cloud-deploy-20261021.tar',
    '  rm -rf /opt/ai-cloud && mv /opt/ai-cloud-deploy /opt/ai-cloud',
    '',
    '方式 B（目录直传）：',
    '  scp -r <本目录> root@122.51.12.200:/opt/ai-cloud',
    '（约 3.2GB；也可以用 U 盘 / 宝塔面板，保持目录结构不变）',
    '',
    '## 2. 前置检查（2 核 2G 必看）',
    '  docker version && docker compose version',
    '  free -m          # 看内存与 swap',
    '  df -h /opt       # 新包解包 + 数据卷 + 旧栈备份，建议 >=10GB 可用',
    '没有 swap 或小于 1G 时先加 4G（否则高峰期会被 OOM Killer 杀掉）：',
    '  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile',
    '  echo "/swapfile none swap sw 0 0" >> /etc/fstab',
    '',
    '## 3. 起栈（会硬校验数据：转储/对象不全直接拒绝启动）',
    '  cd /opt/ai-cloud',
    '  bash scripts/load-and-up.sh',
    '  # 不想开 Agent 沙箱（省 ~350MB 内存）：',
    '  #   WITH_SANDBOX=0 bash scripts/load-and-up.sh',
    '',
    '首次启动要 1~3 分钟（6 个 Spring 上下文串行启动）。看日志：',
    '  docker compose --env-file .env logs -f allinone',
    '看到 6 行 "Started ... in ... seconds" 就好了。',
    '',
    '## 4. 验收（16 项：入口/注册关闭/admin 登录/全量行数/MinIO/6 个端口/画布 CORS/模型/沙箱）',
    '  bash scripts/smoke-test.sh',
    '  # 不想花模型额度：SKIP_MODEL_CALL=1 bash scripts/smoke-test.sh',
    '',
    '## 5. 访问',
    '  http://122.51.12.200/',
    '  admin@qq.com / 123456',
    '  （库中另有 156 个历史账号，密码保持原样；注册入口被 nginx 拦成 403）',
    '',
    '## 6. 安全组 / 防火墙',
    '只放行 80。5433/6379/8083/9000/9001/8090 都只绑 127.0.0.1，公网访问不到。',
    '',
    '## 7. 常用命令',
    '  docker compose --env-file .env ps',
    '  docker compose --env-file .env logs -f allinone',
    '  docker compose --env-file .env restart allinone',
    '  docker stats --no-stream        # 看内存占用',
    '',
    '## 8. 回滚到旧栈',
    '  cd /opt/ai-cloud && docker compose --env-file .env down',
    '  cd /opt/ai-lite  && docker compose --env-file .env up -d',
    '旧栈的卷 ai-lite-pgdata / ai-lite-uploads 与 data/minio 都还在。',
    '',
    '## 9. 排障先看 README.md 的「排障」一节'
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
