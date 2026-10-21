# 停止统一栈（默认保留数据卷与 ./data 内的 MinIO / 沙箱数据）
param(
    [string]$EnvFile = '.env',
    [switch]$RemoveVolumes
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envPath  = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $StackDir $EnvFile }

# 宿主 shell 遗留变量会覆盖 --env-file（例如 REDIS_PORT），这里用 env 文件覆盖进程环境
Get-Content $envPath | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    Set-Item -Path "Env:$($kv[0])" -Value $kv[1].Trim()
}

Push-Location $StackDir
try {
    if ($RemoveVolumes) {
        Write-Host '[down][警告] 同时删除本项目命名卷（pgdata/redis/uploads）；./data 宿主目录不受影响'
        docker compose --env-file $envPath -f $compose down -v
    } else {
        docker compose --env-file $envPath -f $compose down
    }
} finally { Pop-Location }
