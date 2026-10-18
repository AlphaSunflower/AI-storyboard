param(
    [switch]$SkipUnit
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

if (-not $SkipUnit) {
    Write-Host '==> MoonAgent tests'
    mvn -pl MoonAgent -am test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host '==> Frontend tests + build'
    Push-Location AIStoryboardClient
    try {
        npm test -- --run
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        npm run build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}

if (-not $env:MOON_EMAIL -or -not $env:MOON_PASSWORD -or -not $env:MOON_PROJECT_ID) {
    Write-Host 'SKIP eval: 需要 MOON_EMAIL / MOON_PASSWORD / MOON_PROJECT_ID'
    exit 0
}

Write-Host '==> MoonAgent real-turn eval'
python scripts/eval/run_eval.py --cases scripts/eval/cases.jsonl
exit $LASTEXITCODE
