[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Manifest,
    [Parameter(Mandatory = $true)] [string] $Output,
    [Parameter(Mandatory = $true)] [string] $Version,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$outputPath = [IO.Path]::GetFullPath($Output)
$urls = Select-String -Path $manifestPath -Pattern '^\s*-\s+(https?://\S+)' |
    ForEach-Object { $_.Matches[0].Groups[1].Value }
if (-not $urls) { throw "No HTTP sources found in manifest: $manifestPath" }

$metadata = [ordered]@{
    version = $Version
    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
    manifest = $manifestPath
    sourceCount = $urls.Count
    dryRun = [bool]$DryRun
}

if ($DryRun) {
    Write-Host "Dry run: would fetch $($urls.Count) sources for Spring AI $Version into $outputPath"
    $metadata | ConvertTo-Json | Write-Output
    exit 0
}

New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputPath 'sync-metadata.json') -Encoding UTF8
$index = 0
foreach ($url in $urls) {
    $index++
    $safeName = ($url -replace '^https?://', '' -replace '[^A-Za-z0-9._-]', '_')
    $target = Join-Path $outputPath ("{0:D3}-{1}.md" -f $index, $safeName)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 30
        $response.Content | Set-Content -LiteralPath $target -Encoding UTF8
        Write-Host "[$index/$($urls.Count)] $url"
    }
    catch {
        Write-Warning "Failed to fetch $url : $($_.Exception.Message)"
    }
}
