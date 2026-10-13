# 一键启动统一栈：接管旧 compose 项目 → 启动全部服务 → 等待健康
param(
    [switch]$SkipPrepare,
    [int]$TimeoutSeconds = 420
)

$ErrorActionPreference = 'Continue'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose  = Join-Path $StackDir 'docker-compose.yml'
$envFile  = Join-Path $StackDir '.env'

if (-not $SkipPrepare) {
    & (Join-Path $PSScriptRoot 'prepare.ps1')
}

Push-Location $StackDir
try {
    Write-Host '[up] docker compose up -d'
    docker compose --env-file $envFile -f $compose up -d
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败' }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $rows = docker compose --env-file $envFile -f $compose ps --format '{{.Service}}|{{.State}}|{{.Health}}'
        $bad = @()
        foreach ($r in $rows) {
            $parts = $r -split '\|'
            $state = $parts[1]; $health = $parts[2]
            if ($state -ne 'running') { $bad += "$($parts[0]):$state" }
            elseif ($health -and $health -ne 'healthy') { $bad += "$($parts[0]):$health" }
        }
        if ($bad.Count -eq 0 -and $rows.Count -gt 0) {
            Write-Host '[up] 全部服务 running/healthy'
            break
        }
        Write-Host ("[up] 等待中… 未就绪: " + ($bad -join ', '))
        Start-Sleep -Seconds 10
    }

    docker compose --env-file $envFile -f $compose ps
} finally { Pop-Location }

Write-Host '[up] 访问入口: http://localhost/'
