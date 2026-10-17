# 读取 deploy/allinone/.env（或 -EnvFile 指定的那份）的小工具，其它脚本 dot-source 它
function Get-StackEnv {
    param(
        [Parameter(Mandatory = $true)][string]$StackDir,
        [string]$EnvFile = '.env'
    )
    $file = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $StackDir $EnvFile }
    if (-not (Test-Path $file)) { throw "缺少 $file（可从 .env.example 复制一份）" }
    $map = @{}
    foreach ($line in Get-Content $file) {
        $text = $line.Trim()
        if ($text -eq '' -or $text.StartsWith('#')) { continue }
        $idx = $text.IndexOf('=')
        if ($idx -lt 1) { continue }
        $map[$text.Substring(0, $idx).Trim()] = $text.Substring($idx + 1).Trim()
    }
    return $map
}

# 把 env 文件的值写进当前进程环境。
# 原因：docker compose 的变量插值里「shell 环境变量」优先级高于 --env-file，
# 开发者机器上常见的遗留变量（REDIS_PORT / POSTGRES_PASSWORD …）会悄悄盖掉 .env，导致端口错位。
function Import-StackEnvToProcess {
    param([Parameter(Mandatory = $true)][hashtable]$Map)
    foreach ($key in $Map.Keys) {
        Set-Item -Path "Env:$key" -Value $Map[$key]
    }
}
