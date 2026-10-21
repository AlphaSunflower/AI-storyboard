# 把运行中的沙箱命名卷导出回 ./data/sandbox-workspaces（备份 / 重新出包前执行）
#
#   powershell -File scripts\export-sandbox.ps1
#   powershell -File scripts\export-sandbox.ps1 -EnvFile .env.verify
param([string]$EnvFile = '.env')

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $StackDir $EnvFile }
if (-not (Test-Path $envPath)) { throw "找不到 env 文件: $envPath" }

$cfg = @{}
Get-Content $envPath | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $cfg[$kv[0]] = $kv[1].Trim()
}
$prefix = $cfg['STACK_PREFIX']; if (-not $prefix) { $prefix = 'aistoryboard' }
$vol = "$prefix-sandbox-workspaces"
$out = Join-Path $StackDir 'data\sandbox-workspaces'
$script = Join-Path $PSScriptRoot 'export-sandbox.sh'
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "[export] 卷 $vol -> $out"
docker run --rm --entrypoint sh `
    -v "${vol}:/w:ro" `
    -v "${out}:/out" `
    -v "${script}:/export.sh:ro" `
    alpine /export.sh
if ($LASTEXITCODE -ne 0) { throw '导出沙箱卷失败' }

$s = Get-ChildItem $out -Recurse -File -Force | Measure-Object Length -Sum
Write-Host ("[export] 完成：{0} 个文件 / {1:N1} MB" -f $s.Count, ($s.Sum / 1MB))
