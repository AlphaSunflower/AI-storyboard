# 停止单 JVM 全栈（保留数据卷；不要用 down -v，那会删掉数据库/上传文件/沙箱工作区）
param(
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Push-Location $StackDir
try {
    & docker compose --env-file $EnvFile --profile sandbox down
} finally { Pop-Location }
