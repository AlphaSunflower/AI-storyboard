# 把本目录下的 shell / nginx / Dockerfile 统一成 LF，避免 Windows 上准备、Linux 上运行时的
# "/bin/bash^M: bad interpreter"（会让 postgres 静默跳过建库与还原）。
# export-package.ps1 会自动调用它；单独跑也安全（幂等）。
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }

$patterns = @('*.sh', '*.conf', 'Dockerfile')
$utf8 = New-Object System.Text.UTF8Encoding($false)
$fixed = 0
$scanned = 0

foreach ($pattern in $patterns) {
    Get-ChildItem $Root -Recurse -File -Filter $pattern | ForEach-Object {
        # 跳过构建暂存目录（那些是产物/副本，没必要也不该改）
        if ($_.FullName -like "*$([IO.Path]::DirectorySeparatorChar)build$([IO.Path]::DirectorySeparatorChar)*") { return }
        $scanned++
        $text = [System.IO.File]::ReadAllText($_.FullName)
        if ($text.Contains("`r`n")) {
            $normalized = $text -replace "`r`n", "`n"
            [System.IO.File]::WriteAllText($_.FullName, $normalized, $utf8)
            $fixed++
            Write-Host "[lf] 已转成 LF: $($_.FullName.Replace($Root, '.'))"
        }
    }
}

Write-Host "[lf] 扫描 $scanned 个文件，修正 $fixed 个"
