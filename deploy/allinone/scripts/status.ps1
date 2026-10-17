# 查看单 JVM 全栈状态与资源占用
param(
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_env.ps1')

$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envMap = Get-StackEnv -StackDir $StackDir -EnvFile $EnvFile
$prefix = if ($envMap['STACK_PREFIX']) { $envMap['STACK_PREFIX'] } else { 'ai-lite' }

Push-Location $StackDir
try {
    & docker compose --env-file $EnvFile --profile sandbox ps
} finally { Pop-Location }

Write-Host ''
Write-Host '[status] 资源占用：'
& docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' |
    Where-Object { $_ -like "$prefix-*" }

Write-Host ''
Write-Host '[status] JVM 内存（allinone = 6 个服务共用的唯一 JVM）：'
& docker exec "$prefix-allinone" sh -c 'grep -E "^(VmRSS|VmSwap|VmSize)" /proc/1/status' 2>&1
& docker exec "$prefix-allinone" sh -c 'java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E "MaxHeapSize|MaxMetaspaceSize" ' 2>&1

Write-Host ''
Write-Host '[status] 各服务最近一行启动日志：'
& docker logs --tail 400 "$prefix-allinone" 2>&1 |
    Select-String -Pattern '(Started .*Application in|Tomcat started on port|Netty started on port)' |
    ForEach-Object { $_.Line }
