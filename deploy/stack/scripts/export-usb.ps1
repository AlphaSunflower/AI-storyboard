# 把迁移到另一台机器所需要的文件汇总到一个目录（可直接拷进 U 盘 / scp）
#
#   powershell -File scripts\export-usb.ps1 -Target E:\Desktop\aistoryboard-deploy
#   powershell -File scripts\export-usb.ps1 -Target E:\... -WithSources
#
# 目标目录即完整交付包：deploy/stack 的全部运行文件 + data/（MinIO 与沙箱迁移数据）
# + offline/（离线镜像）+ db/*.dump。目标机只要 bash scripts/load-and-up.sh。
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$WithSources
)

$ErrorActionPreference = 'Stop'
$StackDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RepoRoot = (Resolve-Path (Join-Path $StackDir '..\..')).Path

$targetFull = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Target)
$repoTrim = $RepoRoot.TrimEnd('\')
if ($targetFull.TrimEnd('\') -eq $repoTrim -or $targetFull -like "$repoTrim\*") {
    throw "导出目标不能落在仓库内: $targetFull"
}

Write-Host "[export] 目标: $targetFull"
if (Test-Path $targetFull) { Remove-Item $targetFull -Recurse -Force }
New-Item -ItemType Directory -Force -Path $targetFull | Out-Null

# 1) deploy/stack（排除 build 暂存与旧日志；data/、offline/、db/*.dump、.env 都带上）
$stackDst = Join-Path $targetFull 'stack'
robocopy $StackDir $stackDst /E /XD build /XF '*.log' /NFL /NDL /NJH /NJS /NP | Out-Null
Write-Host "[export] deploy/stack -> $stackDst （已排除 build/）"

# 2) 数据体积统计（data 已随 stack 拷贝）
$minio = Join-Path $stackDst 'data\minio'
$ws    = Join-Path $stackDst 'data\sandbox-workspaces'
foreach ($p in @($minio, $ws)) {
    if (Test-Path $p) {
        $s = (Get-ChildItem $p -Recurse -File -Force | Measure-Object Length -Sum)
        Write-Host ("[export] {0}: {1} 个文件 / {2:N1} MB" -f $p.Replace($targetFull, ''), $s.Count, ($s.Sum / 1MB))
    } else {
        Write-Host "[export][警告] 缺少数据目录 $p"
    }
}

# 3) 目标机一页纸
$note = @(
    '# AI-storyboard 部署包',
    '',
    '## 1. 上传',
    '  把本目录整体传到目标机，例如：scp -r . root@<IP>:/opt/ai-storyboard',
    '',
    '## 2. 前置',
    '  - Docker 24+ 与 compose v2',
    '  - 内存 ≥ 8G（全栈 12 个容器）；4G 机器按 stack/README.md 调小 *_MEM_LIMIT',
    '',
    '## 3. 修改配置（stack/.env）',
    '  - PUBLIC_ORIGIN 留空 = 起栈时自动取本机默认出口网卡的内网 IP；',
    '    公网 IP / 域名请显式填写（http://公网IP 或 https://域名）',
    '  - FRONTEND_PORT 不是 80 时一并改',
    '',
    '## 4. 起栈（无需联网 / Maven / Node）',
    '  cd stack',
    '  bash scripts/load-and-up.sh',
    '',
    '## 5. 验收',
    '  bash scripts/verify.sh              # 跳过付费模型调用：SKIP_MODEL_CALL=1',
    '',
    '## 6. 访问',
    '  http://<目标机IP>/',
    '  admin@qq.com / 123456',
    '',
    '数据已随包迁移：Postgres（db/*.dump）、MinIO（data/minio）、沙箱工作区（data/sandbox-workspaces）。'
)
[System.IO.File]::WriteAllText((Join-Path $targetFull '部署说明.txt'), ($note -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

# 4) 可选：源码（目标机要重新构建镜像才需要）
if ($WithSources) {
    $srcDst = Join-Path $targetFull 'src'
    robocopy $RepoRoot $srcDst /E /XD node_modules target .git build offline dist .idea .superpowers /XF "*.log" /NFL /NDL /NJH /NJS /NP | Out-Null
    Write-Host "[export] 源码 -> $srcDst"
}

Write-Host ''
Write-Host '[export] 体积统计:'
$rows = foreach ($item in (Get-ChildItem $targetFull -Force)) {
    $sum = if ($item.PSIsContainer) {
        (Get-ChildItem $item.FullName -Recurse -Force -File | Measure-Object Length -Sum).Sum
    } else { $item.Length }
    [pscustomobject]@{ Name = $item.Name; MB = [Math]::Round($sum / 1MB, 1) }
}
$rows | Sort-Object MB -Descending | Format-Table -AutoSize
$total = (Get-ChildItem $targetFull -Recurse -Force -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("[export] 合计 {0:N1} MB -> {1}" -f $total, $targetFull)
