# 从本地运行中的 PostgreSQL 导出全量业务数据，并生成 data-manifest.env
#
#   powershell -ExecutionPolicy Bypass -File scripts\export-db.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\export-db.ps1 -PgHost 127.0.0.1 -PgPort 5432 -PgUser postgres -PgPassword 123456
#
# 导出内容（都是 pg_dump 自定义格式，pg_restore 可直接还原）：
#   db/ai_storyboard.dump   业务库（public + asset + agentscope 三个 schema 全量）
#   db/llm_gateway.dump     模型网关库（渠道、路由、密钥、参数）
#   db/data-manifest.env    行数清单，供还原后 05-verify-data.sh 自检
param(
    [string]$PgHost = '127.0.0.1',
    [int]$PgPort = 5432,
    [string]$PgUser = 'postgres',
    [string]$PgPassword = '123456',
    [string]$PgBin = 'D:\Program Files\PostgreSQL\18\bin'
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$DbDir = Join-Path $StackDir 'db'
New-Item -ItemType Directory -Force -Path $DbDir | Out-Null

$env:PGPASSWORD = $PgPassword
$pgDump = Join-Path $PgBin 'pg_dump.exe'
$psql = Join-Path $PgBin 'psql.exe'
foreach ($tool in @($pgDump, $psql)) {
    if (-not (Test-Path $tool)) { throw "找不到 $tool（用 -PgBin 指定 PostgreSQL bin 目录）" }
}

function Export-Database([string]$db, [string]$file) {
    $path = Join-Path $DbDir $file
    Write-Host "[db] $db -> $path"
    & $pgDump -h $PgHost -p $PgPort -U $PgUser -d $db --format=custom --compress=9 --no-owner --no-privileges --file=$path
    if ($LASTEXITCODE -ne 0) { throw "pg_dump $db 失败（exit=$LASTEXITCODE）" }
    $mb = [math]::Round((Get-Item $path).Length / 1MB, 2)
    Write-Host "[db] $db 完成（$mb MB）"
}

function Scalar([string]$db, [string]$sql) {
    $out = & $psql -h $PgHost -p $PgPort -U $PgUser -d $db -tAc $sql
    if ($LASTEXITCODE -ne 0) { throw "查询失败: $sql" }
    return ($out | Select-Object -First 1).Trim()
}

Export-Database 'ai_storyboard' 'ai_storyboard.dump'
Export-Database 'llm_gateway' 'llm_gateway.dump'

$counts = [ordered]@{
    USERS               = Scalar 'ai_storyboard' 'select count(*) from public.users'
    PROJECTS            = Scalar 'ai_storyboard' 'select count(*) from public.projects'
    CONVERSATIONS       = Scalar 'ai_storyboard' 'select count(*) from public.conversations'
    AGENT_MESSAGES      = Scalar 'ai_storyboard' 'select count(*) from public.agent_messages'
    AGENT_ASSETS        = Scalar 'ai_storyboard' 'select count(*) from public.agent_assets'
    CANVAS_ASSETS       = Scalar 'ai_storyboard' 'select count(*) from public.canvas_assets'
    SCENES              = Scalar 'ai_storyboard' 'select count(*) from public.scenes'
    MORROW_CANVASES     = Scalar 'ai_storyboard' 'select count(*) from public.morrow_canvases'
    LIBRARY_ASSETS      = Scalar 'ai_storyboard' 'select count(*) from asset.library_assets'
    LIBRARY_MEDIA       = Scalar 'ai_storyboard' 'select count(*) from asset.library_media'
    AGENTSCOPE_SESSIONS = Scalar 'ai_storyboard' 'select count(*) from agentscope.agentscope_sessions'
}

# MinIO 对象清单（只统计，不复制；复制由 export-data.ps1 负责）
$minioDir = Join-Path $StackDir 'data\minio'
$minioFiles = 0
$minioBytes = 0
if (Test-Path $minioDir) {
    $files = Get-ChildItem $minioDir -Recurse -File -Force
    $minioFiles = $files.Count
    $minioBytes = ($files | Measure-Object Length -Sum).Sum
} else {
    Write-Host '[db][警告] data/minio 不存在，MinIO 对象清单记为 0'
}

$lines = @(
    '# 全量数据清单（由 scripts/export-db.ps1 在 pg_dump 同时生成）',
    '# 还原后 05-verify-data.sh 会对比这些数字；不一致只告警，不阻断启动。',
    "EXPORTED_AT=$((Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz'))"
)
foreach ($key in $counts.Keys) { $lines += "$key=$($counts[$key])" }
$lines += "MINIO_FILES=$minioFiles"
$lines += "MINIO_BYTES=$minioBytes"
$manifest = Join-Path $DbDir 'data-manifest.env'
[System.IO.File]::WriteAllText($manifest, ($lines -join "`n"), (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "[db] 行数清单 -> $manifest"
$counts.GetEnumerator() | ForEach-Object { Write-Host ("     {0,-20} {1}" -f $_.Key, $_.Value) }
Write-Host ("     {0,-20} {1}" -f 'MINIO_FILES', $minioFiles)
Write-Host ''
Write-Host '[db] 下一步：scripts\export-data.ps1（复制 MinIO 对象）'
