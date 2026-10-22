# 重建 ai_storyboard / llm_gateway 两个库的表结构（2026-10-22 备份）
#
# 用法：
#   .\restore.ps1                                  # 默认 127.0.0.1:5432 postgres
#   .\restore.ps1 -PgPort 55432 -PgUser postgres
#   .\restore.ps1 -Force                           # 目标库已存在时先删除
#
# 依赖：psql / createdb / dropdb 在 PATH，或通过 -PgBin 指定 bin 目录

param(
    [string]$PgHost = '127.0.0.1',
    [int]$PgPort = 5432,
    [string]$PgUser = 'postgres',
    [string]$PgBin = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-PgTool([string]$name) {
    if ($PgBin) { return (Join-Path $PgBin "$name.exe") }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "找不到 $name.exe，请用 -PgBin 指定 PostgreSQL bin 目录" }
    return $cmd.Source
}

$psql = Resolve-PgTool 'psql'
$createdb = Resolve-PgTool 'createdb'
$dropdb = Resolve-PgTool 'dropdb'

$targets = @(
    @{ Db = 'ai_storyboard'; File = 'ai_storyboard_schema.sql' },
    @{ Db = 'llm_gateway';   File = 'llm_gateway_schema.sql' }
)

foreach ($t in $targets) {
    $db = $t.Db
    $sql = Join-Path $here $t.File
    if (-not (Test-Path $sql)) { throw "缺少文件：$sql" }

    $exists = & $psql -h $PgHost -p $PgPort -U $PgUser -d postgres -tAc "select 1 from pg_database where datname='$db'"
    if ($exists.Trim() -eq '1') {
        if (-not $Force) {
            Write-Host "[skip] $db 已存在（加 -Force 可重建）" -ForegroundColor Yellow
            continue
        }
        Write-Host "[drop] $db" -ForegroundColor DarkYellow
        & $dropdb -h $PgHost -p $PgPort -U $PgUser --if-exists $db
    }

    Write-Host "[create] $db" -ForegroundColor Cyan
    & $createdb -h $PgHost -p $PgPort -U $PgUser $db

    Write-Host "[apply ] $($t.File)" -ForegroundColor Cyan
    & $psql -h $PgHost -p $PgPort -U $PgUser -d $db -v ON_ERROR_STOP=1 -q -f $sql
    if ($LASTEXITCODE -ne 0) { throw "$db 恢复失败（exit $LASTEXITCODE）" }

    $count = & $psql -h $PgHost -p $PgPort -U $PgUser -d $db -tAc "select count(*) from information_schema.tables where table_type='BASE TABLE' and table_schema not in ('pg_catalog','information_schema')"
    Write-Host "[ok    ] $db 表数 = $($count.Trim())" -ForegroundColor Green
}

Write-Host ''
Write-Host '可选：灌种子数据' -ForegroundColor DarkGray
Write-Host '  psql -d ai_storyboard -f ..\deploy\stack\db\sql\post-migrate.sql'
Write-Host '  psql -d llm_gateway   -f ..\deploy\stack\db\sql\seed-models.sql'
