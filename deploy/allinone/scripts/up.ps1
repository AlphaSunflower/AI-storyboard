# 本机启动单 JVM 全栈
#   powershell -File scripts\up.ps1                      # 用 .env
#   powershell -File scripts\up.ps1 -EnvFile .env.test    # 用测试端口（不影响已在跑的其它栈）
param(
    [string]$EnvFile = '.env',
    [switch]$WithSandbox
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# 让 env 文件赢过 shell 里的遗留变量（compose 插值时 shell 环境优先级更高）
Import-StackEnvToProcess -Map (Get-StackEnv -StackDir $StackDir -EnvFile $EnvFile)

$composeArgs = @('compose', '--env-file', $EnvFile)
if ($WithSandbox) {
    # -WithSandbox = 既启动 sandbox profile，也把 moon-agent 的沙箱开关打开
    $composeArgs += @('--profile', 'sandbox')
    Set-Item -Path 'Env:AGENT_SANDBOX_ENABLED' -Value 'true'
}
$composeArgs += @('up', '-d', '--no-build')

Push-Location $StackDir
try {
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败' }
    & docker compose --env-file $EnvFile ps
} finally { Pop-Location }

Write-Host ''
Write-Host '[up] 首次启动 6 个 Spring 上下文需要 1~3 分钟，日志：'
Write-Host "  docker compose --env-file $EnvFile logs -f allinone"
